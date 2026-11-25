package com.example

import com.example.data.DirectSessionManager
import com.example.data.StaticKeyProvider
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class DirectSessionManagerTest {

    private class FakeKeys : StaticKeyProvider {
        private val priv = X25519PrivateKeyParameters(SecureRandom())
        private val pub = priv.generatePublicKey().encoded

        override fun publicKey(): ByteArray = pub.copyOf()

        override fun dh(peerPub: ByteArray): ByteArray? {
            val agreement = X25519Agreement()
            agreement.init(priv)
            val out = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), out, 0)
            return out
        }

        fun pubHex(): String = pub.joinToString("") { "%02x".format(it) }
    }

    private class Party(val id: String) {
        val keys = FakeKeys()
        var blob: String? = null
        val mgr = DirectSessionManager(keys, persist = { blob = it })
    }

    /** يُنشئ جلسة كاملة بين a (المبادر) و b (المستجيب). */
    private fun handshake(a: Party, b: Party) {
        val init = a.mgr.beginHandshake(b.id, b.keys.pubHex())
        assertNotNull(init)
        val resp = b.mgr.acceptInit(a.id, a.keys.pubHex(), init!!)
        assertNotNull(resp)
        assertTrue(a.mgr.completeHandshake(b.id, b.keys.pubHex(), resp!!))
    }

    @Test
    fun handshake_producesMatchingKeysInBothDirections() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)

        val sendA = a.mgr.nextSendKey("B")!!
        val recvB = b.mgr.recvKey("A", sendA.sid, sendA.n)
        assertArrayEquals(sendA.key, recvB)

        val sendB = b.mgr.nextSendKey("A")!!
        val recvA = a.mgr.recvKey("B", sendB.sid, sendB.n)
        assertArrayEquals(sendB.key, recvA)
    }

    @Test
    fun everyMessageGetsAFreshKey() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        val k0 = a.mgr.nextSendKey("B")!!
        val k1 = a.mgr.nextSendKey("B")!!
        assertEquals(0, k0.n)
        assertEquals(1, k1.n)
        assertFalse(k0.key.contentEquals(k1.key))
    }

    @Test
    fun replayedMessageKeyIsRejected() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        val k = a.mgr.nextSendKey("B")!!
        assertNotNull(b.mgr.recvKey("A", k.sid, k.n))
        assertNull(b.mgr.recvKey("A", k.sid, k.n))
    }

    @Test
    fun outOfOrderMessagesStillDecrypt() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        val k0 = a.mgr.nextSendKey("B")!!
        val k1 = a.mgr.nextSendKey("B")!!
        val k2 = a.mgr.nextSendKey("B")!!
        assertArrayEquals(k2.key, b.mgr.recvKey("A", k2.sid, k2.n))
        assertArrayEquals(k0.key, b.mgr.recvKey("A", k0.sid, k0.n))
        assertArrayEquals(k1.key, b.mgr.recvKey("A", k1.sid, k1.n))
    }

    @Test
    fun hugeGapIsRejected() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        val k = a.mgr.nextSendKey("B")!!
        assertNull(b.mgr.recvKey("A", k.sid, DirectSessionManager.MAX_SKIP + 5))
    }

    @Test
    fun unknownSessionIdIsRejected() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        assertNull(b.mgr.recvKey("A", "0000000000000000", 0))
    }

    @Test
    fun replayedInitIsIgnored() {
        val a = Party("A")
        val b = Party("B")
        val init = a.mgr.beginHandshake("B", b.keys.pubHex())!!
        assertNotNull(b.mgr.acceptInit("A", a.keys.pubHex(), init))
        assertNull(b.mgr.acceptInit("A", a.keys.pubHex(), init))
    }

    @Test
    fun handshakeWithWrongStaticKeyFails() {
        val a = Party("A")
        val b = Party("B")
        val impostor = Party("B")
        val init = a.mgr.beginHandshake("B", b.keys.pubHex())!!
        // المهاجم يردّ بمفتاحه الثابت بدل مفتاح B الحقيقي: التأكيد لن يتطابق
        val resp = impostor.mgr.acceptInit("A", a.keys.pubHex(), init)!!
        assertFalse(a.mgr.completeHandshake("B", b.keys.pubHex(), resp))
        assertFalse(a.mgr.hasSession("B"))
    }

    @Test
    fun responseForAnotherSessionIsRejected() {
        val a = Party("A")
        val b = Party("B")
        val init1 = a.mgr.beginHandshake("B", b.keys.pubHex())!!
        val resp1 = b.mgr.acceptInit("A", a.keys.pubHex(), init1)!!
        // مصافحة جديدة تستبدل المعلّقة؛ الرد القديم يجب ألا يُقبل
        val a2 = Party("A")
        assertFalse(a2.mgr.completeHandshake("B", b.keys.pubHex(), resp1))
    }

    @Test
    fun secondInitWithinRetryWindowIsSuppressed() {
        val a = Party("A")
        val b = Party("B")
        assertNotNull(a.mgr.beginHandshake("B", b.keys.pubHex()))
        assertNull(a.mgr.beginHandshake("B", b.keys.pubHex()))
    }

    @Test
    fun rejectsMalformedHandshakeInput() {
        val a = Party("A")
        val b = Party("B")
        assertNull(a.mgr.beginHandshake("B", "zz"))
        assertNull(b.mgr.acceptInit("A", a.keys.pubHex(), "garbage"))
        assertNull(b.mgr.acceptInit("A", a.keys.pubHex(), "0011223344556677|abcd"))
        assertFalse(a.mgr.completeHandshake("B", b.keys.pubHex(), "x|y|z"))
    }

    @Test
    fun sessionStateSurvivesRestart() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        val k0 = a.mgr.nextSendKey("B")!!
        assertNotNull(b.mgr.recvKey("A", k0.sid, k0.n))

        // إعادة تشغيل b: استعادة من الكتلة المحفوظة
        val b2 = DirectSessionManager(b.keys)
        b2.restore(b.blob)
        assertTrue(b2.hasSession("A"))
        val k1 = a.mgr.nextSendKey("B")!!
        assertArrayEquals(k1.key, b2.recvKey("A", k1.sid, k1.n))
        // الرسالة 0 استُهلكت قبل إعادة التشغيل ولا يمكن فكّها مجدداً
        assertNull(b2.recvKey("A", k0.sid, k0.n))
    }

    @Test
    fun forgetPeerRemovesSessions() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        a.mgr.forgetPeer("B")
        assertFalse(a.mgr.hasSession("B"))
        assertNull(a.mgr.nextSendKey("B"))
    }

    @Test
    fun rotationIsRequestedAfterManyMessages() {
        val a = Party("A")
        val b = Party("B")
        handshake(a, b)
        assertFalse(a.mgr.needsRotation("B"))
        repeat(DirectSessionManager.ROTATE_AFTER_MESSAGES) { a.mgr.nextSendKey("B") }
        assertTrue(a.mgr.needsRotation("B"))
    }

    @Test
    fun simultaneousInitsBothWork() {
        val a = Party("A")
        val b = Party("B")
        val initA = a.mgr.beginHandshake("B", b.keys.pubHex())!!
        val initB = b.mgr.beginHandshake("A", a.keys.pubHex())!!
        val respB = b.mgr.acceptInit("A", a.keys.pubHex(), initA)!!
        val respA = a.mgr.acceptInit("B", b.keys.pubHex(), initB)!!
        assertTrue(a.mgr.completeHandshake("B", b.keys.pubHex(), respB))
        assertTrue(b.mgr.completeHandshake("A", a.keys.pubHex(), respA))

        // كل طرف يرسل بأحدث جلسة لديه، والطرف الآخر يعرف الجلستين
        val ka = a.mgr.nextSendKey("B")!!
        val kb = b.mgr.nextSendKey("A")!!
        assertArrayEquals(ka.key, b.mgr.recvKey("A", ka.sid, ka.n))
        assertArrayEquals(kb.key, a.mgr.recvKey("B", kb.sid, kb.n))
    }
}
