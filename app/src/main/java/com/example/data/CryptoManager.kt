package com.example.data

import android.content.Context
import android.util.Base64
import com.example.model.AppLanguage
import com.example.model.MeshIdentity
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

class CryptoManager(context: Context) {

    private val prefs = context.getSharedPreferences("locchat_identity_prefs", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun getOrCreateIdentity(): MeshIdentity {
        val existingEdPriv = prefs.getString(KEY_ED_PRIV_B64, null)
        val existingEdPub = prefs.getString(KEY_ED_PUB_B64, null)
        val existingXPriv = prefs.getString(KEY_X_PRIV_B64, null)
        val existingXPub = prefs.getString(KEY_X_PUB_B64, null)
        val savedNickname = prefs.getString(KEY_NICKNAME, null)
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)

        if (existingEdPriv != null && existingEdPub != null &&
            existingXPriv != null && existingXPub != null && createdAt > 0L
        ) {
            // Upgrade legacy plain private keys into Android Keystore wrapping on real devices
            if (!KeystoreKeyWrapper.isWrappedWithKeystore(existingEdPriv)) {
                KeystoreKeyWrapper.unwrapPrivateKey(existingEdPriv)?.let { rawEd ->
                    val wrapped = KeystoreKeyWrapper.wrapPrivateKey(rawEd)
                    if (KeystoreKeyWrapper.isWrappedWithKeystore(wrapped)) {
                        prefs.edit().putString(KEY_ED_PRIV_B64, wrapped).apply()
                    }
                }
            }
            if (!KeystoreKeyWrapper.isWrappedWithKeystore(existingXPriv)) {
                KeystoreKeyWrapper.unwrapPrivateKey(existingXPriv)?.let { rawX ->
                    val wrapped = KeystoreKeyWrapper.wrapPrivateKey(rawX)
                    if (KeystoreKeyWrapper.isWrappedWithKeystore(wrapped)) {
                        prefs.edit().putString(KEY_X_PRIV_B64, wrapped).apply()
                    }
                }
            }

            val edPubBytes = Base64.decode(existingEdPub, Base64.NO_WRAP)
            val xPubBytes = Base64.decode(existingXPub, Base64.NO_WRAP)
            return MeshIdentity(
                nodeId = deriveNodeId(edPubBytes),
                nickname = savedNickname ?: defaultNickname(edPubBytes),
                publicKeyHex = edPubBytes.toHex(),
                x25519PublicKeyHex = xPubBytes.toHex(),
                fingerprint = formatFingerprint(edPubBytes, xPubBytes),
                createdAt = createdAt
            )
        }

        return generateNewIdentity(savedNickname)
    }

    fun isKeystoreProtected(): Boolean {
        return KeystoreKeyWrapper.isWrappedWithKeystore(prefs.getString(KEY_ED_PRIV_B64, null))
    }

    fun isNicknameConfigured(): Boolean {
        return prefs.getBoolean(KEY_NICKNAME_CONFIGURED, false)
    }

    fun isBiometricLockEnabled(): Boolean {
        return prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
    }

    fun isStealthModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_STEALTH_MODE, false)
    }

    fun setStealthModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEALTH_MODE, enabled).apply()
    }

    fun isAutoApplyTrustedBlocksEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRUSTED_BLOCKS, true)
    }

    fun setAutoApplyTrustedBlocksEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRUSTED_BLOCKS, enabled).apply()
    }

    fun getMaxHopTtl(): Int {
        return prefs.getInt(KEY_MAX_HOP_TTL, 7).coerceIn(1, 7)
    }

    fun setMaxHopTtl(hops: Int) {
        prefs.edit().putInt(KEY_MAX_HOP_TTL, hops.coerceIn(1, 7)).apply()
    }

    fun getLanguage(): AppLanguage {
        return AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE_CODE, AppLanguage.ARABIC.code))
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE_CODE, language.code).apply()
    }

    @Synchronized
    fun regenerateIdentity(nickname: String? = null): MeshIdentity {
        KeystoreKeyWrapper.deleteMasterKey()
        channelKeyCache.clear()
        prefs.edit()
            .putBoolean(KEY_NICKNAME_CONFIGURED, !nickname.isNullOrBlank())
            .putBoolean(KEY_STEALTH_MODE, false)
            .apply()
        return generateNewIdentity(nickname)
    }

    @Synchronized
    fun updateNickname(newNickname: String): MeshIdentity {
        val clean = newNickname.trim().take(24).ifEmpty { "عقدة محلية" }
        prefs.edit()
            .putString(KEY_NICKNAME, clean)
            .putBoolean(KEY_NICKNAME_CONFIGURED, true)
            .apply()
        return getOrCreateIdentity()
    }

    fun signPayload(data: ByteArray): String {
        return try {
            val wrappedPriv = prefs.getString(KEY_ED_PRIV_B64, null) ?: return ""
            val privBytes = KeystoreKeyWrapper.unwrapPrivateKey(wrappedPriv) ?: return ""
            val privKey = Ed25519PrivateKeyParameters(privBytes, 0)
            val signer = Ed25519Signer()
            signer.init(true, privKey)
            signer.update(data, 0, data.size)
            Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun verifyNodeIdMatchesPublicKey(claimedNodeId: String, publicKeyHex: String): Boolean {
        return try {
            if (publicKeyHex.length != 64) return false
            val pubBytes = hexToBytes(publicKeyHex)
            if (pubBytes.size != 32) return false
            deriveNodeId(pubBytes).equals(claimedNodeId.trim(), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun verifyPacketIdentityAndSignature(
        claimedSenderId: String,
        publicKeyHex: String,
        data: ByteArray,
        signatureB64: String
    ): Boolean {
        return try {
            if (publicKeyHex.length != 64 || signatureB64.isBlank()) return false
            val pubBytes = hexToBytes(publicKeyHex)
            if (pubBytes.size != 32) return false
            // Cryptographically bind senderId to Ed25519 public key to block impersonation
            if (!deriveNodeId(pubBytes).equals(claimedSenderId, ignoreCase = true)) return false

            val sigBytes = Base64.decode(signatureB64, Base64.NO_WRAP)
            val pubKey = Ed25519PublicKeyParameters(pubBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubKey)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun deriveDirectKeyWithPeer(peerX25519PubHex: String): ByteArray? {
        return try {
            if (peerX25519PubHex.length != 64) return null
            val wrappedPriv = prefs.getString(KEY_X_PRIV_B64, null) ?: return null
            val privBytes = KeystoreKeyWrapper.unwrapPrivateKey(wrappedPriv) ?: return null
            val myPriv = X25519PrivateKeyParameters(privBytes, 0)
            val peerPub = X25519PublicKeyParameters(hexToBytes(peerX25519PubHex), 0)
            val agreement = X25519Agreement()
            agreement.init(myPriv)
            val secret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(peerPub, secret, 0)
            // Reject all-zero low-order X25519 points
            if (secret.all { it == 0.toByte() }) return null
            // HKDF-SHA256 مرتبط بالمفتاحين العامّين (مرتبين) لربط المفتاح بالزوج
            val myPub = Base64.decode(prefs.getString(KEY_X_PUB_B64, null) ?: return null, Base64.NO_WRAP)
            val peerPubBytes = hexToBytes(peerX25519PubHex)
            val (a, b) = if (compareBytes(myPub, peerPubBytes) <= 0) myPub to peerPubBytes else peerPubBytes to myPub
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(
                HKDFParameters(
                    secret,
                    "LOCCHAT_DH_V2".toByteArray(Charsets.UTF_8),
                    "direct-chat".toByteArray(Charsets.UTF_8) + a + b
                )
            )
            ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
        } catch (_: Exception) {
            null
        }
    }

    private fun compareBytes(x: ByteArray, y: ByteArray): Int {
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private val channelKeyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun deriveChannelKey(channelId: String, passphrase: String): ByteArray {
        val cleanChannel = channelId.trim().lowercase()
        return if (passphrase.isNotEmpty()) {
            // الاشتقاق مكلف عمدًا (210k دورة، إصدار V2)؛ نخزّن النتيجة لتجنب إعادته لكل رسالة
            val cacheKey = cleanChannel + "\u0000" + passphrase
            channelKeyCache[cacheKey]?.let { return it.copyOf() }
            val gen = PKCS5S2ParametersGenerator(SHA256Digest())
            gen.init(
                passphrase.toByteArray(Charsets.UTF_8),
                "LOCCHAT_PBKDF2_V2:$cleanChannel".toByteArray(Charsets.UTF_8),
                210_000
            )
            val derived = (gen.generateDerivedParameters(256) as KeyParameter).key
            channelKeyCache[cacheKey] = derived.copyOf()
            derived
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest("LOCCHAT_OPEN_CH_V1:$cleanChannel".toByteArray(Charsets.UTF_8))
        }
    }

    fun encryptTextWithKey(
        keyBytes: ByteArray,
        plaintext: String,
        associatedData: ByteArray? = null
    ): String {
        // FIX (أمان): لا نعود أبدًا إلى النص الصريح عند فشل التشفير؛ نرمي استثناءً ليُلغى الإرسال.
        val padded = padPkcs7(plaintext.toByteArray(Charsets.UTF_8), 32)
        return AesGcmCryptoHelper.encrypt(keyBytes, padded, associatedData)
    }

    fun decryptTextWithKey(
        keyBytes: ByteArray,
        cipherTextB64: String,
        associatedData: ByteArray? = null
    ): String? {
        val decrypted = AesGcmCryptoHelper.decrypt(keyBytes, cipherTextB64, associatedData) ?: return null
        val unpadded = unpadPkcs7(decrypted)
        return String(unpadded, Charsets.UTF_8)
    }

    fun hashPassphrase(passphrase: String): String {
        if (passphrase.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest("LOCCHAT_PASS_CHECK:$passphrase".toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun generateNewIdentity(customNickname: String?): MeshIdentity {
        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edPair = edGen.generateKeyPair()
        val edPriv = (edPair.private as Ed25519PrivateKeyParameters).encoded
        val edPub = (edPair.public as Ed25519PublicKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xPair = xGen.generateKeyPair()
        val xPriv = (xPair.private as X25519PrivateKeyParameters).encoded
        val xPub = (xPair.public as X25519PublicKeyParameters).encoded

        val now = System.currentTimeMillis()
        val nickname = customNickname?.trim()?.take(24)?.ifEmpty { null } ?: defaultNickname(edPub)

        prefs.edit()
            .putString(KEY_ED_PRIV_B64, KeystoreKeyWrapper.wrapPrivateKey(edPriv))
            .putString(KEY_ED_PUB_B64, Base64.encodeToString(edPub, Base64.NO_WRAP))
            .putString(KEY_X_PRIV_B64, KeystoreKeyWrapper.wrapPrivateKey(xPriv))
            .putString(KEY_X_PUB_B64, Base64.encodeToString(xPub, Base64.NO_WRAP))
            .putString(KEY_NICKNAME, nickname)
            .putLong(KEY_CREATED_AT, now)
            .apply()

        return MeshIdentity(
            nodeId = deriveNodeId(edPub),
            nickname = nickname,
            publicKeyHex = edPub.toHex(),
            x25519PublicKeyHex = xPub.toHex(),
            fingerprint = formatFingerprint(edPub, xPub),
            createdAt = now
        )
    }

    fun formatFingerprintFromHex(edPubHex: String, xPubHex: String = ""): String {
        return try {
            val ed = hexToBytes(edPubHex)
            val x = if (xPubHex.isNotBlank()) hexToBytes(xPubHex) else ByteArray(0)
            formatFingerprint(ed, x)
        } catch (_: Exception) {
            edPubHex.take(16).uppercase()
        }
    }

    private fun padPkcs7(data: ByteArray, blockSize: Int): ByteArray {
        val padLen = blockSize - (data.size % blockSize)
        val out = ByteArray(data.size + padLen)
        System.arraycopy(data, 0, out, 0, data.size)
        for (i in data.size until out.size) {
            out[i] = padLen.toByte()
        }
        return out
    }

    private fun unpadPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padLen = data.last().toInt() and 0xFF
        if (padLen <= 0 || padLen > 32 || padLen > data.size) return data
        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xFF) != padLen) return data
        }
        return data.copyOfRange(0, data.size - padLen)
    }

    private fun deriveNodeId(pubBytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        // 128 بت (16 بايت) بدل 64 بت لمقاومة انتحال الهوية بالقوة الغاشمة
        return "LC-" + hash.take(16).toByteArray().toHex().uppercase()
    }

    private fun formatFingerprint(edPub: ByteArray, xPub: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(edPub + xPub)
        val hex = hash.take(8).toByteArray().toHex().uppercase()
        return hex.chunked(4).joinToString(" • ")
    }

    private fun defaultNickname(pubBytes: ByteArray): String {
        val suffix = pubBytes.take(2).toByteArray().toHex().uppercase()
        return "مستخدم-$suffix"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val KEY_ED_PRIV_B64 = "ed25519_priv_b64"
        private const val KEY_ED_PUB_B64 = "ed25519_pub_b64"
        private const val KEY_X_PRIV_B64 = "x25519_priv_b64"
        private const val KEY_X_PUB_B64 = "x25519_pub_b64"
        private const val KEY_NICKNAME = "node_nickname"
        private const val KEY_NICKNAME_CONFIGURED = "nickname_configured"
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
        private const val KEY_STEALTH_MODE = "stealth_mode_enabled"
        private const val KEY_AUTO_TRUSTED_BLOCKS = "auto_apply_trusted_blocks"
        private const val KEY_MAX_HOP_TTL = "max_hop_ttl"
        private const val KEY_LANGUAGE_CODE = "app_language_code"
        private const val KEY_CREATED_AT = "identity_created_at"
    }
}
