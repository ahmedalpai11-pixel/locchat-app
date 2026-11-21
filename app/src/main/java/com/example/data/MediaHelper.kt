package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MediaHelper(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var recordingStartMs: Long = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()
    private var recordingTimerJob: Job? = null

    fun compressImageFromUri(uri: Uri, targetMaxBytes: Int = 28 * 1024): Pair<String, Int>? {
        return try {
            val imagesDir = File(context.filesDir, "mesh_images").apply { mkdirs() }
            val inputBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

            val orientation = try {
                ExifInterface(inputBytes.inputStream()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, bounds)
            var sampleSize = 1
            val maxDim = 512
            while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, decodeOpts) ?: return null
            val rotated = applyExifRotation(decoded, orientation)

            val scale = minOf(1f, maxDim.toFloat() / maxOf(rotated.width, rotated.height).toFloat())
            val finalBmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    rotated,
                    (rotated.width * scale).toInt().coerceAtLeast(1),
                    (rotated.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                rotated
            }

            val outStream = ByteArrayOutputStream()
            var quality = 70
            finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            while (outStream.size() > targetMaxBytes && quality > 22) {
                outStream.reset()
                quality -= 8
                finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            }

            val outFile = File(imagesDir, "img_${UUID.randomUUID()}.jpg")
            FileOutputStream(outFile).use { it.write(outStream.toByteArray()) }

            if (rotated !== decoded) decoded.recycle()
            if (finalBmp !== rotated) rotated.recycle()

            val sizeKb = (outFile.length() / 1024L).toInt().coerceAtLeast(1)
            outFile.absolutePath to sizeKb
        } catch (_: Exception) {
            null
        }
    }

    fun encodeFileToBase64(filePath: String?, maxBytes: Int = 68 * 1024): String? {
        if (filePath.isNullOrBlank()) return null
        return try {
            val file = File(filePath)
            if (!file.exists() || file.length() > maxBytes) return null
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun saveBase64ToImageFile(b64: String): Pair<String, Int>? {
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            if (bytes.size < 12 || bytes.size > 96 * 1024) return null
            val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            val isWebp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            if (!isJpeg && !isPng && !isWebp) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > 2048 || bounds.outHeight > 2048
            ) {
                return null
            }
            val imagesDir = File(context.filesDir, "mesh_images").apply { mkdirs() }
            val outFile = File(imagesDir, "rx_img_${UUID.randomUUID()}.jpg")
            FileOutputStream(outFile).use { it.write(bytes) }
            val sizeKb = (bytes.size / 1024).coerceAtLeast(1)
            outFile.absolutePath to sizeKb
        } catch (_: Exception) {
            null
        }
    }

    fun saveBase64ToVoiceFile(b64: String): String? {
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            if (bytes.size < 12 || bytes.size > 120 * 1024) return null
            // Validate ISO BMFF / M4A header ('ftyp' at bytes 4..7)
            val hasFtyp = bytes[4] == 'f'.code.toByte() &&
                bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() &&
                bytes[7] == 'p'.code.toByte()
            if (!hasFtyp) return null
            val voiceDir = File(context.filesDir, "mesh_voice").apply { mkdirs() }
            val outFile = File(voiceDir, "rx_voice_${UUID.randomUUID()}.m4a")
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun startVoiceRecording(): Boolean {
        if (_isRecording.value) return false
        return try {
            stopAudio()
            val voiceDir = File(context.filesDir, "mesh_voice").apply { mkdirs() }
            val outFile = File(voiceDir, "voice_${UUID.randomUUID()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(16000)
                setAudioSamplingRate(16000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            currentRecordingFile = outFile
            recordingStartMs = System.currentTimeMillis()
            _isRecording.value = true
            _recordingSeconds.value = 0

            recordingTimerJob?.cancel()
            recordingTimerJob = scope.launch {
                while (isActive && _isRecording.value) {
                    delay(250)
                    _recordingSeconds.value = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt()
                }
            }
            true
        } catch (_: Exception) {
            cleanupRecorder()
            false
        }
    }

    fun stopVoiceRecording(save: Boolean): Pair<File, Int>? {
        recordingTimerJob?.cancel()
        val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt().coerceAtLeast(1)
        val file = currentRecordingFile
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            file?.delete()
            cleanupRecorder()
            return null
        }
        mediaRecorder = null
        currentRecordingFile = null
        _isRecording.value = false
        _recordingSeconds.value = 0

        return if (save && file != null && file.exists() && file.length() > 0L) {
            file to elapsedSec
        } else {
            file?.delete()
            null
        }
    }

    fun togglePlayVoice(messageId: String, filePath: String?) {
        if (filePath.isNullOrBlank()) return
        if (_playingMessageId.value == messageId) {
            stopAudio()
            return
        }
        val file = File(filePath)
        if (!file.exists()) return

        stopAudio()
        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            mediaPlayer = player
            _playingMessageId.value = messageId
            _playbackProgress.value = 0f

            player.setOnCompletionListener {
                stopAudio()
            }

            progressJob?.cancel()
            progressJob = scope.launch {
                while (isActive && _playingMessageId.value == messageId) {
                    val dur = player.duration.coerceAtLeast(1)
                    val pos = player.currentPosition.coerceAtLeast(0)
                    _playbackProgress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    delay(80)
                }
            }
        } catch (_: Exception) {
            stopAudio()
        }
    }

    fun stopAudio() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _playingMessageId.value = null
        _playbackProgress.value = 0f
    }

    fun wipeAllMedia() {
        stopVoiceRecording(save = false)
        stopAudio()
        File(context.filesDir, "mesh_images").deleteRecursively()
        File(context.filesDir, "mesh_voice").deleteRecursively()
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        currentRecordingFile = null
        _isRecording.value = false
        _recordingSeconds.value = 0
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
