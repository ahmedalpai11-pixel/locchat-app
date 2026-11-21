package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ContactsManager
import com.example.model.MeshIdentity
import com.example.model.NearbyPeer
import com.example.model.VerifiedContact
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocChatBrandGradient
import com.example.ui.theme.PanicCrimson
import com.example.ui.theme.VerifiedEmerald

@Composable
fun ContactsTab(
    identity: MeshIdentity,
    contactsManager: ContactsManager,
    nearbyPeers: List<NearbyPeer>,
    verifyNodeBinding: (nodeId: String, edPubHex: String) -> Boolean,
    computeFingerprint: (edPubHex: String, xPubHex: String) -> String,
    onStartChatWithFriend: (peerId: String, nickname: String) -> Unit,
    onVerifyFriend: (peerId: String, nickname: String, fingerprint: String, pubKeyHex: String, x25519KeyHex: String) -> Unit,
    onRemoveFriend: (peerId: String) -> Unit,
    onBlockPeerOnly: (peerId: String, nickname: String) -> Unit,
    onBlockAndBroadcastPeer: (peerId: String, nickname: String, reason: String) -> Unit,
    onFriendVerified: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    val verifiedContacts by contactsManager.verifiedContacts.collectAsState()
    val blockedIds by contactsManager.blockedPeerIds.collectAsState()
    val clipboard = LocalClipboardManager.current

    var searchQuery by remember { mutableStateOf("") }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showManualAddDialog by remember { mutableStateOf(false) }
    var contactPendingBlock by remember { mutableStateOf<VerifiedContact?>(null) }
    var contactPendingRemove by remember { mutableStateOf<VerifiedContact?>(null) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }

    val onlinePeerIds = remember(nearbyPeers) {
        nearbyPeers.filter { !it.isBlocked }.map { it.peerId.uppercase() }.toSet()
    }

    val unsavedNearbyPeers = remember(nearbyPeers, verifiedContacts, blockedIds) {
        val savedIds = verifiedContacts.map { it.peerId.uppercase() }.toSet()
        nearbyPeers.filter {
            !it.isBlocked &&
                !blockedIds.contains(it.peerId.uppercase()) &&
                !savedIds.contains(it.peerId.uppercase())
        }
    }

    val filteredContacts = remember(verifiedContacts, searchQuery, blockedIds) {
        val q = searchQuery.trim()
        verifiedContacts
            .filter { !blockedIds.contains(it.peerId.uppercase()) }
            .filter {
                q.isEmpty() ||
                    it.nickname.contains(q, ignoreCase = true) ||
                    it.peerId.contains(q, ignoreCase = true) ||
                    it.fingerprint.contains(q, ignoreCase = true)
            }
            .sortedBy { it.nickname.lowercase() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // QR + Add Friends Action Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder(),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.contactsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${identity.nickname} • ${identity.nodeId}",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("${filteredContacts.size}")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showMyQrDialog = true },
                        modifier = Modifier.testTag("contacts_show_my_qr_button")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.showMyQr)
                    }
                    Button(
                        onClick = { showQrScanner = true },
                        modifier = Modifier.testTag("contacts_scan_qr_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.scanFriendQr)
                    }
                    OutlinedButton(
                        onClick = { showManualAddDialog = true },
                        modifier = Modifier.testTag("contacts_manual_add_button")
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.addManualFriend)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(s.contactsSearchPlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = s.closeLabel)
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("contacts_search_input")
        )

        AnimatedVisibility(visible = bannerMessage != null) {
            Text(
                text = bannerMessage.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = VerifiedEmerald,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (unsavedNearbyPeers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = s.nearbyUnsavedTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                unsavedNearbyPeers.forEach { peer ->
                    AssistChip(
                        onClick = {
                            onVerifyFriend(
                                peer.peerId,
                                peer.nickname,
                                peer.fingerprint,
                                peer.publicKeyHex,
                                peer.x25519PublicKeyHex
                            )
                            onFriendVerified()
                            bannerMessage = s.qrVerifySuccess(peer.nickname, peer.peerId)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("${peer.nickname} (${peer.peerId})") },
                        modifier = Modifier.testTag("quick_add_peer_${peer.peerId}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredContacts.isEmpty()) {
            EmptyStateBox(
                icon = Icons.Default.Contacts,
                title = s.contactsEmptyTitle,
                subtitle = s.contactsEmptySubtitle
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredContacts, key = { it.peerId }) { contact ->
                    val isOnline = onlinePeerIds.contains(contact.peerId.uppercase())
                    ContactBookCard(
                        contact = contact,
                        isOnline = isOnline,
                        onCopyId = {
                            clipboard.setText(AnnotatedString(contact.peerId))
                            bannerMessage = s.copiedIdBanner(contact.nickname, contact.peerId)
                        },
                        onChat = { onStartChatWithFriend(contact.peerId, contact.nickname) },
                        onRequestBlock = { contactPendingBlock = contact },
                        onRequestRemove = { contactPendingRemove = contact }
                    )
                }
            }
        }
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
                    bannerMessage = s.qrVerifySuccess(parsed.nickname, parsed.nodeId)
                } else {
                    bannerMessage = s.qrVerifyError
                }
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showManualAddDialog) {
        var peerId by remember { mutableStateOf("") }
        var nickname by remember { mutableStateOf("") }
        var fingerprint by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualAddDialog = false },
            title = { Text(s.manualVerifyBtn) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = peerId,
                        onValueChange = { peerId = it },
                        label = { Text("${s.idPrefix} (LC-A1B2C3D4)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text(s.nicknameLabel) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it },
                        label = { Text(s.fingerprintTitle.removeSuffix(":")) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerId.isNotBlank()) {
                            val cleanId = peerId.trim().uppercase()
                            val cleanName = nickname.trim().ifEmpty { cleanId }
                            val cleanFp = fingerprint.trim().ifEmpty { cleanId }
                            onVerifyFriend(cleanId, cleanName, cleanFp, "", "")
                            onFriendVerified()
                            bannerMessage = s.qrVerifySuccess(cleanName, cleanId)
                            showManualAddDialog = false
                        }
                    },
                    enabled = peerId.isNotBlank()
                ) { Text(s.addFriendAction) }
            },
            dismissButton = {
                TextButton(onClick = { showManualAddDialog = false }) { Text(s.cancelLabel) }
            }
        )
    }

    contactPendingBlock?.let { target ->
        BlockAndBroadcastDialog(
            targetName = target.nickname,
            targetId = target.peerId,
            onDismiss = { contactPendingBlock = null },
            onBlockLocalOnly = {
                onBlockPeerOnly(target.peerId, target.nickname)
                contactPendingBlock = null
            },
            onBlockAndBroadcast = { reason ->
                onBlockAndBroadcastPeer(target.peerId, target.nickname, reason)
                contactPendingBlock = null
            }
        )
    }

    contactPendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { contactPendingRemove = null },
            title = { Text(s.confirmRemoveFriendTitle) },
            text = { Text(s.confirmRemoveFriendBody(target.nickname)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveFriend(target.peerId)
                        onFriendVerified()
                        contactPendingRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson)
                ) { Text(s.removeFriendLabel) }
            },
            dismissButton = {
                TextButton(onClick = { contactPendingRemove = null }) { Text(s.cancelLabel) }
            }
        )
    }
}

@Composable
private fun ContactBookCard(
    contact: VerifiedContact,
    isOnline: Boolean,
    onCopyId: () -> Unit,
    onChat: () -> Unit,
    onRequestBlock: () -> Unit,
    onRequestRemove: () -> Unit
) {
    val s = LocalAppStrings.current
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact_card_${contact.peerId}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = locChatRimBorder(),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp)) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(LocChatBrandGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.nickname.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isOnline) {
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(VerifiedEmerald)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = contact.nickname,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = VerifiedEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = onCopyId,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = contact.peerId,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = if (isOnline) s.onlineInMesh else s.offlineSaved,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOnline) VerifiedEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = contact.fingerprint,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.blockOrBroadcastBtn, color = PanicCrimson) },
                            leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = PanicCrimson) },
                            onClick = {
                                showMenu = false
                                onRequestBlock()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(s.removeFriendLabel) },
                            leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRequestRemove()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onChat,
                    modifier = Modifier.testTag("contact_chat_${contact.peerId}")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.chatActionBtn)
                }
            }
        }
    }
}
