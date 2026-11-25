package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BiometricLockHelper
import com.example.mesh.MeshForegroundService
import com.example.mesh.MeshRepository
import com.example.model.AppLanguage
import com.example.model.ChatConversation
import com.example.ui.ChatDetailScreen
import com.example.ui.LocalAppStrings
import com.example.ui.LocChatFeaturePillRow
import com.example.ui.LocChatHomeScreen
import com.example.ui.LocChatSquircleLogo
import com.example.ui.LocChatTaglineDivider
import com.example.ui.LocChatWordmark
import com.example.ui.locChatRimBorder
import com.example.ui.stringsFor
import com.example.ui.theme.LocChatTheme

class MainActivity : FragmentActivity() {

    private lateinit var repository: MeshRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = MeshRepository.getInstance(applicationContext)

        setContent {
            LocChatTheme {
                LocChatRootScreen(activity = this, repository = repository)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.mediaHelper.stopAudio()
    }
}

@Composable
fun LocChatRootScreen(activity: FragmentActivity, repository: MeshRepository) {
    val appLanguage by repository.language.collectAsStateWithLifecycle()
    val layoutDirection = if (appLanguage.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val localizedStrings = remember(appLanguage) { stringsFor(appLanguage) }

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalAppStrings provides localizedStrings
    ) {
        LocChatRootContent(
            activity = activity,
            repository = repository,
            appLanguage = appLanguage
        )
    }
}

@Composable
private fun LocChatRootContent(
    activity: FragmentActivity,
    repository: MeshRepository,
    appLanguage: AppLanguage
) {
    val context = activity.applicationContext
    val s = LocalAppStrings.current

    val requiredPermissions = remember {
        mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // أندرويد 11 وأقل: المسح عبر BLE يتطلب الموقع
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    // FIX: إذنا الإشعارات اختياري؛ رفضه كان يُبقي المستخدم عالقًا في شاشة الصلاحيات
    val mandatoryPermissions = remember {
        requiredPermissions.filter { it != Manifest.permission.POST_NOTIFICATIONS }
    }

    var hasPermissions by remember {
        mutableStateOf(
            mandatoryPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val hasBluetoothAdapter = remember { hasBluetoothHardware(context) }
    var isBluetoothOn by remember { mutableStateOf(checkBluetoothEnabled(context)) }
    var isLocationEnabled by remember { mutableStateOf(checkLocationEnabledForBle(context)) }
    var localPreviewBypass by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        isBluetoothOn = checkBluetoothEnabled(context)
                    }
                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        isLocationEnabled = checkLocationEnabledForBle(context)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = mandatoryPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        isLocationEnabled = checkLocationEnabledForBle(context)
        isBluetoothOn = checkBluetoothEnabled(context)
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isBluetoothOn = checkBluetoothEnabled(context)
    }

    val hardwareReady = hasPermissions && isBluetoothOn && isLocationEnabled

    if (!hardwareReady && !localPreviewBypass) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LocChatSquircleLogo(size = 112.dp, showAmbientGlow = true)
                Spacer(modifier = Modifier.height(16.dp))
                LocChatWordmark(fontSize = 34.sp)
                Spacer(modifier = Modifier.height(10.dp))
                LocChatTaglineDivider()
                Spacer(modifier = Modifier.height(18.dp))
                LocChatFeaturePillRow()
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    !hasPermissions -> {
                        Text(
                            text = stringResource(R.string.permissions_rationale),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermissions) },
                            modifier = Modifier.testTag("grant_permissions_button")
                        ) {
                            Text(stringResource(R.string.grant_permissions_button))
                        }
                    }
                    !isBluetoothOn -> {
                        Text(
                            text = if (hasBluetoothAdapter) {
                                stringResource(R.string.bluetooth_off_message)
                            } else {
                                "لم يتم اكتشاف محول بلوتوث في هذا الجهاز. يمكنك المتابع�� في وضع المعاينة المحلية."
                            },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        if (hasBluetoothAdapter) {
                            Button(
                                onClick = { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                                modifier = Modifier.testTag("enable_bluetooth_button")
                            ) {
                                Text(stringResource(R.string.enable_bluetooth_button))
                            }
                        }
                    }
                    !isLocationEnabled -> {
                        Text(
                            text = stringResource(R.string.location_off_message),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                            modifier = Modifier.testTag("enable_location_button")
                        ) {
                            Text(stringResource(R.string.enable_location_button))
                        }
                    }
                }

                if (!hasBluetoothAdapter) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { localPreviewBypass = true },
                        modifier = Modifier.testTag("local_preview_bypass_button")
                    ) {
                        Text(s.continueLocalPreview)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = appLanguage == lang,
                            onClick = { repository.setLanguage(lang) },
                            label = { Text(lang.displayName) }
                        )
                    }
                }
            }
        }
        return
    }

    LaunchedEffect(hardwareReady) {
        if (hardwareReady) {
            MeshForegroundService.start(context)
        }
    }

    val identity by repository.identity.collectAsStateWithLifecycle()
    val activeLocalIdentity by repository.activeLocalIdentity.collectAsStateWithLifecycle()
    val isNicknameConfigured by repository.isNicknameConfigured.collectAsStateWithLifecycle()
    val isBiometricLockEnabled by repository.isBiometricLockEnabled.collectAsStateWithLifecycle()
    val isStealthMode by repository.isStealthMode.collectAsStateWithLifecycle()
    val autoApplyTrustedBlocks by repository.autoApplyTrustedBlocks.collectAsStateWithLifecycle()
    val maxHopTtl by repository.maxHopTtl.collectAsStateWithLifecycle()
    val mediaTransferProgress by repository.mediaTransferProgress.collectAsStateWithLifecycle()
    val feedPosts by repository.feedPosts.collectAsStateWithLifecycle()
    val conversations by repository.conversations.collectAsStateWithLifecycle()
    val channelMemberships by repository.channelMemberships.collectAsStateWithLifecycle()
    val blockReports by repository.blockReports.collectAsStateWithLifecycle()
    val messagesByChat by repository.messagesByChat.collectAsStateWithLifecycle()
    val nearbyPeers by repository.nearbyPeers.collectAsStateWithLifecycle()
    val meshStatus by repository.meshStatus.collectAsStateWithLifecycle()
    val totalMessagesCount = remember(messagesByChat) { messagesByChat.values.sumOf { it.size } }

    var isBiometricUnlocked by remember { mutableStateOf(!isBiometricLockEnabled) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isBiometricLockEnabled) {
        if (isBiometricLockEnabled && !isBiometricUnlocked) {
            BiometricLockHelper.showPrompt(
                activity = activity,
                onSuccess = {
                    isBiometricUnlocked = true
                    biometricError = null
                },
                onError = { msg -> biometricError = msg }
            )
        } else if (!isBiometricLockEnabled) {
            isBiometricUnlocked = true
        }
    }

    if (isBiometricLockEnabled && !isBiometricUnlocked) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "قفل البصمة",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = s.appLockedTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = biometricError ?: s.appLockedSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = {
                        BiometricLockHelper.showPrompt(
                            activity = activity,
                            onSuccess = {
                                isBiometricUnlocked = true
                                biometricError = null
                            },
                            onError = { msg -> biometricError = msg }
                        )
                    },
                    modifier = Modifier.testTag("unlock_biometric_button")
                ) {
                    Text(s.unlockNowBtn)
                }
            }
        }
        return
    }

    if (!isNicknameConfigured) {
        NicknameOnboardingScreen(
            suggestedNodeId = identity.nodeId,
            currentLanguage = appLanguage,
            onSelectLanguage = { repository.setLanguage(it) },
            onConfirmNickname = { chosenName -> repository.updateNickname(chosenName) }
        )
        return
    }

    var activeConversation by remember { mutableStateOf<ChatConversation?>(null) }
    var contactsRefreshTick by remember { mutableIntStateOf(0) }
    val isKeystoreProtected = remember(identity.nodeId) { repository.isKeystoreProtected() }

    val activeChat = activeConversation
    if (activeChat == null) {
        key(contactsRefreshTick) {
            LocChatHomeScreen(
                identity = identity,
                activeLocalIdentity = activeLocalIdentity,
                contactsManager = repository.contactsManager,
                mediaHelper = repository.mediaHelper,
                posts = feedPosts,
                conversations = conversations,
                channelMemberships = channelMemberships,
                blockReports = blockReports,
                messagesCount = totalMessagesCount,
                nearbyPeers = nearbyPeers,
                meshStatus = meshStatus,
                currentLanguage = appLanguage,
                isLocalPreviewMode = localPreviewBypass || !hasBluetoothAdapter,
                isKeystoreProtected = isKeystoreProtected,
                isBiometricLockEnabled = isBiometricLockEnabled,
                isStealthMode = isStealthMode,
                autoApplyTrustedBlocks = autoApplyTrustedBlocks,
                maxHopTtl = maxHopTtl,
                verifyNodeBinding = { nodeId, pubHex -> repository.verifyNodeIdMatchesPublicKey(nodeId, pubHex) },
                computeFingerprint = { edHex, xHex -> repository.formatFingerprintFromHex(edHex, xHex) },
                onChangeLanguage = { lang -> repository.setLanguage(lang) },
                onOpenChat = { conv ->
                    repository.markConversationRead(conv.id)
                    activeConversation = conv
                },
                onStartChatWithPeer = { peerId, nickname ->
                    activeConversation = repository.openOrCreateConversation(peerId, nickname, isChannel = false)
                },
                onCreateChannel = { name, passphrase ->
                    val normalized = if (name.startsWith("#")) name else "#$name"
                    activeConversation = repository.openOrCreateConversation(
                        peerId = normalized,
                        nickname = normalized,
                        isChannel = true,
                        passphrase = passphrase
                    )
                },
                onCreatePost = { text, imgPath, sizeKb, category, ttlHours ->
                    repository.publishFeedPost(text, imgPath, sizeKb, category, ttlHours)
                },
                onToggleLike = { postId -> repository.togglePostLike(postId) },
                onReactToPost = { postId, emoji -> repository.reactToFeedPost(postId, emoji) },
                onAddComment = { postId, commentText -> repository.addPostComment(postId, commentText) },
                onReBroadcastPost = { postId -> repository.reBroadcastFeedPost(postId) },
                onUpdateNickname = { newName -> repository.updateNickname(newName) },
                onToggleBiometricLock = { enabled ->
                    if (enabled) {
                        BiometricLockHelper.showPrompt(
                            activity = activity,
                            title = "تفعيل قفل التطبيق",
                            subtitle = "أكد هويتك لتفعيل قفل LocChat",
                            onSuccess = {
                                repository.setBiometricLockEnabled(true)
                                isBiometricUnlocked = true
                            }
                        )
                    } else {
                        repository.setBiometricLockEnabled(false)
                    }
                },
                onToggleStealthMode = { enabled -> repository.setStealthMode(enabled) },
                onToggleAutoApplyTrustedBlocks = { enabled -> repository.setAutoApplyTrustedBlocks(enabled) },
                onChangeMaxHopTtl = { hops -> repository.setMaxHopTtl(hops) },
                onClearRelayCaches = { repository.clearRelayCaches() },
                onVerifyFriend = { peerId, nickname, fingerprint, pubKeyHex, x25519KeyHex ->
                    repository.verifyFriend(peerId, nickname, fingerprint, pubKeyHex, x25519KeyHex)
                },
                onRemoveFriend = { peerId -> repository.removeFriend(peerId) },
                onBlockPeer = { peerId, nickname -> repository.blockPeer(peerId, nickname) },
                onBlockAndBroadcastPeer = { peerId, nickname, reason ->
                    repository.blockAndBroadcastPeer(peerId, nickname, reason)
                },
                onBroadcastExistingBlock = { blocked ->
                    repository.broadcastBlockForPeer(blocked.peerId, blocked.nickname)
                },
                onAcceptBlockReport = { report -> repository.acceptBlockReport(report) },
                onDismissBlockReport = { reportId -> repository.dismissBlockReport(reportId) },
                onUnblockPeer = { peerId -> repository.unblockPeer(peerId) },
                onChangePowerMode = { mode -> repository.setMeshPowerMode(mode) },
                onFriendVerified = { contactsRefreshTick++ },
                onPanicWipe = {
                    val executeWipe: () -> Unit = {
                        repository.performPanicWipe()
                        activeConversation = null
                        contactsRefreshTick++
                    }
                    if (isBiometricLockEnabled) {
                        BiometricLockHelper.showPrompt(
                            activity = activity,
                            title = "تأكيد المسح الطارئ",
                            subtitle = "تحقق بالبصمة قبل مسح كافة البيانات والمفاتيح",
                            onSuccess = executeWipe
                        )
                    } else {
                        executeWipe()
                    }
                }
            )
        }
    } else {
        val chatMsgs = messagesByChat[activeChat.id] ?: emptyList()

        ChatDetailScreen(
            conversation = activeChat,
            messages = chatMsgs,
            mediaHelper = repository.mediaHelper,
            transferProgress = mediaTransferProgress,
            onBack = { activeConversation = null },
            onSendText = { text, replyPreview ->
                repository.sendTextMessage(activeChat.id, activeChat.isChannel, text, replyPreview)
            },
            onSendImage = { uri ->
                repository.sendImageMessage(activeChat.id, activeChat.isChannel, uri)
            },
            onSendVoice = { file, duration ->
                repository.sendVoiceMessage(activeChat.id, activeChat.isChannel, file, duration)
            },
            onReactToMessage = { msgId, emoji ->
                repository.sendMessageReaction(activeChat.id, activeChat.isChannel, msgId, emoji)
            },
            onBlockPeerOnly = { peerId, nickname -> repository.blockPeer(peerId, nickname) },
            onBlockAndBroadcastPeer = { peerId, nickname, reason ->
                repository.blockAndBroadcastPeer(peerId, nickname, reason)
            }
        )
    }
}

@Composable
private fun NicknameOnboardingScreen(
    suggestedNodeId: String,
    currentLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onConfirmNickname: (String) -> Unit
) {
    val s = LocalAppStrings.current
    var nameInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LocChatSquircleLogo(
                size = 96.dp,
                showAmbientGlow = true
            )
            Spacer(modifier = Modifier.height(14.dp))
            LocChatWordmark(fontSize = 30.sp)
            Spacer(modifier = Modifier.height(10.dp))
            LocChatTaglineDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = currentLanguage == lang,
                        onClick = { onSelectLanguage(lang) },
                        label = { Text(lang.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = locChatRimBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = s.onboardingTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = s.onboardingSubtitle(suggestedNodeId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { if (it.length <= 24) nameInput = it },
                        label = { Text(s.onboardingInputLabel) },
                        placeholder = { Text(s.onboardingPlaceholder) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_nickname_input")
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onConfirmNickname(nameInput.trim()) },
                        enabled = nameInput.trim().length >= 2,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("onboarding_confirm_nickname_button")
                    ) {
                        Text(s.onboardingButton, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            LocChatFeaturePillRow()
        }
    }
}

private fun hasBluetoothHardware(context: Context): Boolean {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bm?.adapter != null
}

private fun checkBluetoothEnabled(context: Context): Boolean {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bm?.adapter?.isEnabled == true
}

private fun checkLocationEnabledForBle(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return LocationManagerCompat.isLocationEnabled(lm)
}
