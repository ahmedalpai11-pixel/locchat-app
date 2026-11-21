package com.example.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standalone javax.crypto AES/GCM/NoPadding helper for encrypting and authenticating
 * message and media payloads before transmission over the Bluetooth GATT mesh service.
 */
object AesGcmCryptoHelper {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private val secureRandom = SecureRandom()

    fun encrypt(
        keyBytes: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray? = null
    ): String {
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES key must be 128, 192, or 256 bits"
        }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        val cipherBytes = cipher.doFinal(plaintext)
        return Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    fun decrypt(
        keyBytes: ByteArray,
        cipherTextBase64: String,
        associatedData: ByteArray? = null
    ): ByteArray? {
        return try {
            if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) return null
            val combined = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH_BYTES + 16) return null
            val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherBytes = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            if (associatedData != null && associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            null
        }
    }
}
