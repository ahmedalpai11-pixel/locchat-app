package com.example.mesh

import android.content.Context
import android.net.Uri
import com.example.data.BlockReportEntity
import com.example.data.ChannelMembershipEntity
import com.example.data.ContactsManager
import com.example.data.ConversationEntity
import com.example.data.CryptoManager
import com.example.data.DirectSessionManager
import com.example.data.FeedPostEntity
import com.example.data.LocChatDatabase
import com.example.data.LocalIdentityEntity
import com.example.data.MediaHelper
import com.example.data.MessageEntity
import com.example.data.PostCommentEntity
import com.example.data.SendKey
import com.example.data.StaticKeyProvider
import com.example.model.AppLanguage
import com.example.model.ChatConversation
import com.example.model.ChatMessage
import com.example.model.DeliveryStatus
import com.example.model.FeedPost
import com.example.model.MeshEngineStatus
import com.example.model.MeshIdentity
import com.example.model.MeshPowerMode
import com.example.model.MessageType
import com.example.model.NearbyPeer
import com.example.model.PostCategory
import com.example.model.PostComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MeshRepository private constructor(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                android.util.Log.e("MeshRepository", "Unhandled coroutine error", e)
            }
    )
    private val db = LocChatDatabase.getInstance(context)
    private val cryptoManager = CryptoManager(context)
    private val directSessions = DirectSessionManager(
        keys = object : StaticKeyProvider {
            override fun publicKey(): ByteArray? = cryptoManager.myX25519PublicBytes()
            override fun dh(peerPub: ByteArray): ByteArray? = cryptoManager.x25519StaticDh(peerPub)
        },
        persist = { blob -> runCatching { cryptoManager.saveSessionBlob(blob) } }
    ).also { mgr -> runCatching { mgr.restore(cryptoManager.loadSessionBlob()) } }
    private val mediaChunkAssembler = MediaChunkAssembler()

    val contactsManager = ContactsManager(db.verifiedContactDao(), db.blockedPeerDao())
    val mediaHelper = MediaHelper(context)
    val notificationHelper = MessageNotificationHelper(context)

    @Volatile
    private var isAppInForeground: Boolean = false

    private val channelPassphrases = ConcurrentHashMap<String, String>()
    private val pendingDirectPayloads = ConcurrentHashMap<String, MutableList<PendingDirectItem>>()

    private val _identity = MutableStateFlow(cryptoManager.getOrCreateIdentity())
    val identity: StateFlow<MeshIdentity> = _identity.asStateFlow()

    private val _isNicknameConfigured = MutableStateFlow(cryptoManager.isNicknameConfigured())
    val isNicknameConfigured: StateFlow<Boolean> = _isNicknameConfigured.asStateFlow()

    private val _isBiometricLockEnabled = MutableStateFlow(cryptoManager.isBiometricLockEnabled())
    val isBiometricLockEnabled: StateFlow<Boolean> = _isBiometricLockEnabled.asStateFlow()

    private val _isStealthMode = MutableStateFlow(cryptoManager.isStealthModeEnabled())
    val isStealthMode: StateFlow<Boolean> = _isStealthMode.asStateFlow()

    private val _autoApplyTrustedBlocks = MutableStateFlow(cryptoManager.isAutoApplyTrustedBlocksEnabled())
    val autoApplyTrustedBlocks: StateFlow<Boolean> = _autoApplyTrustedBlocks.asStateFlow()

    private val _maxHopTtl = MutableStateFlow(cryptoManager.getMaxHopTtl())
    val maxHopTtl: StateFlow<Int> = _maxHopTtl.asStateFlow()

    private val _language = MutableStateFlow(cryptoManager.getLanguage())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _mediaTransferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val mediaTransferProgress: StateFlow<Map<String, Float>> = _mediaTransferProgress.asStateFlow()

    private val _activeLocalIdentity = MutableStateFlow<LocalIdentityEntity?>(null)
    val activeLocalIdentity: StateFlow<LocalIdentityEntity?> = _activeLocalIdentity.asStateFlow()

    private val _channelMemberships = MutableStateFlow<List<ChannelMembershipEntity>>(emptyList())
    val channelMemberships: StateFlow<List<ChannelMembershipEntity>> = _channelMemberships.asStateFlow()

    private val _blockReports = MutableStateFlow<List<BlockReportEntity>>(emptyList())
    val blockReports: StateFlow<List<BlockReportEntity>> = _blockReports.asStateFlow()

    private val _feedPosts = MutableStateFlow<List<FeedPost>>(emptyList())
    val feedPosts: StateFlow<List<FeedPost>> = _feedPosts.asStateFlow()

    private val _conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    val conversations: StateFlow<List<ChatConversation>> = _conversations.asStateFlow()

    private val _messagesByChat = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messagesByChat: StateFlow<Map<String, List<ChatMessage>>> = _messagesByChat.asStateFlow()

    private val bleEngine = BleMeshEngine(
        context = context,
        cryptoManager = cryptoManager,
        contactsManager = contactsManager,
        onPeerKeyLearned = { peerId, x25519KeyHex ->
            onPeerKeyKnown(peerId, x25519KeyHex)
        },
        onPacketReceived = { packet -> handleIncomingPacket(packet) }
    )

    val nearbyPeers: StateFlow<List<NearbyPeer>> = bleEngine.nearbyPeers
    val meshStatus: StateFlow<MeshEngineStatus> = bleEngine.status

    init {
        syncLocalIdentityToRoom(_identity.value)
        observeDatabase()
        ensureDefaultChannel()
    }

    fun startMeshEngine() {
        bleEngine.start(_identity.value)
    }

    fun stopMeshEngine() {
        bleEngine.stop()
    }

    fun setMeshPowerMode(mode: MeshPowerMode) {
        bleEngine.setPowerMode(mode)
    }

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
    }

    private fun observeDatabase() {
        scope.launch {
            combine(
                db.feedPostDao().observeAll(),
                db.postCommentDao().observeAll()
            ) { posts, comments ->
                val now = System.currentTimeMillis()
                val commentsByPost = comments.groupBy { it.postId }
                posts.filter { post ->
                    now <= post.createdAt + post.ttlHours * 3_600_000L
                }.map { entity ->
                    FeedPost(
                        id = entity.id,
                        authorId = entity.authorId,
                        authorName = entity.authorName,
                        text = entity.text,
                        imagePath = entity.imagePath,
                        imageSizeKb = entity.imageSizeKb,
                        category = PostCategory.entries.firstOrNull { it.name == entity.category } ?: PostCategory.GENERAL,
                        ttlHours = entity.ttlHours,
                        createdAt = entity.createdAt,
                        likesCount = entity.likesCount,
                        isLikedByMe = entity.isLikedByMe,
                        reactions = decodeReactions(entity.reactionsJson),
                        myReaction = entity.myReaction,
                        comments = (commentsByPost[entity.id] ?: emptyList()).map { c ->
                            PostComment(
                                id = c.id,
                                postId = c.postId,
                                authorId = c.authorId,
                                authorName = c.authorName,
                                text = c.text,
                                createdAt = c.createdAt
                            )
                        },
                        hopCount = entity.hopCount
                    )
                }
            }.collect { _feedPosts.value = it }
        }

        scope.launch {
            combine(
                db.conversationDao().observeAll(),
                contactsManager.verifiedContacts,
                bleEngine.nearbyPeers
            ) { convs, verifiedList, peers ->
                val verifiedById = verifiedList.associateBy { it.peerId.uppercase() }
                val onlineIds = peers.filter { !it.isBlocked }.map { it.peerId.uppercase() }.toSet()
                val existingConvs = convs.map { c ->
                    val friend = verifiedById[c.id.uppercase()]
                    ChatConversation(
                        id = c.id,
                        title = friend?.nickname?.takeIf { it.isNotBlank() } ?: c.title,
                        isChannel = c.isChannel,
                        peerFingerprint = friend?.fingerprint?.takeIf { it.isNotBlank() } ?: c.peerFingerprint,
                        peerX25519KeyHex = c.peerX25519KeyHex.ifBlank { friend?.x25519PublicKeyHex ?: "" },
                        isVerified = c.isVerified || friend != null,
                        isProtectedChannel = c.channelPassphraseHash.isNotEmpty(),
                        lastMessage = c.lastMessage,
                        lastMessageTime = c.lastMessageTime,
                        unreadCount = c.unreadCount,
                        isOnline = c.isChannel || onlineIds.contains(c.id.uppercase())
                    )
                }
                val existingIds = existingConvs.map { it.id.uppercase() }.toSet()
                val missingFriends = verifiedList
                    .filter { !contactsManager.isBlocked(it.peerId) && !existingIds.contains(it.peerId.uppercase()) }
                    .map { friend ->
                        ChatConversation(
                            id = friend.peerId,
                            title = friend.nickname.ifBlank { friend.peerId },
                            isChannel = false,
                            peerFingerprint = friend.fingerprint,
                            peerX25519KeyHex = friend.x25519PublicKeyHex,
                            isVerified = true,
                            isProtectedChannel = false,
                            lastMessage = com.example.ui.stringsFor(_language.value).chatsReadyToMessage,
                            lastMessageTime = friend.verifiedAt,
                            unreadCount = 0,
                            isOnline = onlineIds.contains(friend.peerId.uppercase())
                        )
                    }
                (existingConvs + missingFriends).sortedByDescending { it.lastMessageTime }
            }.collect { _conversations.value = it }
        }

        scope.launch {
            db.messageDao().observeAll().collect { list ->
                val grouped = list.map { e ->
                    ChatMessage(
                        id = e.id,
                        chatId = e.chatId,
                        senderId = e.senderId,
                        senderName = e.senderName,
                        isFromMe = e.isFromMe,
                        type = MessageType.entries.firstOrNull { it.name == e.type } ?: MessageType.TEXT,
                        text = e.text,
                        mediaPath = e.mediaPath,
                        voiceDurationSec = e.voiceDurationSec,
                        replyPreview = e.replyPreview,
                        reactions = decodeReactions(e.reactionsJson),
                        myReaction = e.myReaction,
                        timestamp = e.timestamp,
                        status = DeliveryStatus.entries.firstOrNull { it.name == e.status } ?: DeliveryStatus.SENT,
                        isEncrypted = e.isEncrypted,
                        hopCount = e.hopCount
                    )
                }.groupBy { it.chatId }
                _messagesByChat.value = grouped
            }
        }

        scope.launch {
            db.localIdentityDao().observeActiveIdentity().collect { entity ->
                _activeLocalIdentity.value = entity
            }
        }

        scope.launch {
            db.channelMembershipDao().observeAllActive().collect { list ->
                _channelMemberships.value = list
            }
        }

        scope.launch {
            db.blockReportDao().observeActive().collect { reports ->
                _blockReports.value = reports
            }
        }
    }

    private fun ensureDefaultChannel() {
        scope.launch {
            val defaultId = "#عام-الحي"
            val now = System.currentTimeMillis()
            if (db.conversationDao().getById(defaultId) == null) {
                db.conversationDao().upsert(
                    ConversationEntity(
                        id = defaultId,
                        title = defaultId,
                        isChannel = true,
                        peerFingerprint = "BROADCAST",
                        isVerified = false,
                        lastMessage = "قناة مفتوحة للتواصل المحلي عبر شبكة البلوتوث",
                        lastMessageTime = now,
                        unreadCount = 0
                    )
                )
            }
            val me = _identity.value
            val membershipId = "$defaultId|${me.nodeId}"
            if (db.channelMembershipDao().getById(membershipId) == null) {
                db.channelMembershipDao().upsert(
                    ChannelMembershipEntity(
                        membershipId = membershipId,
                        channelId = defaultId,
                        channelName = defaultId,
                        memberNodeId = me.nodeId,
                        memberNickname = me.nickname,
                        role = "OWNER",
                        isProtected = false,
                        passphraseHash = "",
                        isActive = true,
                        joinedAt = now,
                        lastSeenAt = now
                    )
                )
            }
        }
    }

    fun openOrCreateConversation(
        peerId: String,
        nickname: String,
        isChannel: Boolean,
        passphrase: String = ""
    ): ChatConversation {
        val cleanId = peerId.trim()
        if (isChannel && passphrase.isNotBlank()) {
            channelPassphrases[cleanId] = passphrase
        }

        val existing = _conversations.value.firstOrNull { it.id == cleanId }
        if (existing != null) {
            scope.launch { db.conversationDao().clearUnread(cleanId) }
            return existing.copy(unreadCount = 0)
        }

        val verified = contactsManager.isVerified(cleanId)
        val peerXKey = bleEngine.getPeerX25519Key(cleanId) ?: ""
        val fp = contactsManager.getContact(cleanId)?.fingerprint
            ?: nearbyPeers.value.firstOrNull { it.peerId == cleanId }?.fingerprint
            ?: if (isChannel) "BROADCAST" else cleanId
        val passHash = if (isChannel && passphrase.isNotBlank()) {
            cryptoManager.hashPassphrase(passphrase)
        } else ""

        val conv = ChatConversation(
            id = cleanId,
            title = nickname.ifBlank { cleanId },
            isChannel = isChannel,
            peerFingerprint = fp,
            peerX25519KeyHex = peerXKey,
            isVerified = verified,
            isProtectedChannel = passHash.isNotEmpty(),
            lastMessage = if (isChannel) "تم إنشاء القناة المحلية" else "بدء محادثة مشفرة (X25519 + AES-GCM + سرية مستقبلية)",
            lastMessageTime = System.currentTimeMillis(),
            unreadCount = 0,
            isOnline = isChannel || nearbyPeers.value.any { it.peerId == cleanId }
        )

        scope.launch {
            db.conversationDao().upsert(
                ConversationEntity(
                    id = conv.id,
                    title = conv.title,
                    isChannel = conv.isChannel,
                    peerFingerprint = conv.peerFingerprint,
                    peerX25519KeyHex = conv.peerX25519KeyHex,
                    channelPassphraseHash = passHash,
                    isVerified = conv.isVerified,
                    lastMessage = conv.lastMessage,
                    lastMessageTime = conv.lastMessageTime,
                    unreadCount = 0
                )
            )
            if (isChannel) {
                val me = _identity.value
                val now = System.currentTimeMillis()
                val membershipId = "${conv.id}|${me.nodeId}"
                val prev = db.channelMembershipDao().getById(membershipId)
                db.channelMembershipDao().upsert(
                    ChannelMembershipEntity(
                        membershipId = membershipId,
                        channelId = conv.id,
                        channelName = conv.title,
                        memberNodeId = me.nodeId,
                        memberNickname = me.nickname,
                        role = prev?.role ?: "OWNER",
                        isProtected = passHash.isNotEmpty(),
                        passphraseHash = passHash,
                        isActive = true,
                        joinedAt = prev?.joinedAt ?: now,
                        lastSeenAt = now
                    )
                )
            }
            if (!isChannel) {
                if (peerXKey.isBlank()) bleEngine.requestKeyHandshake()
                if (!directSessions.hasSession(cleanId)) ensureDirectSession(cleanId)
            }
        }
        return conv
    }

    fun leaveChannel(channelId: String) {
        scope.launch {
            db.channelMembershipDao().markLeft(channelId, _identity.value.nodeId)
        }
    }

    fun markConversationRead(chatId: String) {
        notificationHelper.cancelNotificationForChat(chatId)
        scope.launch {
            db.conversationDao().clearUnread(chatId)
        }
    }

    fun clearChatHistory(chatId: String) {
        scope.launch {
            db.messageDao().deleteByChatId(chatId)
        }
    }

    fun publishFeedPost(
        text: String,
        imgPath: String?,
        sizeKb: Int,
        category: PostCategory,
        ttlHours: Int
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty() && imgPath == null) return
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val me = _identity.value

        scope.launch {
            db.feedPostDao().upsert(
                FeedPostEntity(
                    id = id,
                    authorId = me.nodeId,
                    authorName = me.nickname,
                    text = cleanText,
                    imagePath = imgPath,
                    imageSizeKb = sizeKb,
                    category = category.name,
                    ttlHours = ttlHours,
                    createdAt = now,
                    likesCount = 0,
                    isLikedByMe = false,
                    hopCount = 0
                )
            )

            val imgB64 = mediaHelper.encodeFileToBase64(imgPath)
            val payloadObj = JSONObject().apply {
                put("postId", id)
                put("text", cleanText)
                put("cat", category.name)
                put("ttl", ttlHours)
                put("kb", sizeKb)
                if (imgB64 != null) put("imgB64", imgB64)
            }
            val packet = signedPacket(
                type = MeshPacketType.FEED_POST,
                targetId = "*",
                isChannel = true,
                payload = payloadObj.toString(),
                timestamp = now
            )
            bleEngine.broadcastPacket(packet)
        }
    }

    fun togglePostLike(postId: String) {
        scope.launch {
            val current = db.feedPostDao().getById(postId) ?: return@launch
            val nextLiked = !current.isLikedByMe
            val nextCount = (current.likesCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
            db.feedPostDao().upsert(
                current.copy(
                    isLikedByMe = nextLiked,
                    likesCount = nextCount
                )
            )
            val payloadObj = JSONObject().apply {
                put("kind", "FEED_LIKE")
                put("postId", postId)
                put("delta", if (nextLiked) 1 else -1)
            }
            bleEngine.broadcastPacket(
                signedPacket(
                    type = MeshPacketType.REACTION,
                    targetId = "*",
                    isChannel = true,
                    payload = payloadObj.toString(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun reactToFeedPost(postId: String, emoji: String) {
        val cleanEmoji = emoji.trim().take(8)
        if (cleanEmoji.isEmpty()) return
        scope.launch {
            val current = db.feedPostDao().getById(postId) ?: return@launch
            val map = decodeReactions(current.reactionsJson).toMutableMap()
            val prev = current.myReaction
            if (prev != null) {
                val c = (map[prev] ?: 1) - 1
                if (c <= 0) map.remove(prev) else map[prev] = c
            }
            val nextMy = if (prev == cleanEmoji) null else cleanEmoji
            if (nextMy != null) {
                map[nextMy] = (map[nextMy] ?: 0) + 1
            }
            db.feedPostDao().upsert(
                current.copy(
                    reactionsJson = encodeReactions(map),
                    myReaction = nextMy
                )
            )
            val payloadObj = JSONObject().apply {
                put("kind", "FEED_REACT")
                put("postId", postId)
                put("emoji", nextMy ?: "")
                if (prev != null && prev != nextMy) put("prev", prev)
            }
            bleEngine.broadcastPacket(
                signedPacket(
                    type = MeshPacketType.REACTION,
                    targetId = "*",
                    isChannel = true,
                    payload = payloadObj.toString(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun reBroadcastFeedPost(postId: String) {
        scope.launch {
            val current = db.feedPostDao().getById(postId) ?: return@launch
            val imgB64 = mediaHelper.encodeFileToBase64(current.imagePath)
            val payloadObj = JSONObject().apply {
                put("postId", current.id)
                put("text", current.text)
                put("cat", current.category)
                put("ttl", current.ttlHours)
                put("kb", current.imageSizeKb)
                if (imgB64 != null) put("imgB64", imgB64)
            }
            val packet = signedPacket(
                type = MeshPacketType.FEED_POST,
                targetId = "*",
                isChannel = true,
                payload = payloadObj.toString(),
                timestamp = System.currentTimeMillis()
            )
            bleEngine.broadcastPacket(packet)
        }
    }

    fun addPostComment(postId: String, commentText: String) {
        val clean = commentText.trim().take(1000)
        if (clean.isEmpty()) return
        val me = _identity.value
        val now = System.currentTimeMillis()
        val commentId = UUID.randomUUID().toString()

        scope.launch {
            db.postCommentDao().upsert(
                PostCommentEntity(
                    id = commentId,
                    postId = postId,
                    authorId = me.nodeId,
                    authorName = me.nickname,
                    text = clean,
                    createdAt = now
                )
            )

            val payloadObj = JSONObject().apply {
                put("commentId", commentId)
                put("postId", postId)
                put("text", clean)
            }
            val packet = signedPacket(
                type = MeshPacketType.FEED_COMMENT,
                targetId = "*",
                isChannel = true,
                payload = payloadObj.toString(),
                timestamp = now
            )
            bleEngine.broadcastPacket(packet)
        }
    }

    fun sendTextMessage(chatId: String, isChannel: Boolean, text: String, replyPreview: String?) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        if (clean.startsWith("/")) {
            if (handleSlashCommand(chatId, clean)) return
        }

        val me = _identity.value
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        scope.launch {
            val directKey = resolveSendKey(chatId, isChannel)

            db.messageDao().upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderId = me.nodeId,
                    senderName = me.nickname,
                    isFromMe = true,
                    type = MessageType.TEXT.name,
                    text = clean,
                    mediaPath = null,
                    voiceDurationSec = 0,
                    replyPreview = replyPreview,
                    reactionsJson = "{}",
                    myReaction = null,
                    timestamp = now,
                    status = if (directKey != null) DeliveryStatus.SENT.name else DeliveryStatus.SENDING.name,
                    isEncrypted = true,
                    hopCount = 0
                )
            )
            updateConversationPreview(chatId, isChannel, clean, now)

            if (directKey == null) {
                // Queue until X25519 key handshake completes — never fall back to public-ID keys!
                queuePendingDirectItem(
                    chatId,
                    PendingDirectItem(
                        msgId = msgId,
                        packetType = MeshPacketType.CHAT_TEXT,
                        plaintext = clean,
                        replyPreview = replyPreview,
                        mediaType = null,
                        mediaLabel = null,
                        voiceDurationSec = 0,
                        timestamp = now
                    )
                )
                ensureDirectSession(chatId)
                return@launch
            }

            sendEncryptedTextPacket(chatId, isChannel, msgId, clean, replyPreview, directKey, now)
        }
    }

    fun sendImageMessage(chatId: String, isChannel: Boolean, uri: Uri) {
        scope.launch {
            val compressed = mediaHelper.compressImageFromUri(uri) ?: return@launch
            val (path, sizeKb) = compressed
            val me = _identity.value
            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val label = "صورة مضغوطة ($sizeKb ك.ب)"

            val directKey = resolveSendKey(chatId, isChannel)

            db.messageDao().upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderId = me.nodeId,
                    senderName = me.nickname,
                    isFromMe = true,
                    type = MessageType.IMAGE.name,
                    text = label,
                    mediaPath = path,
                    voiceDurationSec = 0,
                    replyPreview = null,
                    reactionsJson = "{}",
                    myReaction = null,
                    timestamp = now,
                    status = if (directKey != null) DeliveryStatus.SENT.name else DeliveryStatus.SENDING.name,
                    isEncrypted = true,
                    hopCount = 0
                )
            )
            updateConversationPreview(chatId, isChannel, "📷 $label", now)

            val imgB64 = mediaHelper.encodeFileToBase64(path) ?: return@launch
            if (directKey == null) {
                queuePendingDirectItem(
                    chatId,
                    PendingDirectItem(
                        msgId = msgId,
                        packetType = MeshPacketType.CHAT_MEDIA,
                        plaintext = imgB64,
                        replyPreview = null,
                        mediaType = "IMAGE",
                        mediaLabel = label,
                        voiceDurationSec = 0,
                        timestamp = now
                    )
                )
                ensureDirectSession(chatId)
                return@launch
            }
            sendEncryptedMediaPacket(chatId, isChannel, msgId, "IMAGE", label, 0, imgB64, directKey, now)
        }
    }

    fun sendVoiceMessage(chatId: String, isChannel: Boolean, file: File, durationSec: Int) {
        val me = _identity.value
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val label = "رسالة صوتية (${durationSec}ث)"

        scope.launch {
            val directKey = resolveSendKey(chatId, isChannel)

            db.messageDao().upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderId = me.nodeId,
                    senderName = me.nickname,
                    isFromMe = true,
                    type = MessageType.VOICE.name,
                    text = label,
                    mediaPath = file.absolutePath,
                    voiceDurationSec = durationSec,
                    replyPreview = null,
                    reactionsJson = "{}",
                    myReaction = null,
                    timestamp = now,
                    status = if (directKey != null) DeliveryStatus.SENT.name else DeliveryStatus.SENDING.name,
                    isEncrypted = true,
                    hopCount = 0
                )
            )
            updateConversationPreview(chatId, isChannel, "🎤 $label", now)

            val voiceB64 = mediaHelper.encodeFileToBase64(file.absolutePath) ?: return@launch
            if (directKey == null) {
                queuePendingDirectItem(
                    chatId,
                    PendingDirectItem(
                        msgId = msgId,
                        packetType = MeshPacketType.CHAT_MEDIA,
                        plaintext = voiceB64,
                        replyPreview = null,
                        mediaType = "VOICE",
                        mediaLabel = label,
                        voiceDurationSec = durationSec,
                        timestamp = now
                    )
                )
                ensureDirectSession(chatId)
                return@launch
            }
            sendEncryptedMediaPacket(chatId, isChannel, msgId, "VOICE", label, durationSec, voiceB64, directKey, now)
        }
    }

    fun sendMessageReaction(chatId: String, isChannel: Boolean, msgId: String, emoji: String) {
        scope.launch {
            val existing = db.messageDao().getById(msgId) ?: return@launch
            val map = decodeReactions(existing.reactionsJson).toMutableMap()
            val prev = existing.myReaction
            if (prev != null) {
                val c = (map[prev] ?: 1) - 1
                if (c <= 0) map.remove(prev) else map[prev] = c
            }
            val nextMy = if (prev == emoji) null else emoji
            if (nextMy != null) {
                map[nextMy] = (map[nextMy] ?: 0) + 1
            }

            db.messageDao().upsert(
                existing.copy(
                    reactionsJson = encodeReactions(map),
                    myReaction = nextMy
                )
            )

            val payloadObj = JSONObject().apply {
                put("msgId", msgId)
                put("chatId", chatId)
                put("emoji", nextMy ?: "")
            }
            bleEngine.broadcastPacket(
                signedPacket(
                    type = MeshPacketType.REACTION,
                    targetId = chatId,
                    isChannel = isChannel,
                    payload = payloadObj.toString(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateNickname(newName: String) {
        val updated = cryptoManager.updateNickname(newName)
        _identity.value = updated
        _isNicknameConfigured.value = true
        bleEngine.updateIdentity(updated)
        scope.launch {
            db.channelMembershipDao().updateMemberNickname(updated.nodeId, updated.nickname, System.currentTimeMillis())
        }
        syncLocalIdentityToRoom(updated)
    }

    private fun syncLocalIdentityToRoom(identity: MeshIdentity) {
        scope.launch {
            db.localIdentityDao().deactivateAll()
            db.localIdentityDao().upsert(
                LocalIdentityEntity(
                    nodeId = identity.nodeId,
                    nickname = identity.nickname,
                    ed25519PublicKeyHex = identity.publicKeyHex,
                    x25519PublicKeyHex = identity.x25519PublicKeyHex,
                    fingerprint = identity.fingerprint,
                    isKeystoreProtected = cryptoManager.isKeystoreProtected(),
                    isNicknameConfigured = cryptoManager.isNicknameConfigured(),
                    isStealthEnabled = _isStealthMode.value,
                    autoApplyTrustedBlocks = _autoApplyTrustedBlocks.value,
                    maxHopTtl = _maxHopTtl.value,
                    isActive = true,
                    createdAt = identity.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        cryptoManager.setBiometricLockEnabled(enabled)
        _isBiometricLockEnabled.value = enabled
    }

    fun setStealthMode(enabled: Boolean) {
        cryptoManager.setStealthModeEnabled(enabled)
        _isStealthMode.value = enabled
        bleEngine.setStealthMode(enabled)
        syncLocalIdentityToRoom(_identity.value)
    }

    fun setAutoApplyTrustedBlocks(enabled: Boolean) {
        cryptoManager.setAutoApplyTrustedBlocksEnabled(enabled)
        _autoApplyTrustedBlocks.value = enabled
        syncLocalIdentityToRoom(_identity.value)
    }

    fun setMaxHopTtl(hops: Int) {
        val safe = hops.coerceIn(1, 7)
        cryptoManager.setMaxHopTtl(safe)
        _maxHopTtl.value = safe
        bleEngine.setMaxHopTtl(safe)
        syncLocalIdentityToRoom(_identity.value)
    }

    fun clearRelayCaches() {
        bleEngine.clearCaches()
    }

    fun isKeystoreProtected(): Boolean = cryptoManager.isKeystoreProtected()

    fun verifyNodeIdMatchesPublicKey(nodeId: String, edPubKeyHex: String): Boolean =
        cryptoManager.verifyNodeIdMatchesPublicKey(nodeId, edPubKeyHex)

    fun formatFingerprintFromHex(edPubKeyHex: String, xPubKeyHex: String): String =
        cryptoManager.formatFingerprintFromHex(edPubKeyHex, xPubKeyHex)

    fun setLanguage(language: AppLanguage) {
        cryptoManager.setLanguage(language)
        _language.value = language
        notificationHelper.ensureNotificationChannel(language)
    }

    fun verifyFriend(
        peerId: String,
        nickname: String,
        fingerprint: String,
        publicKeyHex: String = "",
        x25519KeyHex: String = ""
    ) {
        val cleanId = peerId.trim().uppercase()
        val cleanName = nickname.trim().ifEmpty { cleanId }.take(32)
        var cleanFp = fingerprint.trim().uppercase()
        var resolvedPubKey = publicKeyHex
        var resolvedXKey = x25519KeyHex.ifBlank { bleEngine.getPeerX25519Key(cleanId) ?: "" }
        if (resolvedPubKey.length != 64) {
            // FIX: الإضافة اليدوية لا تُوثَّق إلا إذا كان الجهاز ظاهرًا في الشبكة، ومفتاحه مرتبط بالمعرّف،
            // والبصمة المكتوبة تطابق البصمة الفعلية. وإلا لا يُسجَّل أي توثيق.
            val np = nearbyPeers.value.firstOrNull { it.peerId.equals(cleanId, ignoreCase = true) } ?: return
            if (np.publicKeyHex.length != 64 ||
                !cryptoManager.verifyNodeIdMatchesPublicKey(cleanId, np.publicKeyHex)
            ) return
            fun norm(x: String) = x.filter { it.isLetterOrDigit() }.uppercase()
            if (norm(cleanFp).isEmpty() || norm(cleanFp) == norm(cleanId) || norm(cleanFp) != norm(np.fingerprint)) return
            resolvedPubKey = np.publicKeyHex
            if (resolvedXKey.length != 64) resolvedXKey = np.x25519PublicKeyHex
            cleanFp = np.fingerprint.trim().uppercase()
        }
        scope.launch {
            contactsManager.verifyContact(cleanId, cleanName, cleanFp, resolvedPubKey, resolvedXKey)
            val existingConv = db.conversationDao().getById(cleanId)
            val readyText = com.example.ui.stringsFor(_language.value).chatsReadyToMessage
            db.conversationDao().upsert(
                ConversationEntity(
                    id = cleanId,
                    title = cleanName,
                    isChannel = false,
                    peerFingerprint = cleanFp.ifBlank { existingConv?.peerFingerprint ?: cleanId },
                    peerX25519KeyHex = resolvedXKey.ifBlank { existingConv?.peerX25519KeyHex ?: "" },
                    channelPassphraseHash = "",
                    isVerified = true,
                    lastMessage = existingConv?.lastMessage ?: readyText,
                    lastMessageTime = existingConv?.lastMessageTime ?: System.currentTimeMillis(),
                    unreadCount = existingConv?.unreadCount ?: 0
                )
            )
            bleEngine.refreshPeersFromContacts()
            if (resolvedXKey.length == 64 && !directSessions.hasSession(cleanId)) {
                ensureDirectSession(cleanId)
            }
        }
    }

    fun removeFriend(peerId: String) {
        scope.launch {
            contactsManager.removeVerification(peerId)
            bleEngine.refreshPeersFromContacts()
        }
    }

    fun blockPeer(peerId: String, nickname: String) {
        scope.launch {
            contactsManager.blockPeer(peerId, nickname)
            bleEngine.refreshPeersFromContacts()
        }
    }

    fun blockAndBroadcastPeer(peerId: String, nickname: String, reason: String = "بلاغ حظر من عقدة في الشبكة") {
        val cleanId = peerId.trim().uppercase()
        val cleanName = nickname.trim().ifEmpty { cleanId }.take(32)
        val cleanReason = reason.trim().ifEmpty { "بلاغ حظر محلي" }.take(120)
        scope.launch {
            contactsManager.blockPeer(cleanId, cleanName)
            bleEngine.refreshPeersFromContacts()
            broadcastBlockPacket(cleanId, cleanName, cleanReason)
        }
    }

    fun broadcastBlockForPeer(peerId: String, nickname: String, reason: String = "تحذير حظر مذاع من شبكة LocChat") {
        val cleanId = peerId.trim().uppercase()
        val cleanName = nickname.trim().ifEmpty { cleanId }.take(32)
        val cleanReason = reason.trim().ifEmpty { "تحذير حظر" }.take(120)
        scope.launch {
            broadcastBlockPacket(cleanId, cleanName, cleanReason)
        }
    }

    private fun broadcastBlockPacket(blockedId: String, blockedName: String, reason: String) {
        val payloadObj = JSONObject().apply {
            put("blockedId", blockedId)
            put("blockedName", blockedName)
            put("reason", reason)
        }
        val packet = signedPacket(
            type = MeshPacketType.BLOCK_BROADCAST,
            targetId = "*",
            isChannel = true,
            payload = payloadObj.toString(),
            timestamp = System.currentTimeMillis()
        )
        bleEngine.broadcastPacket(packet, cacheForStoreAndForward = true)
    }

    fun acceptBlockReport(report: BlockReportEntity) {
        scope.launch {
            contactsManager.blockPeer(report.blockedPeerId, report.blockedNickname)
            db.blockReportDao().dismiss(report.reportId)
            bleEngine.refreshPeersFromContacts()
        }
    }

    fun dismissBlockReport(reportId: String) {
        scope.launch {
            db.blockReportDao().dismiss(reportId)
        }
    }

    fun unblockPeer(peerId: String) {
        scope.launch {
            contactsManager.unblockPeer(peerId)
            bleEngine.refreshPeersFromContacts()
        }
    }

    fun performPanicWipe() {
        mediaHelper.wipeAllMedia()
        notificationHelper.cancelAllMessageNotifications()
        bleEngine.stop()
        bleEngine.clearCaches()
        mediaChunkAssembler.clear()
        _mediaTransferProgress.value = emptyMap()
        channelPassphrases.clear()
        pendingDirectPayloads.clear()
        directSessions.clearAll()
        scope.launch {
            db.messageDao().deleteAll()
            db.conversationDao().deleteAll()
            db.channelMembershipDao().deleteAll()
            db.localIdentityDao().deleteAll()
            db.blockReportDao().deleteAll()
            db.postCommentDao().deleteAll()
            db.feedPostDao().deleteAll()
            contactsManager.wipeAll()
            val fresh = cryptoManager.regenerateIdentity()
            _identity.value = fresh
            _isNicknameConfigured.value = false
            _isStealthMode.value = false
            bleEngine.setStealthMode(false)
            syncLocalIdentityToRoom(fresh)
            ensureDefaultChannel()
            bleEngine.start(fresh)
        }
    }

    private fun buildChatAad(
        chatId: String,
        isChannel: Boolean,
        myNodeId: String,
        sid: String = "",
        n: Int = -1
    ): ByteArray {
        val contextTag = if (isChannel) {
            "CH:${chatId.trim()}"
        } else {
            val pair = listOf(myNodeId.trim(), chatId.trim()).sorted()
            // sid/n مرتبطان بالتشفير: أي تلاعب بهما يُبطل الوسم
            "DM:${pair[0]}:${pair[1]}:$sid:$n"
        }
        return contextTag.toByteArray(Charsets.UTF_8)
    }

    /** مفتاح الإرسال: للقنوات مفتاح القناة، وللمحادثات المباشرة مفتاح رسالة من جلسة سرية مستقبلية. */
    private suspend fun resolveSendKey(chatId: String, isChannel: Boolean): SendKey? {
        if (isChannel) {
            return SendKey("", -1, cryptoManager.deriveChannelKey(chatId, channelPassphrases[chatId] ?: ""))
        }
        val key = directSessions.nextSendKey(chatId)
        if (key != null && directSessions.needsRotation(chatId)) ensureDirectSession(chatId)
        return key
    }

    /** يبدأ مصافحة جلسة جديدة مع الطرف (إن عُرف مفتاحه) ويطلب الإعلان عن الهوية. */
    private fun ensureDirectSession(peerId: String) {
        bleEngine.requestKeyHandshake()
        scope.launch {
            val x = resolvePeerXKey(peerId)
            if (x.length == 64) startHandshake(peerId, x)
        }
    }

    private fun startHandshake(peerId: String, peerXHex: String) {
        val payload = directSessions.beginHandshake(peerId, peerXHex) ?: return
        val packet = signedPacket(
            type = MeshPacketType.SESSION_INIT,
            targetId = peerId,
            isChannel = false,
            payload = payload,
            timestamp = System.currentTimeMillis()
        )
        bleEngine.broadcastPacket(packet)
    }

    /** عُرف مفتاح طرف: إن كانت لدينا رسائل معلّقة له ولا جلسة، ابدأ المصافحة. */
    private fun onPeerKeyKnown(peerId: String, x25519KeyHex: String) {
        if (directSessions.hasSession(peerId)) {
            flushPendingDirectMessages(peerId)
            return
        }
        if (!pendingDirectPayloads[peerId].isNullOrEmpty()) startHandshake(peerId, x25519KeyHex)
    }

    private suspend fun sendEncryptedTextPacket(
        chatId: String,
        isChannel: Boolean,
        msgId: String,
        plaintext: String,
        replyPreview: String?,
        send: SendKey,
        timestamp: Long
    ) {
        val aad = buildChatAad(chatId, isChannel, _identity.value.nodeId, send.sid, send.n)
        val encryptedText = runCatching { cryptoManager.encryptTextWithKey(send.key, plaintext, aad) }.getOrNull() ?: return // لا إرسال بدون تشفير
        val payloadObj = JSONObject().apply {
            put("msgId", msgId)
            put("chatId", chatId)
            put("body", encryptedText)
            if (send.n >= 0) {
                put("sid", send.sid)
                put("n", send.n)
            }
            if (replyPreview != null) put("reply", replyPreview)
        }
        val packet = signedPacket(
            type = MeshPacketType.CHAT_TEXT,
            targetId = chatId,
            isChannel = isChannel,
            payload = payloadObj.toString(),
            timestamp = timestamp
        )
        bleEngine.broadcastPacket(packet)
        db.messageDao().updateStatus(
            msgId,
            if (nearbyPeers.value.isNotEmpty()) DeliveryStatus.RELAYED.name else DeliveryStatus.SENT.name
        )
    }

    private suspend fun sendEncryptedMediaPacket(
        chatId: String,
        isChannel: Boolean,
        msgId: String,
        mediaType: String,
        label: String,
        durationSec: Int,
        rawB64: String,
        send: SendKey,
        timestamp: Long
    ) {
        val aad = buildChatAad(chatId, isChannel, _identity.value.nodeId, send.sid, send.n)
        val encMedia = runCatching { cryptoManager.encryptTextWithKey(send.key, rawB64, aad) }.getOrNull() ?: return // لا إرسال بدون تشفير
        if (encMedia.length <= MediaChunkAssembler.CHUNK_SIZE_CHARS) {
            val payloadObj = JSONObject().apply {
                put("msgId", msgId)
                put("chatId", chatId)
                put("mediaType", mediaType)
                put("label", label)
                put("dur", durationSec)
                put("data", encMedia)
                if (send.n >= 0) {
                    put("sid", send.sid)
                    put("n", send.n)
                }
            }
            bleEngine.broadcastPacket(
                signedPacket(
                    type = MeshPacketType.CHAT_MEDIA,
                    targetId = chatId,
                    isChannel = isChannel,
                    payload = payloadObj.toString(),
                    timestamp = timestamp
                )
            )
        } else {
            val chunks = MediaChunkAssembler.splitEncryptedPayload(encMedia)
            val crc32 = MediaChunkAssembler.computeCrc32(encMedia)
            val total = chunks.size
            chunks.forEachIndexed { idx, chunkStr ->
                updateTransferProgress(msgId, (idx + 1).toFloat() / total.toFloat())
                val chunkPayload = JSONObject().apply {
                    put("msgId", msgId)
                    put("chatId", chatId)
                    put("mediaType", mediaType)
                    put("label", label)
                    put("dur", durationSec)
                    put("idx", idx)
                    put("total", total)
                    put("crc", crc32)
                    put("chunk", chunkStr)
                    if (send.n >= 0) {
                        put("sid", send.sid)
                        put("n", send.n)
                    }
                }
                bleEngine.broadcastPacket(
                    signedPacket(
                        type = MeshPacketType.MEDIA_CHUNK,
                        targetId = chatId,
                        isChannel = isChannel,
                        payload = chunkPayload.toString(),
                        timestamp = timestamp
                    )
                )
                if (idx < total - 1) delay(35L)
            }
            clearTransferProgress(msgId)
        }
        db.messageDao().updateStatus(
            msgId,
            if (nearbyPeers.value.isNotEmpty()) DeliveryStatus.RELAYED.name else DeliveryStatus.SENT.name
        )
    }

    private fun updateTransferProgress(transferId: String, progress: Float) {
        val next = _mediaTransferProgress.value.toMutableMap()
        next[transferId] = progress.coerceIn(0f, 1f)
        _mediaTransferProgress.value = next
    }

    private fun clearTransferProgress(transferId: String) {
        if (_mediaTransferProgress.value.containsKey(transferId)) {
            val next = _mediaTransferProgress.value.toMutableMap()
            next.remove(transferId)
            _mediaTransferProgress.value = next
        }
    }

    private fun queuePendingDirectItem(peerId: String, item: PendingDirectItem) {
        val list = pendingDirectPayloads.getOrPut(peerId) { Collections.synchronizedList(mutableListOf()) }
        list.add(item)
    }

    private fun flushPendingDirectMessages(peerId: String) {
        if (!directSessions.hasSession(peerId)) return
        val pending = pendingDirectPayloads.remove(peerId) ?: return
        scope.launch {
            pending.toList().forEach { item ->
                val send = directSessions.nextSendKey(peerId)
                if (send == null) {
                    queuePendingDirectItem(peerId, item)
                    return@forEach
                }
                if (item.packetType == MeshPacketType.CHAT_TEXT) {
                    sendEncryptedTextPacket(
                        chatId = peerId,
                        isChannel = false,
                        msgId = item.msgId,
                        plaintext = item.plaintext,
                        replyPreview = item.replyPreview,
                        send = send,
                        timestamp = item.timestamp
                    )
                } else if (item.packetType == MeshPacketType.CHAT_MEDIA && item.mediaType != null) {
                    sendEncryptedMediaPacket(
                        chatId = peerId,
                        isChannel = false,
                        msgId = item.msgId,
                        mediaType = item.mediaType,
                        label = item.mediaLabel.orEmpty(),
                        durationSec = item.voiceDurationSec,
                        rawB64 = item.plaintext,
                        send = send,
                        timestamp = item.timestamp
                    )
                }
            }
        }
    }

    private fun handleSlashCommand(chatId: String, commandText: String): Boolean {
        val parts = commandText.split(" ", limit = 2)
        return when (parts[0].lowercase()) {
            "/nick" -> {
                val name = parts.getOrNull(1)?.trim()
                if (!name.isNullOrBlank()) {
                    updateNickname(name)
                    insertSystemNote(chatId, "تم تغيير اسمك إلى: $name")
                }
                true
            }
            "/clear" -> {
                clearChatHistory(chatId)
                true
            }
            "/help" -> {
                insertSystemNote(
                    chatId,
                    "أوامر Bitchat المدعومة: /nick <اسم> لتغيير الاسم • /clear لمسح المحادثة"
                )
                true
            }
            else -> false
        }
    }

    private fun insertSystemNote(chatId: String, text: String) {
        scope.launch {
            db.messageDao().upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    senderId = "SYSTEM",
                    senderName = "النظام",
                    isFromMe = false,
                    type = MessageType.SYSTEM.name,
                    text = text,
                    mediaPath = null,
                    voiceDurationSec = 0,
                    replyPreview = null,
                    reactionsJson = "{}",
                    myReaction = null,
                    timestamp = System.currentTimeMillis(),
                    status = DeliveryStatus.DELIVERED.name,
                    isEncrypted = false,
                    hopCount = 0
                )
            )
        }
    }

    private suspend fun handleIncomingPacket(packet: MeshPacket) {
        val me = _identity.value
        if (packet.senderId == me.nodeId) return
        if (contactsManager.isBlocked(packet.senderId)) return

        when (packet.type) {
            MeshPacketType.FEED_POST -> {
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val postId = obj.optString("postId").take(64)
                if (postId.isBlank()) return
                val existing = db.feedPostDao().getById(postId)
                if (existing != null) return
                val imgB64 = obj.optString("imgB64")
                val savedImg = if (imgB64.isNotBlank()) mediaHelper.saveBase64ToImageFile(imgB64) else null
                db.feedPostDao().upsert(
                    FeedPostEntity(
                        id = postId,
                        authorId = packet.senderId,
                        authorName = packet.senderName.take(32),
                        text = obj.optString("text").take(2000),
                        imagePath = savedImg?.first,
                        imageSizeKb = savedImg?.second ?: obj.optInt("kb", 0).coerceIn(0, 512),
                        category = obj.optString("cat", PostCategory.GENERAL.name),
                        ttlHours = obj.optInt("ttl", 24).coerceIn(1, 72),
                        createdAt = packet.timestamp,
                        likesCount = 0,
                        isLikedByMe = false,
                        hopCount = packet.hopCount + 1
                    )
                )
            }

            MeshPacketType.FEED_COMMENT -> {
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val commentId = obj.optString("commentId").take(64)
                val postId = obj.optString("postId").take(64)
                val text = obj.optString("text").take(1000)
                if (commentId.isBlank() || postId.isBlank() || text.isBlank()) return
                db.postCommentDao().upsert(
                    PostCommentEntity(
                        id = commentId,
                        postId = postId,
                        authorId = packet.senderId,
                        authorName = packet.senderName.take(32),
                        text = text,
                        createdAt = packet.timestamp
                    )
                )
            }

            MeshPacketType.CHAT_TEXT -> {
                if (!packet.isChannel && packet.targetId != me.nodeId) return
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val msgId = obj.optString("msgId").take(64)
                if (msgId.isBlank()) return
                val chatId = if (packet.isChannel) packet.targetId else packet.senderId
                val sid = obj.optString("sid")
                val n = obj.optInt("n", -1)
                val keyBytes = if (packet.isChannel) {
                    cryptoManager.deriveChannelKey(chatId, channelPassphrases[chatId] ?: "")
                } else {
                    directSessions.recvKey(packet.senderId, sid, n)
                }
                val aad = buildChatAad(chatId, packet.isChannel, me.nodeId, sid, n)
                val decryptedOrNull = keyBytes?.let {
                    cryptoManager.decryptTextWithKey(it, obj.optString("body"), aad)
                }?.take(4000)
                if (decryptedOrNull == null && !packet.isChannel) {
                    // لا جلسة لدينا (أُعيد تشغيل التطبيق/ضاعت الحالة): ابدأ مصافحة جديدة ليتمكن الطرف من إعادة الإرسال
                    trustedStaticKeyOf(packet)?.let { startHandshake(packet.senderId, it) }
                }
                val decrypted = decryptedOrNull
                    ?: "🔒 رسالة مشفرة (يتعذر فك التشفير بدون المفتاح/كلمة المرور)"
                val reply = obj.optString("reply").take(160).takeIf { it.isNotBlank() }

                db.messageDao().upsert(
                    MessageEntity(
                        id = msgId,
                        chatId = chatId,
                        senderId = packet.senderId,
                        senderName = packet.senderName.take(32),
                        isFromMe = false,
                        type = MessageType.TEXT.name,
                        text = decrypted,
                        mediaPath = null,
                        voiceDurationSec = 0,
                        replyPreview = reply,
                        reactionsJson = "{}",
                        myReaction = null,
                        timestamp = packet.timestamp,
                        status = DeliveryStatus.DELIVERED.name,
                        isEncrypted = true,
                        hopCount = packet.hopCount + 1
                    )
                )
                incrementConversationUnread(
                    chatId = chatId,
                    title = if (packet.isChannel) chatId else packet.senderName.take(32),
                    isChannel = packet.isChannel,
                    preview = decrypted,
                    timestamp = packet.timestamp,
                    peerX25519Key = packet.senderX25519KeyHex
                )
                notificationHelper.notifyIncomingMessageIfBackground(
                    isAppInForeground = isAppInForeground,
                    chatId = chatId,
                    isChannel = packet.isChannel,
                    senderName = packet.senderName.take(32),
                    messagePreview = decrypted,
                    language = _language.value,
                    hideSensitiveContent = _isBiometricLockEnabled.value
                )
                if (packet.isChannel) {
                    recordChannelPeerMembership(
                        channelId = chatId,
                        memberNodeId = packet.senderId,
                        memberNickname = packet.senderName.take(32),
                        seenAt = packet.timestamp
                    )
                } else if (decryptedOrNull != null) {
                    // FIX: لا نُرسل إيصال تسليم لرسالة تعذّر فك تشفيرها (كان المرسل يرى «تم التسليم» خطأً)
                    sendDeliveryAck(packet.senderId, msgId)
                }
            }

            MeshPacketType.CHAT_MEDIA -> {
                if (!packet.isChannel && packet.targetId != me.nodeId) return
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val msgId = obj.optString("msgId").take(64)
                if (msgId.isBlank()) return
                val chatId = if (packet.isChannel) packet.targetId else packet.senderId
                val mediaType = obj.optString("mediaType", "IMAGE")
                val encData = obj.optString("data")
                val labelHint = obj.optString("label", "صورة واردة عبر Mesh").take(80)
                val dur = obj.optInt("dur", 1).coerceIn(1, 300)
                handleEncryptedMediaPayload(
                    packet = packet,
                    msgId = msgId,
                    chatId = chatId,
                    mediaType = mediaType,
                    labelHint = labelHint,
                    dur = dur,
                    encData = encData,
                    sid = obj.optString("sid"),
                    n = obj.optInt("n", -1)
                )
            }

            MeshPacketType.MEDIA_CHUNK -> {
                if (!packet.isChannel && packet.targetId != me.nodeId) return
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val msgId = obj.optString("msgId").take(64)
                if (msgId.isBlank()) return
                val chatId = if (packet.isChannel) packet.targetId else packet.senderId
                val idx = obj.optInt("idx", -1)
                val total = obj.optInt("total", 0)
                val expectedCrc = obj.optLong("crc", -1L)
                val chunkStr = obj.optString("chunk")
                if (expectedCrc < 0L) return

                when (val res = mediaChunkAssembler.addChunk(msgId, idx, total, expectedCrc, chunkStr)) {
                    is MediaChunkAssembler.AssemblyResult.InProgress -> {
                        updateTransferProgress(msgId, res.progress)
                    }
                    is MediaChunkAssembler.AssemblyResult.Complete -> {
                        clearTransferProgress(msgId)
                        handleEncryptedMediaPayload(
                            packet = packet,
                            msgId = msgId,
                            chatId = chatId,
                            mediaType = obj.optString("mediaType", "IMAGE"),
                            labelHint = obj.optString("label", "وسائط واردة عبر Mesh").take(80),
                            dur = obj.optInt("dur", 1).coerceIn(1, 300),
                            encData = res.fullData,
                            sid = obj.optString("sid"),
                            n = obj.optInt("n", -1)
                        )
                    }
                    MediaChunkAssembler.AssemblyResult.Corrupted,
                    MediaChunkAssembler.AssemblyResult.Invalid -> {
                        clearTransferProgress(msgId)
                    }
                }
            }

            MeshPacketType.DELIVERY_ACK -> {
                if (packet.targetId != me.nodeId) return
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val msgId = obj.optString("msgId")
                if (msgId.isNotBlank()) {
                    db.messageDao().updateStatus(msgId, DeliveryStatus.DELIVERED.name)
                }
            }

            MeshPacketType.REACTION -> {
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val kind = obj.optString("kind")
                when (kind) {
                    "FEED_LIKE" -> {
                        val postId = obj.optString("postId")
                        val delta = obj.optInt("delta", 1).coerceIn(-1, 1)
                        val post = db.feedPostDao().getById(postId) ?: return
                        val nextCount = (post.likesCount + delta).coerceAtLeast(0)
                        db.feedPostDao().upsert(post.copy(likesCount = nextCount))
                    }
                    "FEED_REACT" -> {
                        val postId = obj.optString("postId")
                        val emoji = obj.optString("emoji").take(8)
                        val prev = obj.optString("prev").take(8)
                        val post = db.feedPostDao().getById(postId) ?: return
                        val map = decodeReactions(post.reactionsJson).toMutableMap()
                        if (prev.isNotBlank()) {
                            val c = (map[prev] ?: 1) - 1
                            if (c <= 0) map.remove(prev) else map[prev] = c
                        }
                        if (emoji.isNotBlank()) {
                            map[emoji] = (map[emoji] ?: 0) + 1
                        }
                        db.feedPostDao().upsert(post.copy(reactionsJson = encodeReactions(map)))
                    }
                    else -> {
                        val msgId = obj.optString("msgId")
                        val emoji = obj.optString("emoji").take(8)
                        val existing = db.messageDao().getById(msgId) ?: return
                        if (emoji.isNotBlank()) {
                            val map = decodeReactions(existing.reactionsJson).toMutableMap()
                            map[emoji] = (map[emoji] ?: 0) + 1
                            db.messageDao().upsert(existing.copy(reactionsJson = encodeReactions(map)))
                        }
                    }
                }
            }

            MeshPacketType.BLOCK_BROADCAST -> {
                val obj = runCatching { JSONObject(packet.payload) }.getOrNull() ?: return
                val blockedId = obj.optString("blockedId").trim().uppercase().take(32)
                if (blockedId.isBlank() || blockedId.equals(me.nodeId, ignoreCase = true)) return
                val blockedName = obj.optString("blockedName", blockedId).trim().take(32).ifEmpty { blockedId }
                val reason = obj.optString("reason", "بلاغ حظر عبر Mesh").trim().take(120)
                val isTrustedReporter = contactsManager.isVerified(packet.senderId)

                val report = BlockReportEntity(
                    reportId = "${packet.senderId}|$blockedId",
                    blockedPeerId = blockedId,
                    blockedNickname = blockedName,
                    reporterId = packet.senderId,
                    reporterNickname = packet.senderName.take(32),
                    reporterTrusted = isTrustedReporter,
                    reason = reason,
                    isDismissed = isTrustedReporter && _autoApplyTrustedBlocks.value,
                    reportedAt = packet.timestamp
                )
                db.blockReportDao().upsert(report)

                if (isTrustedReporter && _autoApplyTrustedBlocks.value) {
                    contactsManager.blockPeer(blockedId, blockedName)
                    bleEngine.refreshPeersFromContacts()
                }
            }

            MeshPacketType.SESSION_INIT -> {
                if (packet.isChannel || packet.targetId != me.nodeId) return
                if (System.currentTimeMillis() - packet.timestamp > HANDSHAKE_MAX_AGE_MS) return
                val peerX = trustedStaticKeyOf(packet) ?: return
                val resp = directSessions.acceptInit(packet.senderId, peerX, packet.payload) ?: return
                bleEngine.broadcastPacket(
                    signedPacket(
                        type = MeshPacketType.SESSION_RESP,
                        targetId = packet.senderId,
                        isChannel = false,
                        payload = resp,
                        timestamp = System.currentTimeMillis()
                    )
                )
                flushPendingDirectMessages(packet.senderId)
            }

            MeshPacketType.SESSION_RESP -> {
                if (packet.isChannel || packet.targetId != me.nodeId) return
                if (System.currentTimeMillis() - packet.timestamp > HANDSHAKE_MAX_AGE_MS) return
                val peerX = trustedStaticKeyOf(packet) ?: return
                if (directSessions.completeHandshake(packet.senderId, peerX, packet.payload)) {
                    flushPendingDirectMessages(packet.senderId)
                }
            }

            MeshPacketType.PEER_HELLO,
            MeshPacketType.NODE_ANNOUNCE -> Unit
        }
    }

    private suspend fun handleEncryptedMediaPayload(
        packet: MeshPacket,
        msgId: String,
        chatId: String,
        mediaType: String,
        labelHint: String,
        dur: Int,
        encData: String,
        sid: String,
        n: Int
    ) {
        val me = _identity.value
        val keyBytes = if (packet.isChannel) {
            cryptoManager.deriveChannelKey(chatId, channelPassphrases[chatId] ?: "")
        } else {
            directSessions.recvKey(packet.senderId, sid, n)
        }
        val aad = buildChatAad(chatId, packet.isChannel, me.nodeId, sid, n)
        val decB64 = keyBytes?.let { cryptoManager.decryptTextWithKey(it, encData, aad) }
        if (decB64 == null) {
            if (!packet.isChannel) trustedStaticKeyOf(packet)?.let { startHandshake(packet.senderId, it) }
            return
        }

        if (mediaType == "VOICE") {
            val savedPath = mediaHelper.saveBase64ToVoiceFile(decB64) ?: return
            val label = "رسالة صوتية (${dur}ث)"
            db.messageDao().upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderId = packet.senderId,
                    senderName = packet.senderName.take(32),
                    isFromMe = false,
                    type = MessageType.VOICE.name,
                    text = label,
                    mediaPath = savedPath,
                    voiceDurationSec = dur,
                    replyPreview = null,
                    reactionsJson = "{}",
                    myReaction = null,
                    timestamp = packet.timestamp,
                    status = DeliveryStatus.DELIVERED.name,
                    isEncrypted = true,
                    hopCount = packet.hopCount + 1
                )
            )
            incrementConversationUnread(
                chatId = chatId,
                title = if (packet.isChannel) chatId else packet.senderName.take(32),
                isChannel = packet.isChannel,
                preview = "🎤 $label",
                timestamp = packet.timestamp,
                peerX25519Key = packet.senderX25519KeyHex
            )
            notificationHelper.notifyIncomingMessageIfBackground(
                isAppInForeground = isAppInForeground,
                chatId = chatId,
                isChannel = packet.isChannel,
                senderName = packet.senderName.take(32),
                messagePreview = "🎤 $label",
                language = _language.value,
                hideSensitiveContent = _isBiometricLockEnabled.value
            )
        } else {
            val saved = mediaHelper.saveBase64ToImageFile(decB64) ?: return
            db.messageDao().upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderId = packet.senderId,
                    senderName = packet.senderName.take(32),
                    isFromMe = false,
                    type = MessageType.IMAGE.name,
                    text = labelHint,
                    mediaPath = saved.first,
                    voiceDurationSec = 0,
                    replyPreview = null,
                    reactionsJson = "{}",
                    myReaction = null,
                    timestamp = packet.timestamp,
                    status = DeliveryStatus.DELIVERED.name,
                    isEncrypted = true,
                    hopCount = packet.hopCount + 1
                )
            )
            incrementConversationUnread(
                chatId = chatId,
                title = if (packet.isChannel) chatId else packet.senderName.take(32),
                isChannel = packet.isChannel,
                preview = "📷 $labelHint",
                timestamp = packet.timestamp,
                peerX25519Key = packet.senderX25519KeyHex
            )
            notificationHelper.notifyIncomingMessageIfBackground(
                isAppInForeground = isAppInForeground,
                chatId = chatId,
                isChannel = packet.isChannel,
                senderName = packet.senderName.take(32),
                messagePreview = "📷 $labelHint",
                language = _language.value,
                hideSensitiveContent = _isBiometricLockEnabled.value
            )
        }
        if (packet.isChannel) {
            recordChannelPeerMembership(
                channelId = chatId,
                memberNodeId = packet.senderId,
                memberNickname = packet.senderName.take(32),
                seenAt = packet.timestamp
            )
        } else {
            sendDeliveryAck(packet.senderId, msgId)
        }
    }

    private suspend fun recordChannelPeerMembership(
        channelId: String,
        memberNodeId: String,
        memberNickname: String,
        seenAt: Long
    ) {
        val membershipId = "$channelId|$memberNodeId"
        val existing = db.channelMembershipDao().getById(membershipId)
        val conv = db.conversationDao().getById(channelId)
        db.channelMembershipDao().upsert(
            ChannelMembershipEntity(
                membershipId = membershipId,
                channelId = channelId,
                channelName = conv?.title ?: channelId,
                memberNodeId = memberNodeId,
                memberNickname = memberNickname,
                role = existing?.role ?: "MEMBER",
                isProtected = conv?.channelPassphraseHash?.isNotEmpty() == true,
                passphraseHash = conv?.channelPassphraseHash ?: "",
                isActive = true,
                joinedAt = existing?.joinedAt ?: seenAt,
                lastSeenAt = seenAt
            )
        )
    }

    private fun sendDeliveryAck(recipientId: String, msgId: String) {
        val payload = JSONObject().apply { put("msgId", msgId) }.toString()
        val ack = signedPacket(
            type = MeshPacketType.DELIVERY_ACK,
            targetId = recipientId,
            isChannel = false,
            payload = payload,
            timestamp = System.currentTimeMillis()
        )
        bleEngine.broadcastPacket(ack, cacheForStoreAndForward = true)
    }

    private suspend fun resolvePeerXKey(peerId: String, inlineXKey: String = ""): String {
        val xKey = inlineXKey.ifBlank {
            bleEngine.getPeerX25519Key(peerId)
                ?: contactsManager.getContact(peerId)?.x25519PublicKeyHex?.takeIf { it.length == 64 }
                ?: db.conversationDao().getById(peerId)?.peerX25519KeyHex
                ?: ""
        }
        return if (xKey.length == 64) xKey else ""
    }

    /** مفتاح X25519 الثابت الموقَّع في الحزمة، مع رفضه إن خالف المفتاح المثبّت لجهة اتصال موثّقة. */
    private fun trustedStaticKeyOf(packet: MeshPacket): String? {
        val x = packet.senderX25519KeyHex
        if (x.length != 64) return null
        val pinned = contactsManager.getContact(packet.senderId)?.x25519PublicKeyHex
        if (!pinned.isNullOrBlank() && pinned.length == 64 && !pinned.equals(x, ignoreCase = true)) return null
        return x
    }

    private suspend fun updateConversationPreview(
        chatId: String,
        isChannel: Boolean,
        preview: String,
        timestamp: Long
    ) {
        val current = db.conversationDao().getById(chatId)
        db.conversationDao().upsert(
            ConversationEntity(
                id = chatId,
                title = current?.title ?: chatId,
                isChannel = isChannel,
                peerFingerprint = current?.peerFingerprint ?: if (isChannel) "BROADCAST" else chatId,
                peerX25519KeyHex = current?.peerX25519KeyHex ?: bleEngine.getPeerX25519Key(chatId) ?: "",
                channelPassphraseHash = current?.channelPassphraseHash ?: "",
                isVerified = current?.isVerified ?: contactsManager.isVerified(chatId),
                lastMessage = preview,
                lastMessageTime = timestamp,
                unreadCount = 0
            )
        )
    }

    private suspend fun incrementConversationUnread(
        chatId: String,
        title: String,
        isChannel: Boolean,
        preview: String,
        timestamp: Long,
        peerX25519Key: String
    ) {
        val current = db.conversationDao().getById(chatId)
        db.conversationDao().upsert(
            ConversationEntity(
                id = chatId,
                title = current?.title ?: title,
                isChannel = isChannel,
                peerFingerprint = current?.peerFingerprint ?: if (isChannel) "BROADCAST" else chatId,
                peerX25519KeyHex = peerX25519Key.ifBlank { current?.peerX25519KeyHex ?: "" },
                channelPassphraseHash = current?.channelPassphraseHash ?: "",
                isVerified = current?.isVerified ?: contactsManager.isVerified(chatId),
                lastMessage = preview,
                lastMessageTime = timestamp,
                unreadCount = (current?.unreadCount ?: 0) + 1
            )
        )
    }

    private fun signedPacket(
        type: MeshPacketType,
        targetId: String,
        isChannel: Boolean,
        payload: String,
        timestamp: Long
    ): MeshPacket {
        val me = _identity.value
        val base = MeshPacket(
            type = type,
            senderId = me.nodeId,
            senderName = if (_isStealthMode.value) "مستخدم مخفي" else me.nickname,
            senderPubKeyHex = me.publicKeyHex,
            senderX25519KeyHex = me.x25519PublicKeyHex,
            targetId = targetId,
            isChannel = isChannel,
            ttl = _maxHopTtl.value,
            payload = payload,
            timestamp = timestamp
        )
        val sig = cryptoManager.signPayload(MeshPacketCodec.signingBytes(base))
        return base.copy(signature = sig)
    }

    private fun decodeReactions(json: String): Map<String, Int> {
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { key ->
                val v = obj.optInt(key, 0)
                if (v > 0) map[key] = v
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encodeReactions(map: Map<String, Int>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private data class PendingDirectItem(
        val msgId: String,
        val packetType: MeshPacketType,
        val plaintext: String,
        val replyPreview: String?,
        val mediaType: String?,
        val mediaLabel: String?,
        val voiceDurationSec: Int,
        val timestamp: Long
    )

    companion object {
        private const val HANDSHAKE_MAX_AGE_MS = 15 * 60_000L

        @Volatile
        private var INSTANCE: MeshRepository? = null

        fun getInstance(context: Context): MeshRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
