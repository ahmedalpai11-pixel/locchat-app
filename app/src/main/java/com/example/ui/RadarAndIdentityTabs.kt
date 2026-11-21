package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.MeshEngineStatus
import com.example.model.NearbyPeer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocChatBrandGradient
import com.example.ui.theme.MeshAzure
import com.example.ui.theme.MeshViolet
import com.example.ui.theme.NearbyCyanTeal
import com.example.ui.theme.PanicCrimson
import com.example.ui.theme.PrivateShieldGold
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.VerifiedEmerald
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarTab(
    nearbyPeers: List<NearbyPeer>,
    meshStatus: MeshEngineStatus,
    isStealthMode: Boolean,
    onToggleStealthMode: (Boolean) -> Unit,
    onStartChatWithPeer: (peerId: String, nickname: String) -> Unit,
    onVerifyPeer: (peer: NearbyPeer) -> Unit,
    onBlockPeerOnly: (peer: NearbyPeer) -> Unit,
    onBlockAndBroadcastPeer: (peer: NearbyPeer, reason: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    val activePeers = remember(nearbyPeers) { nearbyPeers.filter { !it.isBlocked } }
    var peerPendingBlock by remember { mutableStateOf<NearbyPeer?>(null) }
    var copiedIdNotice by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = locChatRimBorder()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isStealthMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = if (isStealthMode) PrivateShieldGold else NearbyCyanTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isStealthMode) s.radarStealthActive else s.radarVisibleActive,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isStealthMode) s.radarStealthDesc else s.radarVisibleDesc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = isStealthMode,
                    onCheckedChange = onToggleStealthMode,
                    modifier = Modifier.testTag("radar_stealth_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        MeshRadarCanvas(
            peers = activePeers,
            isScanning = meshStatus.isScanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(195.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = s.discoveredDevicesCount(activePeers.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = meshStatus.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(visible = copiedIdNotice != null) {
            Text(
                text = copiedIdNotice.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = VerifiedEmerald,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (activePeers.isEmpty()) {
            EmptyStateBox(
                icon = Icons.Default.Radar,
                title = s.radarEmptyTitle,
                subtitle = s.radarEmptySubtitle
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(activePeers, key = { it.peerId }) { peer ->
                    NearbyPeerCard(
                        peer = peer,
                        onCopyId = {
                            clipboard.setText(AnnotatedString(peer.peerId))
                            copiedIdNotice = s.copiedIdBanner(peer.nickname, peer.peerId)
                        },
                        onChat = { onStartChatWithPeer(peer.peerId, peer.nickname) },
                        onVerify = { onVerifyPeer(peer) },
                        onRequestBlock = { peerPendingBlock = peer }
                    )
                }
            }
        }
    }

    peerPendingBlock?.let { target ->
        BlockAndBroadcastDialog(
            targetName = target.nickname,
            targetId = target.peerId,
            onDismiss = { peerPendingBlock = null },
            onBlockLocalOnly = {
                onBlockPeerOnly(target)
                peerPendingBlock = null
            },
            onBlockAndBroadcast = { reason ->
                onBlockAndBroadcastPeer(target, reason)
                peerPendingBlock = null
            }
        )
    }
}

@Composable
fun BlockAndBroadcastDialog(
    targetName: String,
    targetId: String,
    onDismiss: () -> Unit,
    onBlockLocalOnly: () -> Unit,
    onBlockAndBroadcast: (reason: String) -> Unit
) {
    val s = LocalAppStrings.current
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Block, contentDescription = null, tint = PanicCrimson) },
        title = { Text(s.blockDialogTitle(targetName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = s.blockDialogDesc(targetId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 100) reason = it },
                    label = { Text(s.blockReasonLabel) },
                    placeholder = { Text(s.blockReasonPlaceholder) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("block_reason_input")
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onBlockAndBroadcast(reason.ifBlank { s.blockReasonPlaceholder }) },
                    colors = ButtonDefaults.buttonColors(containerColor = PanicCrimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_block_and_broadcast_button")
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.blockAndBroadcastBtn)
                }
                OutlinedButton(
                    onClick = onBlockLocalOnly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_block_local_only_button")
                ) {
                    Text(s.blockLocalOnlyBtn)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(s.cancelLabel)
                }
            }
        }
    )
}

@Composable
private fun MeshRadarCanvas(
    peers: List<NearbyPeer>,
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = surfaceColor,
        border = locChatRimBorder(),
        tonalElevation = 1.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = minOf(size.width, size.height) * 0.42f

            val ringColors = listOf(ElectricCyan, MeshAzure, MeshViolet)
            listOf(0.33f, 0.66f, 1.0f).forEachIndexed { idx, factor ->
                drawCircle(
                    color = ringColors[idx].copy(alpha = 0.30f),
                    radius = maxRadius * factor,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            if (isScanning) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.6f to MeshViolet.copy(alpha = 0.08f),
                        0.85f to MeshAzure.copy(alpha = 0.22f),
                        1f to ElectricCyan.copy(alpha = 0.42f),
                        center = center
                    ),
                    startAngle = sweepAngle,
                    sweepAngle = 78f,
                    useCenter = true,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                )
            }

            drawCircle(
                color = NearbyCyanTeal.copy(alpha = 0.28f),
                radius = 14.dp.toPx(),
                center = center
            )
            drawCircle(
                color = primaryColor,
                radius = 7.dp.toPx(),
                center = center
            )

            peers.forEachIndexed { index, peer ->
                val angleRad = Math.toRadians((index * 73.0 + 25.0) % 360.0)
                val normalizedDist = ((-peer.rssi).coerceIn(40, 100) - 40f) / 60f
                val dist = maxRadius * (0.28f + normalizedDist * 0.65f)
                val blip = Offset(
                    x = center.x + (cos(angleRad) * dist).toFloat(),
                    y = center.y + (sin(angleRad) * dist).toFloat()
                )
                drawCircle(
                    color = if (peer.isVerified) VerifiedEmerald else RadarAmber,
                    radius = 6.dp.toPx(),
                    center = blip
                )
            }
        }
    }
}

@Composable
private fun NearbyPeerCard(
    peer: NearbyPeer,
    onCopyId: () -> Unit,
    onChat: () -> Unit,
    onVerify: () -> Unit,
    onRequestBlock: () -> Unit
) {
    val s = LocalAppStrings.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nearby_peer_${peer.peerId}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = locChatRimBorder(),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocChatBrandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.nickname,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (peer.isVerified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = VerifiedEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = onCopyId,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.testTag("copy_peer_id_${peer.peerId}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "ID: ${peer.peerId}",
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
                            text = "${peer.rssi} dBm • ${s.hopsLabel(peer.hopCount)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRequestBlock,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PanicCrimson),
                    modifier = Modifier.testTag("block_peer_${peer.peerId}")
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s.blockOrBroadcastBtn)
                }

                if (!peer.isVerified) {
                    IconButton(
                        onClick = onVerify,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("verify_peer_${peer.peerId}")
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = s.verifyFriendBtnDesc)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                FilledTonalButton(
                    onClick = onChat,
                    modifier = Modifier.testTag("chat_peer_${peer.peerId}")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.chatActionBtn)
                }
            }
        }
    }
}
