package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BlockReportEntity
import com.example.data.BlockedPeerEntity
import com.example.data.ChannelMembershipEntity
import com.example.data.ContactsManager
import com.example.data.LocalIdentityEntity
import com.example.data.MediaHelper
import com.example.model.AppLanguage
import com.example.model.ChatConversation
import com.example.model.FeedPost
import com.example.model.MeshEngineStatus
import com.example.model.MeshIdentity
import com.example.model.MeshPowerMode
import com.example.model.NearbyPeer
import com.example.model.PostCategory
import com.example.ui.theme.NearbyCyanTeal
import com.example.ui.theme.PanicCrimson
import com.example.ui.theme.PrivateShieldGold
import com.example.ui.theme.RadarAmber

private enum class HomeTab(
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val tag: String
) {
    FEED(Icons.Filled.DynamicFeed, Icons.Outlined.DynamicFeed, "tab_feed"),
    CHATS(Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "tab_chats"),
    CONTACTS(Icons.Filled.Contacts, Icons.Outlined.Contacts, "tab_contacts"),
    RADAR(Icons.Filled.Radar, Icons.Outlined.Radar, "tab_radar"),
    IDENTITY(Icons.Filled.Settings, Icons.Outlined.Settings, "tab_identity");

    fun label(s: AppStrings): String = when (this) {
        FEED -> s.tabFeed
        CHATS -> s.tabChats
        CONTACTS -> s.tabContacts
        RADAR -> s.tabRadar
        IDENTITY -> s.tabSettings
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocChatHomeScreen(
    identity: MeshIdentity,
    activeLocalIdentity: LocalIdentityEntity?,
    contactsManager: ContactsManager,
    mediaHelper: MediaHelper,
    posts: List<FeedPost>,
    conversations: List<ChatConversation>,
    channelMemberships: List<ChannelMembershipEntity>,
    blockReports: List<BlockReportEntity>,
    messagesCount: Int,
    nearbyPeers: List<NearbyPeer>,
    meshStatus: MeshEngineStatus,
    currentLanguage: AppLanguage,
    isLocalPreviewMode: Boolean,
    isKeystoreProtected: Boolean,
    isBiometricLockEnabled: Boolean,
    isStealthMode: Boolean,
    autoApplyTrustedBlocks: Boolean,
    maxHopTtl: Int,
    verifyNodeBinding: (nodeId: String, edPubHex: String) -> Boolean,
    computeFingerprint: (edPubHex: String, xPubHex: String) -> String,
    onChangeLanguage: (AppLanguage) -> Unit,
    onOpenChat: (ChatConversation) -> Unit,
    onStartChatWithPeer: (peerId: String, nickname: String) -> Unit,
    onCreateChannel: (name: String, passphrase: String) -> Unit,
    onCreatePost: (text: String, imgPath: String?, sizeKb: Int, category: PostCategory, ttlHours: Int) -> Unit,
    onToggleLike: (postId: String) -> Unit,
    onReactToPost: (postId: String, emoji: String) -> Unit,
    onAddComment: (postId: String, commentText: String) -> Unit,
    onReBroadcastPost: (postId: String) -> Unit,
    onUpdateNickname: (newName: String) -> Unit,
    onToggleBiometricLock: (Boolean) -> Unit,
    onToggleStealthMode: (Boolean) -> Unit,
    onToggleAutoApplyTrustedBlocks: (Boolean) -> Unit,
    onChangeMaxHopTtl: (Int) -> Unit,
    onClearRelayCaches: () -> Unit,
    onVerifyFriend: (peerId: String, nickname: String, fingerprint: String, pubKeyHex: String, x25519KeyHex: String) -> Unit,
    onRemoveFriend: (peerId: String) -> Unit,
    onBlockPeer: (peerId: String, nickname: String) -> Unit,
    onBlockAndBroadcastPeer: (peerId: String, nickname: String, reason: String) -> Unit,
    onBroadcastExistingBlock: (BlockedPeerEntity) -> Unit,
    onAcceptBlockReport: (BlockReportEntity) -> Unit,
    onDismissBlockReport: (String) -> Unit,
    onUnblockPeer: (peerId: String) -> Unit,
    onChangePowerMode: (MeshPowerMode) -> Unit,
    onFriendVerified: () -> Unit,
    onPanicWipe: () -> Unit
) {
    val s = LocalAppStrings.current
    var currentTab by remember { mutableStateOf(HomeTab.FEED) }
    var selectedCategory by remember { mutableStateOf(PostCategory.ALL) }
    var showCreatePostDialog by remember { mutableStateOf(false) }
    var showCreateChannelDialog by remember { mutableStateOf(false) }
    var showQuickMyQrDialog by remember { mutableStateOf(false) }
    var showQuickPanicDialog by remember { mutableStateOf(false) }
    var feedBroadcastBanner by remember { mutableStateOf<String?>(null) }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapMs by remember { mutableLongStateOf(0L) }

    val verifiedContacts by contactsManager.verifiedContacts.collectAsState()
    val totalUnread = remember(conversations) { conversations.sumOf { it.unreadCount } }
    val activePeersCount = remember(nearbyPeers) { nearbyPeers.count { !it.isBlocked } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        navigationIcon = {
                            LocChatAvatar(
                                label = identity.nickname,
                                size = 34.dp,
                                modifier = Modifier
                                    .padding(start = 14.dp)
                                    .testTag("identity_header_chip"),
                                onClick = { currentTab = HomeTab.IDENTITY }
                            )
                        },
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LocChatSquircleLogo(
                                    size = 28.dp,
                                    modifier = Modifier.clickable {
                                        val now = System.currentTimeMillis()
                                        logoTapCount = if (now - lastLogoTapMs < 1500L) logoTapCount + 1 else 1
                                        lastLogoTapMs = now
                                        if (logoTapCount >= 3) {
                                            logoTapCount = 0
                                            showQuickPanicDialog = true
                                        }
                                    }
                                )
                                Text(
                                    text = when {
                                        isStealthMode -> "${s.stealthHeaderStatus} ${meshStatus.packetsRelayed}"
                                        isLocalPreviewMode -> s.previewModeBanner
                                        else -> "${s.meshHeaderPrefix} • $activePeersCount • ${meshStatus.packetsRelayed}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        isStealthMode -> PrivateShieldGold
                                        isLocalPreviewMode -> RadarAmber
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { showQuickMyQrDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("topbar_my_qr_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCode2,
                                    contentDescription = s.myQrButtonDesc,
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                    FeedDivider()
                }
            },
            bottomBar = {
                if (!useRail) {
                    Column {
                    FeedDivider()
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp
                    ) {
                        HomeTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            val tabLabel = tab.label(s)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (tab == HomeTab.CHATS && totalUnread > 0) {
                                                Badge { Text("$totalUnread") }
                                            } else if (tab == HomeTab.CONTACTS && verifiedContacts.isNotEmpty()) {
                                                Badge { Text("${verifiedContacts.size}") }
                                            } else if (tab == HomeTab.RADAR && activePeersCount > 0) {
                                                Badge { Text("$activePeersCount") }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tabLabel
                                        )
                                    }
                                },
                                alwaysShowLabel = false,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                    }
                }
            },
            floatingActionButton = {
                when (currentTab) {
                    HomeTab.FEED -> {
                        FloatingActionButton(
                            onClick = { showCreatePostDialog = true },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White,
                            modifier = Modifier.testTag("fab_new_post")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = s.fabNewPost)
                        }
                    }
                    HomeTab.CHATS -> {
                        FloatingActionButton(
                            onClick = { showCreateChannelDialog = true },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White,
                            modifier = Modifier.testTag("fab_new_channel")
                        ) {
                            Icon(Icons.Default.Tag, contentDescription = s.fabNewChannel)
                        }
                    }
                    else -> Unit
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HomeTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            val tabLabel = tab.label(s)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tabLabel
                                    )
                                },
                                label = { Text(tabLabel) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)

                    when (currentTab) {
                        HomeTab.FEED -> FeedTab(
                            posts = posts,
                            activePeersCount = activePeersCount,
                            cachedPacketsCount = meshStatus.cachedPacketsCount,
                            myNodeId = identity.nodeId,
                            myNickname = identity.nickname,
                            nearbyPeers = nearbyPeers,
                            selectedCategory = selectedCategory,
                            broadcastBannerMessage = feedBroadcastBanner,
                            onOpenCreatePost = { showCreatePostDialog = true },
                            onSelectCategory = { selectedCategory = it },
                            onToggleLike = onToggleLike,
                            onReactToPost = onReactToPost,
                            onAddComment = onAddComment,
                            onReBroadcastPost = { postId ->
                                onReBroadcastPost(postId)
                                feedBroadcastBanner = s.feedBroadcastConfirmed(activePeersCount)
                            },
                            onDirectMessageAuthor = onStartChatWithPeer,
                            modifier = contentModifier
                        )
                        HomeTab.CHATS -> ChatsTab(
                            conversations = conversations,
                            onOpenChat = onOpenChat,
                            modifier = contentModifier
                        )
                        HomeTab.CONTACTS -> ContactsTab(
                            identity = identity,
                            contactsManager = contactsManager,
                            nearbyPeers = nearbyPeers,
                            verifyNodeBinding = verifyNodeBinding,
                            computeFingerprint = computeFingerprint,
                            onStartChatWithFriend = onStartChatWithPeer,
                            onVerifyFriend = onVerifyFriend,
                            onRemoveFriend = onRemoveFriend,
                            onBlockPeerOnly = onBlockPeer,
                            onBlockAndBroadcastPeer = onBlockAndBroadcastPeer,
                            onFriendVerified = onFriendVerified,
                            modifier = contentModifier
                        )
                        HomeTab.RADAR -> RadarTab(
                            nearbyPeers = nearbyPeers,
                            meshStatus = meshStatus,
                            isStealthMode = isStealthMode,
                            onToggleStealthMode = onToggleStealthMode,
                            onStartChatWithPeer = onStartChatWithPeer,
                            onVerifyPeer = { peer ->
                                onVerifyFriend(
                                    peer.peerId,
                                    peer.nickname,
                                    peer.fingerprint,
                                    peer.publicKeyHex,
                                    peer.x25519PublicKeyHex
                                )
                                onFriendVerified()
                            },
                            onBlockPeerOnly = { peer ->
                                onBlockPeer(peer.peerId, peer.nickname)
                            },
                            onBlockAndBroadcastPeer = { peer, reason ->
                                onBlockAndBroadcastPeer(peer.peerId, peer.nickname, reason)
                            },
                            modifier = contentModifier
                        )
                        HomeTab.IDENTITY -> SettingsSecurityTab(
                            identity = identity,
                            activeLocalIdentity = activeLocalIdentity,
                            contactsManager = contactsManager,
                            meshStatus = meshStatus,
                            channelMemberships = channelMemberships,
                            blockReports = blockReports,
                            conversationsCount = conversations.size,
                            messagesCount = messagesCount,
                            currentLanguage = currentLanguage,
                            isKeystoreProtected = isKeystoreProtected,
                            isBiometricLockEnabled = isBiometricLockEnabled,
                            isStealthMode = isStealthMode,
                            autoApplyTrustedBlocks = autoApplyTrustedBlocks,
                            maxHopTtl = maxHopTtl,
                            verifyNodeBinding = verifyNodeBinding,
                            computeFingerprint = computeFingerprint,
                            onChangeLanguage = onChangeLanguage,
                            onUpdateNickname = onUpdateNickname,
                            onToggleBiometricLock = onToggleBiometricLock,
                            onToggleStealthMode = onToggleStealthMode,
                            onToggleAutoApplyTrustedBlocks = onToggleAutoApplyTrustedBlocks,
                            onChangeMaxHopTtl = onChangeMaxHopTtl,
                            onChangePowerMode = onChangePowerMode,
                            onClearRelayCaches = onClearRelayCaches,
                            onVerifyFriend = onVerifyFriend,
                            onBlockAndBroadcastById = onBlockAndBroadcastPeer,
                            onBroadcastExistingBlock = onBroadcastExistingBlock,
                            onUnblockPeer = onUnblockPeer,
                            onAcceptBlockReport = onAcceptBlockReport,
                            onDismissBlockReport = onDismissBlockReport,
                            onFriendVerified = onFriendVerified,
                            onPanicWipe = onPanicWipe,
                            modifier = contentModifier
                        )
                    }
                }
            }
        }
    }

    if (showCreatePostDialog) {
        CreatePostDialog(
            mediaHelper = mediaHelper,
            onDismiss = { showCreatePostDialog = false },
            onPublish = { text, imgPath, sizeKb, category, ttlHours ->
                onCreatePost(text, imgPath, sizeKb, category, ttlHours)
                feedBroadcastBanner = s.feedBroadcastConfirmed(activePeersCount)
            }
        )
    }

    if (showQuickMyQrDialog) {
        MyIdentityQrDialog(
            identity = identity,
            onDismiss = { showQuickMyQrDialog = false }
        )
    }

    if (showCreateChannelDialog) {
        CreateChannelDialog(
            onDismiss = { showCreateChannelDialog = false },
            onCreateChannel = onCreateChannel
        )
    }

    if (showQuickPanicDialog) {
        AlertDialog(
            onDismissRequest = { showQuickPanicDialog = false },
            title = { Text(s.panicDialogTitle) },
            text = { Text(s.panicDialogBody) },
            confirmButton = {
                Button(
                    onClick = {
                        showQuickPanicDialog = false
                        onPanicWipe()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson)
                ) { Text(s.panicConfirmBtn) }
            },
            dismissButton = {
                TextButton(onClick = { showQuickPanicDialog = false }) { Text(s.cancelLabel) }
            }
        )
    }
}
