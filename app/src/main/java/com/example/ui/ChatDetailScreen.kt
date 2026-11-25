package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.MediaHelper
import com.example.model.ChatConversation
import com.example.model.ChatMessage
import com.example.model.DeliveryStatus
import com.example.model.MessageType
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.MeshAzure
import com.example.ui.theme.MeshViolet
import com.example.ui.theme.PanicCrimson
import com.example.ui.theme.VerifiedEmerald
import java.io.File

private val QUICK_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    conversation: ChatConversation,
    messages: List<ChatMessage>,
    mediaHelper: MediaHelper,
    transferProgress: Map<String, Float> = emptyMap(),
    onBack: () -> Unit,
    onSendText: (text: String, replyPreview: String?) -> Unit,
    onSendImage: (Uri) -> Unit,
    onSendVoice: (File, Int) -> Unit,
    onReactToMessage: (msgId: String, emoji: String) -> Unit,
    onBlockPeerOnly: ((peerId: String, nickname: String) -> Unit)? = null,
    onBlockAndBroadcastPeer: ((peerId: String, nickname: String, reason: String) -> Unit)? = null
) {
    BackHandler {
        mediaHelper.stopAudio()
        onBack()
    }

    val context = LocalContext.current
    val listState = rememberLazyListState()
    var messageInput by remember { mutableStateOf("") }
    var activeReplyPreview by remember { mutableStateOf<String?>(null) }
    var selectedMessageForAction by remember { mutableStateOf<ChatMessage?>(null) }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var showEmojiTray by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }

    val playingMessageId by mediaHelper.playingMessageId.collectAsState()
    val playbackProgress by mediaHelper.playbackProgress.collectAsState()
    val isRecording by mediaHelper.isRecording.collectAsState()
    val recordingSeconds by mediaHelper.recordingSeconds.collectAsState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendImage(uri)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            mediaHelper.startVoiceRecording()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            mediaHelper.stopAudio()
                            onBack()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    LocChatAvatar(
                        label = conversation.title,
                        size = 38.dp,
                        isChannel = conversation.isChannel
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (conversation.isVerified) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = "موثق",
                                    tint = VerifiedEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = if (conversation.isChannel) {
                                "قناة محلية مفتوحة عبر Mesh"
                            } else {
                                "محادثة مشفرة • ${conversation.peerFingerprint}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    }
                },
                actions = {
                    if (!conversation.isChannel && (onBlockPeerOnly != null || onBlockAndBroadcastPeer != null)) {
                        IconButton(
                            onClick = { showBlockDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_block_peer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = "حظر وإذاعة الحظر",
                                tint = PanicCrimson
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                AnimatedVisibility(visible = activeReplyPreview != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activeReplyPreview.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { activeReplyPreview = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "إلغاء الرد", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (isRecording) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { mediaHelper.stopVoiceRecording(save = false) },
                            modifier = Modifier.testTag("cancel_voice_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "إلغاء التسجيل", tint = PanicCrimson)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(PanicCrimson)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "جارٍ تسجيل رسالة صوتية... ${recordingSeconds}ث",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                val res = mediaHelper.stopVoiceRecording(save = true)
                                if (res != null) {
                                    onSendVoice(res.first, res.second)
                                }
                            },
                            modifier = Modifier.testTag("send_voice_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إرسال")
                        }
                    }
                } else {
                    AnimatedVisibility(visible = showEmojiTray) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("😀", "😂", "😍", "👍", "❤️", "🔥", "🙏", "🎉", "📍", "⚠️", "🔒", "👋").forEach { emoji ->
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { messageInput += emoji }
                                ) {
                                    Text(
                                        text = emoji,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showEmojiTray = !showEmojiTray },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_emoji_tray_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = "إدراج إيموجي",
                                tint = if (showEmojiTray) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }

                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_pick_image_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = "إرسال صورة")
                        }

                        OutlinedTextField(
                            value = messageInput,
                            onValueChange = { messageInput = it },
                            placeholder = { Text("اكتب رسالة أو /help...") },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input")
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        if (messageInput.isNotBlank()) {
                            FilledIconButton(
                                onClick = {
                                    onSendText(messageInput, activeReplyPreview)
                                    messageInput = ""
                                    activeReplyPreview = null
                                    showEmojiTray = false
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_send_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
                            }
                        } else {
                            FilledTonalIconButton(
                                onClick = {
                                    val hasAudio = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasAudio) {
                                        mediaHelper.startVoiceRecording()
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_record_voice_button")
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "تسجيل رسالة صوتية")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                EmptyStateBox(
                    icon = Icons.Default.Lock,
                    title = if (conversation.isChannel) "قناة محلية فارغة" else "محادثة مشفرة جديدة",
                    subtitle = "أرسل رسالة نصية، صورة مضغوطة، أو مقطعاً صوتياً عبر شبكة البلوتوث المحلية."
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        uploadProgress = transferProgress[msg.id],
                        isPlaying = playingMessageId == msg.id,
                        playbackProgress = if (playingMessageId == msg.id) playbackProgress else 0f,
                        onToggleVoice = { mediaHelper.togglePlayVoice(msg.id, msg.mediaPath) },
                        onImageClick = { msg.mediaPath?.let { fullscreenImagePath = it } },
                        onSelectMessage = { selectedMessageForAction = msg },
                        onToggleReaction = { emoji -> onReactToMessage(msg.id, emoji) }
                    )
                }
            }
        }
    }

    selectedMessageForAction?.let { targetMsg ->
        AlertDialog(
            onDismissRequest = { selectedMessageForAction = null },
            title = { Text("خيارات الرسالة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QUICK_EMOJIS.forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        onReactToMessage(targetMsg.id, emoji)
                                        selectedMessageForAction = null
                                    }
                                    .padding(6.dp)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            activeReplyPreview = "${targetMsg.senderName}: ${targetMsg.text.take(40)}"
                            selectedMessageForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("رد على هذه الرسالة")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForAction = null }) { Text("إغلاق") }
            }
        )
    }

    fullscreenImagePath?.let { imgPath ->
        AlertDialog(
            onDismissRequest = { fullscreenImagePath = null },
            confirmButton = {
                TextButton(onClick = { fullscreenImagePath = null }) { Text("إغلاق") }
            },
            text = {
                AsyncImage(
                    model = File(imgPath),
                    contentDescription = "معاينة الصورة",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            }
        )
    }

    if (showBlockDialog) {
        BlockAndBroadcastDialog(
            targetName = conversation.title,
            targetId = conversation.id,
            onDismiss = { showBlockDialog = false },
            onBlockLocalOnly = {
                showBlockDialog = false
                mediaHelper.stopAudio()
                onBlockPeerOnly?.invoke(conversation.id, conversation.title)
                onBack()
            },
            onBlockAndBroadcast = { reason ->
                showBlockDialog = false
                mediaHelper.stopAudio()
                onBlockAndBroadcastPeer?.invoke(conversation.id, conversation.title, reason)
                onBack()
            }
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    uploadProgress: Float?,
    isPlaying: Boolean,
    playbackProgress: Float,
    onToggleVoice: () -> Unit,
    onImageClick: () -> Unit,
    onSelectMessage: () -> Unit,
    onToggleReaction: (String) -> Unit
) {
    if (message.type == MessageType.SYSTEM) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
    val isDark = isSystemInDarkTheme()
    val bubbleShape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (message.isFromMe) 22.dp else 6.dp,
        bottomEnd = if (message.isFromMe) 6.dp else 22.dp
    )
    // Outgoing: brand gradient with white text. Incoming: flat grey bubble.
    val outgoingBrush = Brush.linearGradient(
        colors = listOf(MeshAzure, MeshViolet)
    )
    val incomingBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    )
    val contentColor = if (message.isFromMe) Color.White else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = Color.Transparent,
            border = null,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(bubbleShape)
                .background(if (message.isFromMe) outgoingBrush else incomingBrush)
                .clickable { onSelectMessage() }
                .testTag("chat_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isFromMe) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                if (!message.replyPreview.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = message.replyPreview,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                when (message.type) {
                    MessageType.IMAGE -> {
                        if (!message.mediaPath.isNullOrBlank()) {
                            AsyncImage(
                                model = File(message.mediaPath),
                                contentDescription = message.text,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onImageClick() }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(text = message.text, style = MaterialTheme.typography.bodySmall, color = contentColor)
                    }

                    MessageType.VOICE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = onToggleVoice,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                    contentDescription = if (isPlaying) "إيقاف" else "تشغيل",
                                    tint = if (message.isFromMe) Color.White else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                LinearProgressIndicator(
                                    progress = { if (isPlaying) playbackProgress else 0f },
                                    color = if (message.isFromMe) Color.White else MaterialTheme.colorScheme.primary,
                                    trackColor = contentColor.copy(alpha = 0.25f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${message.voiceDurationSec} ثانية",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor
                                )
                            }
                        }
                    }

                    MessageType.TEXT, MessageType.SYSTEM -> {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor
                        )
                    }
                }

                if (uploadProgress != null && uploadProgress in 0.01f..0.99f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "جاري بث الأجزاء عبر Mesh... ${(uploadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEncrypted) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "مشفر",
                            modifier = Modifier.size(11.dp),
                            tint = contentColor.copy(alpha = 0.7f)
                        )
                    }
                    if (message.hopCount > 0) {
                        Text(
                            text = "${message.hopCount} قفزة",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f)
                    )
                    if (message.isFromMe) {
                        val statusText = when (message.status) {
                            DeliveryStatus.SENDING -> "⏳"
                            DeliveryStatus.SENT -> "✓"
                            DeliveryStatus.RELAYED -> "✓✓"
                            DeliveryStatus.DELIVERED -> "✓✓"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.status == DeliveryStatus.DELIVERED) {
                                VerifiedEmerald
                            } else {
                                contentColor.copy(alpha = 0.75f)
                            }
                        )
                    }
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.reactions.forEach { (emoji, count) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (message.myReaction == emoji) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.clickable { onToggleReaction(emoji) }
                    ) {
                        Text(
                            text = "$emoji $count",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
