package com.example.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps and unwraps long-term Ed25519 / X25519 private keys using a non-exportable
 * AES-256/GCM/NoPadding key stored inside the hardware-backed Android Keystore (TEE / StrongBox).
 */
object KeystoreKeyWrapper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_ALIAS = "locchat_identity_master_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX_AKS = "AKS1:"
    private const val PREFIX_RAW = "RAW:"
    private const val IV_LENGTH = 12

    fun isWrappedWithKeystore(storedValue: String?): Boolean {
        return storedValue != null && storedValue.startsWith(PREFIX_AKS)
    }

    fun wrapPrivateKey(rawPrivateBytes: ByteArray): String {
        val aksCipher = try {
            val key = getOrCreateMasterKey()
            if (key != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                if (iv != null && iv.size == IV_LENGTH) {
                    val encrypted = cipher.doFinal(rawPrivateBytes)
                    PREFIX_AKS + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
        return aksCipher ?: (PREFIX_RAW + Base64.encodeToString(rawPrivateBytes, Base64.NO_WRAP))
    }

    fun unwrapPrivateKey(storedValue: String): ByteArray? {
        return try {
            when {
                storedValue.startsWith(PREFIX_AKS) -> {
                    val combined = Base64.decode(storedValue.removePrefix(PREFIX_AKS), Base64.NO_WRAP)
                    if (combined.size <= IV_LENGTH + 16) return null
                    val iv = combined.copyOfRange(0, IV_LENGTH)
                    val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
                    val key = getExistingMasterKey() ?: return null
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    cipher.doFinal(encrypted)
                }
                storedValue.startsWith(PREFIX_RAW) -> {
                    Base64.decode(storedValue.removePrefix(PREFIX_RAW), Base64.NO_WRAP)
                }
                else -> {
                    // Legacy pre-Keystore value
                    Base64.decode(storedValue, Base64.NO_WRAP)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteMasterKey() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(MASTER_ALIAS)) {
                ks.deleteEntry(MASTER_ALIAS)
            }
        } catch (_: Exception) {
        }
    }

    private fun getExistingMasterKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateMasterKey(): SecretKey? {
        getExistingMasterKey()?.let { return it }
        return try {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Exception) {
            null
        }
    }
}
