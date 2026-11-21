package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.BiometricLockHelper
import com.example.data.BlockReportEntity
import com.example.data.BlockedPeerEntity
import com.example.data.ChannelMembershipEntity
import com.example.data.ContactsManager
import com.example.data.LocalIdentityEntity
import com.example.mesh.MeshRepository
import com.example.model.AppLanguage
import com.example.model.MeshEngineStatus
import com.example.model.MeshIdentity
import com.example.model.MeshPowerMode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NearbyCyanTeal
import com.example.ui.theme.PanicCrimson
import com.example.ui.theme.PrivateShieldGold
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.VerifiedEmerald

@Composable
fun SettingsSecurityTab(
    identity: MeshIdentity,
    activeLocalIdentity: LocalIdentityEntity?,
    contactsManager: ContactsManager,
    meshStatus: MeshEngineStatus,
    channelMemberships: List<ChannelMembershipEntity>,
    blockReports: List<BlockReportEntity>,
    conversationsCount: Int,
    messagesCount: Int,
    currentLanguage: AppLanguage,
    isKeystoreProtected: Boolean,
    isBiometricLockEnabled: Boolean,
    isStealthMode: Boolean,
    autoApplyTrustedBlocks: Boolean,
    maxHopTtl: Int,
    verifyNodeBinding: (nodeId: String, edPubHex: String) -> Boolean,
    computeFingerprint: (edPubHex: String, xPubHex: String) -> String,
    onChangeLanguage: (AppLanguage) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onToggleBiometricLock: (Boolean) -> Unit,
    onToggleStealthMode: (Boolean) -> Unit,
    onToggleAutoApplyTrustedBlocks: (Boolean) -> Unit,
    onChangeMaxHopTtl: (Int) -> Unit,
    onChangePowerMode: (MeshPowerMode) -> Unit,
    onClearRelayCaches: () -> Unit,
    onVerifyFriend: (peerId: String, nickname: String, fingerprint: String, pubKeyHex: String, x25519KeyHex: String) -> Unit,
    onBlockAndBroadcastById: (peerId: String, nickname: String, reason: String) -> Unit,
    onBroadcastExistingBlock: (BlockedPeerEntity) -> Unit,
    onUnblockPeer: (String) -> Unit,
    onAcceptBlockReport: (BlockReportEntity) -> Unit,
    onDismissBlockReport: (String) -> Unit,
    onFriendVerified: () -> Unit,
    onPanicWipe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    val verifiedContacts by contactsManager.verifiedContacts.collectAsState()
    val blockedPeers by contactsManager.blockedPeers.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val biometricSupported = remember(context) { BiometricLockHelper.canAuthenticate(context) }

    var nicknameDraft by remember(identity.nickname) { mutableStateOf(identity.nickname) }
    var nicknameSavedBanner by remember { mutableStateOf(false) }

    var showVerifyDialog by remember { mutableStateOf(false) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showManualBlockDialog by remember { mutableStateOf(false) }
    var showPanicDialog by remember { mutableStateOf(false) }
    var copiedBanner by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Identity & Inline Nickname Editor persisted in Room
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.identityCardTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${s.idPrefix}: ${activeLocalIdentity?.nodeId ?: identity.nodeId}",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(if (activeLocalIdentity != null) s.savedInRoom else s.syncingLabel) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nicknameDraft,
                        onValueChange = {
                            if (it.length <= 24) {
                                nicknameDraft = it
                                nicknameSavedBanner = false
                            }
                        },
                        label = { Text(s.nicknameLabel) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inline_nickname_input")
                    )
                    Button(
                        onClick = {
                            if (nicknameDraft.trim().length >= 2) {
                                onUpdateNickname(nicknameDraft.trim())
                                nicknameSavedBanner = true
                            }
                        },
                        enabled = nicknameDraft.trim().length >= 2 && nicknameDraft.trim() != identity.nickname,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("inline_save_nickname_button")
                    ) {
                        Text(s.saveLabel)
                    }
                }

                AnimatedVisibility(visible = nicknameSavedBanner) {
                    Text(
                        text = s.nicknameSavedBanner,
                        style = MaterialTheme.typography.labelMedium,
                        color = VerifiedEmerald,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                AssistChip(
                    onClick = {},
                    leadingIcon = {
                        Icon(
                            imageVector = if (isKeystoreProtected) Icons.Default.VerifiedUser else Icons.Default.Key,
                            contentDescription = null,
                            tint = if (isKeystoreProtected) VerifiedEmerald else RadarAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(if (isKeystoreProtected) s.keystoreProtectedChip else s.softwareEncryptedChip)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = s.fingerprintTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = identity.fingerprint,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString("${identity.nodeId} | ${identity.fingerprint}"))
                                copiedBanner = true
                            },
                            modifier = Modifier.testTag("copy_fingerprint_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showMyQrDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("show_my_qr_button")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.showMyQr)
                    }
                    OutlinedButton(
                        onClick = { showQrScanner = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scan_friend_qr_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.scanFriendQr)
                    }
                }

                if (copiedBanner) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = s.copiedIdBanner(identity.nickname, identity.nodeId),
                        style = MaterialTheme.typography.labelMedium,
                        color = VerifiedEmerald
                    )
                }
                feedbackMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (msg.contains("✓")) VerifiedEmerald else PanicCrimson
                    )
                }
            }
        }

        // Language Selection Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = s.languageSectionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = s.languageSectionSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = currentLanguage == lang,
                            onClick = { onChangeLanguage(lang) },
                            label = { Text(lang.displayName, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("language_chip_${lang.code}")
                        )
                    }
                }
            }
        }

        // 2. Privacy, Stealth & Access Security
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = s.privacySectionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                SettingsToggleRow(
                    icon = Icons.Default.VisibilityOff,
                    iconTint = PrivateShieldGold,
                    title = s.stealthToggleTitle,
                    subtitle = s.stealthToggleDesc,
                    checked = isStealthMode,
                    enabled = true,
                    onCheckedChange = onToggleStealthMode,
                    testTag = "settings_stealth_switch"
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                SettingsToggleRow(
                    icon = Icons.Default.Fingerprint,
                    iconTint = NearbyCyanTeal,
                    title = s.biometricToggleTitle,
                    subtitle = if (biometricSupported) s.biometricToggleSupportedDesc else s.biometricToggleUnsupportedDesc,
                    checked = isBiometricLockEnabled,
                    enabled = biometricSupported || isBiometricLockEnabled,
                    onCheckedChange = onToggleBiometricLock,
                    testTag = "biometric_lock_switch"
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                SettingsToggleRow(
                    icon = Icons.Default.GppGood,
                    iconTint = VerifiedEmerald,
                    title = s.autoBlockToggleTitle,
                    subtitle = s.autoBlockToggleDesc,
                    checked = autoApplyTrustedBlocks,
                    enabled = true,
                    onCheckedChange = onToggleAutoApplyTrustedBlocks,
                    testTag = "auto_trusted_blocks_switch"
                )
            }
        }

        // 3. BLE Mesh Engine, Hops & Cache
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = s.meshSettingsTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = meshStatus.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Adv: ${if (meshStatus.isAdvertising) "ON" else "OFF"} • Scan: ${if (meshStatus.isScanning) "ON" else "OFF"} • Relay: ${meshStatus.packetsRelayed} • Cache: ${meshStatus.cachedPacketsCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = s.maxHopsTitle,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 7).forEach { hops ->
                        FilterChip(
                            selected = maxHopTtl == hops,
                            onClick = { onChangeMaxHopTtl(hops) },
                            label = { Text(s.hopsLabel(hops)) },
                            modifier = Modifier.testTag("hop_ttl_$hops")
                        )
                    }
                }

                Text(
                    text = s.powerModeTitle,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MeshPowerMode.entries.forEach { mode ->
                        FilterChip(
                            selected = meshStatus.powerMode == mode,
                            onClick = { onChangePowerMode(mode) },
                            label = { Text(mode.label(s.language)) },
                            modifier = Modifier.testTag("power_mode_${mode.name}")
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        onClearRelayCaches()
                        feedbackMessage = "${s.cacheClearedMsg} ✓"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.clearCacheBtn)
                }
            }
        }

        // 4. Blocklist & Network Block Broadcasts
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s.blocklistSectionTitle(blockedPeers.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = { showManualBlockDialog = true },
                        modifier = Modifier.testTag("open_manual_block_dialog")
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(s.blockByIdBtn)
                    }
                }

                if (blockedPeers.isEmpty()) {
                    Text(
                        text = s.noBlockedPeers,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    blockedPeers.forEach { blocked ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Block, contentDescription = null, tint = PanicCrimson)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(blocked.nickname, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = blocked.peerId,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onBroadcastExistingBlock(blocked)
                                        feedbackMessage = "تمت إذاعة حظر ${blocked.nickname} عبر شبكة Mesh"
                                    },
                                    modifier = Modifier.testTag("broadcast_block_${blocked.peerId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Campaign,
                                        contentDescription = "إذاعة الحظر",
                                        tint = PrivateShieldGold
                                    )
                                }
                                TextButton(onClick = { onUnblockPeer(blocked.peerId) }) {
                                    Text("إلغاء")
                                }
                            }
                        }
                    }
                }

                if (blockReports.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = s.networkBlockReportsTitle(blockReports.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PrivateShieldGold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    blockReports.forEach { report ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${report.blockedNickname} (${report.blockedPeerId})",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(if (report.reporterTrusted) s.trustedFriendBadge else s.networkReportBadge) }
                                    )
                                }
                                Text(
                                    text = "${report.reporterNickname} • ${report.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { onDismissBlockReport(report.reportId) }) {
                                        Text(s.dismissLabel)
                                    }
                                    Button(
                                        onClick = { onAcceptBlockReport(report) },
                                        colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson)
                                    ) {
                                        Text(s.applyBlockLabel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Verified Contacts & Offline Room Summary
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s.verifiedContactsSummaryTitle(verifiedContacts.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = { showVerifyDialog = true },
                        modifier = Modifier.testTag("open_verify_friend_dialog")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(s.manualVerifyBtn)
                    }
                }
                if (verifiedContacts.isEmpty()) {
                    Text(
                        text = s.contactsEmptySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    verifiedContacts.forEach { contact ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = VerifiedEmerald)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(contact.nickname, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "${contact.peerId} • ${contact.fingerprint}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = s.roomStorageSummary(conversationsCount, messagesCount, channelMemberships.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 6. Panic Wipe
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = s.panicWipeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = s.panicWipeDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showPanicDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("panic_wipe_button")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.panicWipeBtn)
                }
            }
        }

        Spacer(modifier = Modifier.height(72.dp))
    }

    if (showManualBlockDialog) {
        var peerIdInput by remember { mutableStateOf("") }
        var nameInput by remember { mutableStateOf("") }
        var reasonInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualBlockDialog = false },
            title = { Text("حظر وإذاعة الحظر بالمعرّف") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = peerIdInput,
                        onValueChange = { peerIdInput = it },
                        label = { Text("معرّف العقدة (مثال: LC-A1B2C3D4)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("الاسم (اختياري)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        label = { Text("سبب الحظر") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerIdInput.isNotBlank()) {
                            onBlockAndBroadcastById(
                                peerIdInput.trim(),
                                nameInput.trim().ifEmpty { peerIdInput.trim() },
                                reasonInput.trim().ifEmpty { "بلاغ حظر محلي" }
                            )
                            feedbackMessage = "تم حظر ${peerIdInput.trim()} وإذاعة الحظر للشبكة"
                            showManualBlockDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson)
                ) { Text("حظر وإذاعة") }
            },
            dismissButton = {
                TextButton(onClick = { showManualBlockDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (showVerifyDialog) {
        var peerId by remember { mutableStateOf("") }
        var nickname by remember { mutableStateOf("") }
        var fingerprint by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showVerifyDialog = false },
            title = { Text("توثيق هوية صديق") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = peerId,
                        onValueChange = { peerId = it },
                        label = { Text("معرّف العقدة (مثال: LC-A1B2)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text("الاسم") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it },
                        label = { Text("بصمة التشفير") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerId.isNotBlank() && fingerprint.isNotBlank()) {
                            onVerifyFriend(peerId, nickname, fingerprint, "", "")
                            onFriendVerified()
                            showVerifyDialog = false
                        }
                    }
                ) { Text("تأكيد التوثيق") }
            },
            dismissButton = {
                TextButton(onClick = { showVerifyDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (showMyQrDialog) {
        MyIdentityQrDialog(
            identity = identity,
            onDismiss = { showMyQrDialog = false }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrScanned = { raw ->
                showQrScanner = false
                val parsed = parseAndVerifyIdentityQr(raw, verifyNodeBinding, computeFingerprint)
                if (parsed != null) {
                    onVerifyFriend(
                        parsed.nodeId,
                        parsed.nickname,
                        parsed.fingerprint,
                        parsed.edPubKeyHex,
                        parsed.x25519KeyHex
                    )
                    onFriendVerified()
                    feedbackMessage = s.qrVerifySuccess(parsed.nickname, parsed.nodeId)
                } else {
                    feedbackMessage = s.qrVerifyError
                }
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text(s.panicDialogTitle) },
            text = { Text(s.panicDialogBody) },
            confirmButton = {
                Button(
                    onClick = {
                        showPanicDialog = false
                        onPanicWipe()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson),
                    modifier = Modifier.testTag("confirm_panic_wipe_button")
                ) { Text(s.panicConfirmBtn) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) { Text(s.cancelLabel) }
            }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
