package com.example.data

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/** وصول لمفتاح X25519 الثابت (الهوية) دون كشف المفتاح الخاص. */
interface StaticKeyProvider {
    /** المفتاح العام الثابت (32 بايت) أو null إن لم تتوفر هوية. */
    fun publicKey(): ByteArray?

    /** DH(s_me, peerPub). يرجع null عند الفشل أو عند نقطة منخفضة الرتبة. */
    fun dh(peerPub: ByteArray): ByteArray?
}

/** مفتاح رسالة واحدة. للقنوات تكون sid="" و n=-1. */
class SendKey(val sid: String, val n: Int, val key: ByteArray)

/**
 * جلسات المحادثة المباشرة مع سرية مستقبلية (Forward Secrecy).
 *
 * المصافحة (نمط Noise KK: الطرفان يعرفان مفتاح الآخر الثابت مسبقًا من QR/الإعلان الموقّع):
 *   -> e_i                      (SESSION_INIT)
 *   <- e_r, confirm             (SESSION_RESP)
 *   مفتاح الجلسة = HKDF( DH(e_i,s_r) || DH(s_i,s_r) || DH(e_i,e_r) || DH(s_i,e_r) )
 *   مرتبط بسجل المصافحة (sid + المفاتيح الأربعة).
 * بعد المصافحة: سلسلة مفاتيح أحادية الاتجاه (Symmetric ratchet): لكل رسالة مفتاح جديد
 * ثم يُمحى المفتاح السابق، فلا يمكن استرجاع مفاتيح الرسائل القديمة حتى لو سُرّب كل شيء لاحقًا.
 * المفاتيح المؤقتة لا تُخزَّن أبدًا؛ تُمحى فور اكتمال المصافحة.
 *
 * لا يعتمد على Android حتى يمكن اختباره كوحدة JVM عادية.
 */
class DirectSessionManager(
    private val keys: StaticKeyProvider,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val persist: ((String) -> Unit)? = null
) {

    private class Session(
        val sid: String,
        val peerId: String,
        val createdAt: Long,
        var sendKey: ByteArray,
        var sendN: Int,
        var recvKey: ByteArray,
        var recvN: Int
    ) {
        /** مفاتيح رسائل وصلت بترتيب مختلف (n -> مفتاح)، تُستهلك مرة واحدة. */
        val skipped = LinkedHashMap<Int, ByteArray>()
    }

    private class PendingInit(
        val sid: String,
        val ephPriv: X25519PrivateKeyParameters,
        val ephPub: ByteArray,
        val createdAt: Long
    )

    private val sessions = HashMap<String, MutableList<Session>>()
    private val pending = HashMap<String, PendingInit>()
    private val handledInits = HashMap<String, LinkedHashSet<String>>()

    // ------------------------------------------------------------------
    // واجهة المصافحة
    // ------------------------------------------------------------------

    /** المبادر: يرجع حمولة SESSION_INIT ("sid|ephHex") أو null إن كانت هناك مصافحة جارية/لا مفاتيح. */
    @Synchronized
    fun beginHandshake(peerId: String, peerStaticHex: String): String? {
        if (peerId.isBlank() || peerStaticHex.length != 64 || hexToBytesOrNull(peerStaticHex, 32) == null) return null
        if (keys.publicKey() == null) return null
        val now = clock()
        val existing = pending[peerId]
        if (existing != null && now - existing.createdAt < INIT_RETRY_MS) return null

        val priv = X25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey().encoded
        val sid = ByteArray(8).also { random.nextBytes(it) }.toHex()
        pending[peerId] = PendingInit(sid, priv, pub, now)
        return "$sid|${pub.toHex()}"
    }

    /** المستجيب: يرجع حمولة SESSION_RESP ("sid|ephHex|confirmHex") أو null عند الرفض. */
    @Synchronized
    fun acceptInit(peerId: String, peerStaticHex: String, payload: String): String? {
        val parts = payload.split('|')
        if (parts.size != 2) return null
        val sid = parts[0]
        if (!isHex(sid, 16)) return null
        val eI = hexToBytesOrNull(parts[1], 32) ?: return null
        val sI = hexToBytesOrNull(peerStaticHex, 32) ?: return null
        val sR = keys.publicKey() ?: return null

        val seen = handledInits.getOrPut(peerId) { LinkedHashSet() }
        if (seen.contains(sid)) return null // إعادة تشغيل لحزمة قديمة

        val ephR = X25519PrivateKeyParameters(random)
        val eR = ephR.generatePublicKey().encoded

        val es = keys.dh(eI) ?: return null            // DH(s_r, e_i)
        val ss = keys.dh(sI) ?: return null            // DH(s_r, s_i)
        val ee = dhEphemeral(ephR, eI) ?: return null  // DH(e_r, e_i)
        val se = dhEphemeral(ephR, sI) ?: return null  // DH(e_r, s_i)

        val okm = deriveKeys(es, ss, ee, se, sid, sI, sR, eI, eR)
        seen.add(sid)
        while (seen.size > MAX_HANDLED_INITS) seen.remove(seen.first())

        // المستجيب: يرسل بسلسلة resp->init ويستقبل بسلسلة init->resp
        addSession(
            Session(
                sid = sid,
                peerId = peerId,
                createdAt = clock(),
                sendKey = okm.copyOfRange(32, 64),
                sendN = 0,
                recvKey = okm.copyOfRange(0, 32),
                recvN = 0
            )
        )
        val confirm = okm.copyOfRange(64, 80)
        wipe(okm)
        notifyChanged()
        return "$sid|${eR.toHex()}|${confirm.toHex()}"
    }

    /** المبادر: يُكمل الجلسة من SESSION_RESP. يرجع true عند النجاح. */
    @Synchronized
    fun completeHandshake(peerId: String, peerStaticHex: String, payload: String): Boolean {
        val p = pending[peerId] ?: return false
        val parts = payload.split('|')
        if (parts.size != 3 || parts[0] != p.sid) return false
        val eR = hexToBytesOrNull(parts[1], 32) ?: return false
        val confirmRecv = hexToBytesOrNull(parts[2], 16) ?: return false
        val sR = hexToBytesOrNull(peerStaticHex, 32) ?: return false
        val sI = keys.publicKey() ?: return false

        val es = dhEphemeral(p.ephPriv, sR) ?: return false // DH(e_i, s_r)
        val ss = keys.dh(sR) ?: return false                // DH(s_i, s_r)
        val ee = dhEphemeral(p.ephPriv, eR) ?: return false // DH(e_i, e_r)
        val se = keys.dh(eR) ?: return false                // DH(s_i, e_r)

        val okm = deriveKeys(es, ss, ee, se, p.sid, sI, sR, p.ephPub, eR)
        val confirmLocal = okm.copyOfRange(64, 80)
        if (!MessageDigest.isEqual(confirmLocal, confirmRecv)) {
            wipe(okm)
            return false
        }
        pending.remove(peerId)
        addSession(
            Session(
                sid = p.sid,
                peerId = peerId,
                createdAt = clock(),
                sendKey = okm.copyOfRange(0, 32),
                sendN = 0,
                recvKey = okm.copyOfRange(32, 64),
                recvN = 0
            )
        )
        wipe(okm)
        notifyChanged()
        return true
    }

    // ------------------------------------------------------------------
    // الاستخدام
    // ------------------------------------------------------------------

    @Synchronized
    fun hasSession(peerId: String): Boolean = !sessions[peerId].isNullOrEmpty()

    /** مفتاح الإرسال التالي من أحدث جلسة، ثم تقدّم السلسلة وتُمحى المادة السابقة. */
    @Synchronized
    fun nextSendKey(peerId: String): SendKey? {
        val s = sessions[peerId]?.lastOrNull() ?: return null
        val mk = hmac(s.sendKey, byteArrayOf(0x01))
        val next = hmac(s.sendKey, byteArrayOf(0x02))
        wipe(s.sendKey)
        s.sendKey = next
        val n = s.sendN
        s.sendN = n + 1
        notifyChanged()
        return SendKey(s.sid, n, mk)
    }

    /** مفتاح فك الرسالة n في الجلسة sid. كل مفتاح يُستهلك مرة واحدة (يمنع إعادة التشغيل). */
    @Synchronized
    fun recvKey(peerId: String, sid: String, n: Int): ByteArray? {
        if (n < 0) return null
        val s = sessions[peerId]?.firstOrNull { it.sid == sid } ?: return null
        if (n < s.recvN) {
            return s.skipped.remove(n)?.also { notifyChanged() }
        }
        if (n - s.recvN > MAX_SKIP) return null
        while (s.recvN < n) {
            val skippedKey = hmac(s.recvKey, byteArrayOf(0x01))
            s.skipped[s.recvN] = skippedKey
            while (s.skipped.size > MAX_SKIPPED_STORED) {
                val oldest = s.skipped.keys.first()
                s.skipped.remove(oldest)?.let { wipe(it) }
            }
            val next = hmac(s.recvKey, byteArrayOf(0x02))
            wipe(s.recvKey)
            s.recvKey = next
            s.recvN++
        }
        val mk = hmac(s.recvKey, byteArrayOf(0x01))
        val next = hmac(s.recvKey, byteArrayOf(0x02))
        wipe(s.recvKey)
        s.recvKey = next
        s.recvN = n + 1
        notifyChanged()
        return mk
    }

    /** هل الجلسة الأحدث قديمة/مستهلكة بما يستدعي مصافحة جديدة؟ */
    @Synchronized
    fun needsRotation(peerId: String): Boolean {
        val s = sessions[peerId]?.lastOrNull() ?: return false
        return clock() - s.createdAt > ROTATE_AFTER_MS || s.sendN >= ROTATE_AFTER_MESSAGES
    }

    @Synchronized
    fun forgetPeer(peerId: String) {
        sessions.remove(peerId)?.forEach { wipeSession(it) }
        pending.remove(peerId)
        handledInits.remove(peerId)
        notifyChanged()
    }

    @Synchronized
    fun clearAll() {
        sessions.values.forEach { list -> list.forEach { wipeSession(it) } }
        sessions.clear()
        pending.clear()
        handledInits.clear()
        notifyChanged()
    }

    // ------------------------------------------------------------------
    // حفظ/استعادة (تُغلَّف الكتلة بمفتاح Keystore في الطبقة الأعلى)
    // ------------------------------------------------------------------

    /** سطر لكل جلسة: peerId|sid|createdAt|sendKeyHex|sendN|recvKeyHex|recvN. المفاتيح المتخطاة لا تُحفظ. */
    @Synchronized
    fun serialize(): String {
        val sb = StringBuilder()
        sessions.forEach { (peerId, list) ->
            list.forEach { s ->
                sb.append(peerId).append('|')
                    .append(s.sid).append('|')
                    .append(s.createdAt).append('|')
                    .append(s.sendKey.toHex()).append('|')
                    .append(s.sendN).append('|')
                    .append(s.recvKey.toHex()).append('|')
                    .append(s.recvN).append('\n')
            }
        }
        return sb.toString()
    }

    @Synchronized
    fun restore(blob: String?) {
        if (blob.isNullOrBlank()) return
        for (line in blob.lineSequence()) {
            val f = line.split('|')
            if (f.size != 7) continue
            val sendKey = hexToBytesOrNull(f[3], 32) ?: continue
            val recvKey = hexToBytesOrNull(f[5], 32) ?: continue
            val created = f[2].toLongOrNull() ?: continue
            val sendN = f[4].toIntOrNull() ?: continue
            val recvN = f[6].toIntOrNull() ?: continue
            if (!isHex(f[1], 16) || f[0].isBlank()) continue
            val list = sessions.getOrPut(f[0]) { ArrayList() }
            if (list.none { it.sid == f[1] }) {
                list.add(Session(f[1], f[0], created, sendKey, sendN, recvKey, recvN))
            }
        }
    }

    // ------------------------------------------------------------------
    // داخلي
    // ------------------------------------------------------------------

    private fun addSession(s: Session) {
        val list = sessions.getOrPut(s.peerId) { ArrayList() }
        list.add(s)
        while (list.size > MAX_SESSIONS_PER_PEER) wipeSession(list.removeAt(0))
    }

    private fun notifyChanged() {
        persist?.invoke(serialize())
    }

    private fun dhEphemeral(priv: X25519PrivateKeyParameters, pub: ByteArray): ByteArray? {
        return try {
            val agreement = X25519Agreement()
            agreement.init(priv)
            val out = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
            if (out.all { it == 0.toByte() }) null else out
        } catch (_: Exception) {
            null
        }
    }

    /** يرجع 96 بايت: [0,32) init->resp، [32,64) resp->init، [64,80) تأكيد المصافحة. */
    private fun deriveKeys(
        es: ByteArray, ss: ByteArray, ee: ByteArray, se: ByteArray,
        sid: String, sI: ByteArray, sR: ByteArray, eI: ByteArray, eR: ByteArray
    ): ByteArray {
        val ikm = es + ss + ee + se
        val info = "LOCCHAT_KK_V1".toByteArray(Charsets.UTF_8) + sid.toByteArray(Charsets.UTF_8) + sI + sR + eI + eR
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, "LOCCHAT_KK_SALT_V1".toByteArray(Charsets.UTF_8), info))
        val out = ByteArray(96)
        hkdf.generateBytes(out, 0, out.size)
        wipe(ikm)
        wipe(es); wipe(ss); wipe(ee); wipe(se)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    private fun wipeSession(s: Session) {
        wipe(s.sendKey)
        wipe(s.recvKey)
        s.skipped.values.forEach { wipe(it) }
        s.skipped.clear()
    }

    private fun wipe(b: ByteArray) {
        java.util.Arrays.fill(b, 0.toByte())
    }

    private fun isHex(s: String, len: Int): Boolean =
        s.length == len && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun hexToBytesOrNull(hex: String, expectedLen: Int): ByteArray? {
        if (hex.length != expectedLen * 2 || !isHex(hex, hex.length)) return null
        return ByteArray(expectedLen) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        const val INIT_RETRY_MS = 20_000L
        const val ROTATE_AFTER_MS = 30 * 60_000L
        const val ROTATE_AFTER_MESSAGES = 100
        const val MAX_SKIP = 64
        const val MAX_SKIPPED_STORED = 128
        const val MAX_SESSIONS_PER_PEER = 4
        const val MAX_HANDLED_INITS = 32
    }
}
