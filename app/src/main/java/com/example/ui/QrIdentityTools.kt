package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.VerifiedEmerald
import com.example.model.MeshIdentity
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ScannedIdentityQr(
    val nodeId: String,
    val nickname: String,
    val edPubKeyHex: String,
    val x25519KeyHex: String,
    val fingerprint: String
)

fun encodeIdentityQrPayload(identity: MeshIdentity): String {
    val safeName = identity.nickname.replace("|", " ").trim().take(24)
    return "LOCCHAT_ID|${identity.nodeId}|$safeName|${identity.publicKeyHex}|${identity.x25519PublicKeyHex}"
}

fun parseAndVerifyIdentityQr(
    raw: String,
    verifyNodeBinding: (nodeId: String, edPubKeyHex: String) -> Boolean,
    computeFingerprint: (edPubKeyHex: String, xPubKeyHex: String) -> String
): ScannedIdentityQr? {
    val parts = raw.trim().split("|")
    if (parts.size < 5 || parts[0] != "LOCCHAT_ID") return null
    val nodeId = parts[1].trim()
    val nickname = parts[2].trim().take(24).ifEmpty { nodeId }
    val edPubHex = parts[3].trim()
    val xPubHex = parts[4].trim()
    if (edPubHex.length != 64 || xPubHex.length != 64) return null
    if (!verifyNodeBinding(nodeId, edPubHex)) return null
    return ScannedIdentityQr(
        nodeId = nodeId,
        nickname = nickname,
        edPubKeyHex = edPubHex,
        x25519KeyHex = xPubHex,
        fingerprint = computeFingerprint(edPubHex, xPubHex)
    )
}

fun generateQrImageBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun MyIdentityQrDialog(
    identity: MeshIdentity,
    onDismiss: () -> Unit
) {
    val s = LocalAppStrings.current
    val clipboard = LocalClipboardManager.current
    val qrPayload = remember(identity) { encodeIdentityQrPayload(identity) }
    val qrBitmap = remember(qrPayload) { generateQrImageBitmap(qrPayload, 520) }
    var copiedPayload by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
        title = { Text(s.myQrDialogTitle) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR ${identity.nickname}",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Text(
                    text = "${identity.nickname} • ${identity.nodeId}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = identity.fingerprint,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = s.myQrDialogDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(qrPayload))
                        copiedPayload = true
                    },
                    modifier = Modifier.testTag("copy_my_qr_payload_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(s.copyQrPayload)
                }
                if (copiedPayload) {
                    Text(
                        text = s.copiedQrPayload,
                        style = MaterialTheme.typography.labelMedium,
                        color = VerifiedEmerald
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_my_qr_button")
            ) {
                Text(s.closeLabel)
            }
        }
    )
}

@Composable
fun QrScannerDialog(
    onQrScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val cameraStartError = LocalAppStrings.current.cameraStartError

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val detected = remember { AtomicBoolean(false) }
    val cameraProviderRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    DisposableEffect(Unit) {
        onDispose {
            // FIX: كانت الكاميرا تبقى مفتوحة بعد إغلاق النافذة (مرتبطة بدورة حياة النشاط)
            try {
                cameraProviderRef[0]?.unbindAll()
            } catch (_: Exception) {
            }
            analyzerExecutor.shutdown()
        }
    }

    val s = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.scanQrDialogTitle) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!hasCameraPermission) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = s.cameraPermissionDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.testTag("grant_camera_qr_button")
                    ) {
                        Text(s.grantCameraPermission)
                    }
                    // FIX: بعد رفض الإذن مرتين لا يظهر نظام أندرويد النافذة مجددًا؛ نوفّر طريق الإعدادات
                    TextButton(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.testTag("open_app_settings_button")
                    ) {
                        Text(s.openAppSettings)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    try {
                                        val cameraProvider = cameraProviderFuture.get()
                                        cameraProviderRef[0] = cameraProvider
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                        val reader = MultiFormatReader().apply {
                                            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                                        }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                            .also { imageAnalysis ->
                                                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                                    try {
                                                        if (!detected.get()) {
                                                            val plane = imageProxy.planes.firstOrNull()
                                                            if (plane != null) {
                                                                val buffer = plane.buffer
                                                                val remaining = buffer.remaining()
                                                                val safeSize = maxOf(remaining, plane.rowStride * imageProxy.height)
                                                                val bytes = ByteArray(safeSize)
                                                                buffer.get(bytes, 0, remaining)
                                                                val source = PlanarYUVLuminanceSource(
                                                                    bytes,
                                                                    plane.rowStride,
                                                                    imageProxy.height,
                                                                    0,
                                                                    0,
                                                                    imageProxy.width.coerceAtMost(plane.rowStride),
                                                                    imageProxy.height,
                                                                    false
                                                                )
                                                                val bitmap = BinaryBitmap(HybridBinarizer(source))
                                                                val result = reader.decodeWithState(bitmap)
                                                                if (result != null && detected.compareAndSet(false, true)) {
                                                                    ContextCompat.getMainExecutor(ctx).execute {
                                                                        onQrScanned(result.text)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (_: Exception) {
                                                    } finally {
                                                        reader.reset()
                                                        imageProxy.close()
                                                    }
                                                }
                                            }
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                    } catch (e: Exception) {
                                        cameraError = cameraStartError
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (cameraError != null) {
                        Text(
                            text = cameraError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = s.scanQrGuide,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancelLabel) }
        }
    )
}
