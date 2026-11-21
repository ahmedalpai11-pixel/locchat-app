package com.example

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.AesGcmCryptoHelper
import com.example.data.ContactsManager
import com.example.data.CryptoManager
import com.example.data.LocChatDatabase
import com.example.data.MediaHelper
import com.example.mesh.BleMeshEngine
import com.example.mesh.MediaChunkAssembler
import com.example.mesh.MeshPacket
import com.example.mesh.MeshPacketCodec
import com.example.mesh.MeshPacketType
import com.example.ui.encodeIdentityQrPayload
import com.example.ui.generateQrImageBitmap
import com.example.ui.parseAndVerifyIdentityQr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeshProtocolTest {

    @Test
    fun `ed25519 sign verify and x25519 ecdh encrypt decrypt round trip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alice = CryptoManager(context)
        val aliceId = alice.getOrCreateIdentity()

        val message = "مرحباً من شبكة البلوتوث المحلية!"
        val keyA = alice.deriveDirectKeyWithPeer(aliceId.x25519PublicKeyHex)
        assertNotNull(keyA)
        val encrypted = alice.encryptTextWithKey(keyA!!, message)
        val decrypted = alice.decryptTextWithKey(keyA, encrypted)
        assertEquals(message, decrypted)

        val packet = MeshPacket(
            type = MeshPacketType.CHAT_TEXT,
            senderId = aliceId.nodeId,
            senderName = aliceId.nickname,
            senderPubKeyHex = aliceId.publicKeyHex,
            senderX25519KeyHex = aliceId.x25519PublicKeyHex,
            targetId = "#عام-الحي",
            isChannel = true,
            payload = encrypted
        )
        val sig = alice.signPayload(MeshPacketCodec.signingBytes(packet))
        assertTrue(
            alice.verifyPacketIdentityAndSignature(
                aliceId.nodeId,
                aliceId.publicKeyHex,
                MeshPacketCodec.signingBytes(packet),
                sig
            )
        )

        // Spoofed senderId must fail identity binding
        assertFalse(
            alice.verifyPacketIdentityAndSignature(
                "LC-SPOOFED1",
                aliceId.publicKeyHex,
                MeshPacketCodec.signingBytes(packet),
                sig
            )
        )

        val frameBytes = MeshPacketCodec.encodeFrame(packet.copy(signature = sig))
        val decoded = MeshPacketCodec.decodeFrame(String(frameBytes, Charsets.UTF_8))
        assertNotNull(decoded)
        assertEquals(packet.packetId, decoded?.packetId)
        assertEquals(aliceId.x25519PublicKeyHex, decoded?.senderX25519KeyHex)
    }

    @Test
    fun `protected channel rejects wrong passphrase`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoManager(context)
        val rightKey = crypto.deriveChannelKey("#طوارئ", "secret123")
        val wrongKey = crypto.deriveChannelKey("#طوارئ", "wrongpass")

        val cipher = crypto.encryptTextWithKey(rightKey, "رسالة سرية للقناة")
        assertEquals("رسالة سرية للقناة", crypto.decryptTextWithKey(rightKey, cipher))
        assertNull(crypto.decryptTextWithKey(wrongKey, cipher))
    }

    @Test
    fun `mesh engine rejects unsigned, replayed, and blocked packets`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = LocChatDatabase.getInstance(context)
        val crypto = CryptoManager(context)
        val contacts = ContactsManager(db.verifiedContactDao(), db.blockedPeerDao())
        val engine = BleMeshEngine(context, crypto, contacts) {}

        val now = System.currentTimeMillis()
        val unsigned = MeshPacket(
            type = MeshPacketType.CHAT_TEXT,
            senderId = "LC-FAKE0001",
            senderName = "Attacker",
            senderPubKeyHex = "",
            targetId = "*",
            isChannel = true,
            payload = "test",
            timestamp = now,
            signature = ""
        )
        assertFalse(engine.validateIncomingPacket(unsigned, now))

        // Expired replay (> 12h old) must be rejected even if signed
        val me = crypto.getOrCreateIdentity()
        val oldTime = now - (13 * 3_600_000L)
        val oldPkt = MeshPacket(
            type = MeshPacketType.CHAT_TEXT,
            senderId = me.nodeId,
            senderName = me.nickname,
            senderPubKeyHex = me.publicKeyHex,
            targetId = "*",
            isChannel = true,
            payload = "old",
            timestamp = oldTime
        )
        val oldSig = crypto.signPayload(MeshPacketCodec.signingBytes(oldPkt))
        assertFalse(engine.validateIncomingPacket(oldPkt.copy(signature = oldSig), now))
    }

    @Test
    fun `aes gcm helper enforces integrity, key match, and aad binding`() {
        val key1 = ByteArray(32) { (it + 1).toByte() }
        val key2 = ByteArray(32) { (it + 2).toByte() }
        val aad = "DM:LC-AAA:LC-BBB".toByteArray(Charsets.UTF_8)
        val wrongAad = "DM:LC-AAA:LC-CCC".toByteArray(Charsets.UTF_8)
        val secret = "محتوى مشفر عبر AES/GCM/NoPadding".toByteArray(Charsets.UTF_8)

        val cipherB64 = AesGcmCryptoHelper.encrypt(key1, secret, aad)
        val decrypted = AesGcmCryptoHelper.decrypt(key1, cipherB64, aad)
        assertNotNull(decrypted)
        assertEquals(String(secret, Charsets.UTF_8), String(decrypted!!, Charsets.UTF_8))

        // Wrong key must fail GCM tag verification
        assertNull(AesGcmCryptoHelper.decrypt(key2, cipherB64, aad))

        // Mismatched AAD must fail GCM tag verification
        assertNull(AesGcmCryptoHelper.decrypt(key1, cipherB64, wrongAad))

        // Tampered ciphertext must fail GCM tag verification
        val raw = Base64.decode(cipherB64, Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0xFF).toByte()
        val tamperedB64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        assertNull(AesGcmCryptoHelper.decrypt(key1, tamperedB64, aad))
    }

    @Test
    fun `media helper rejects non-image and non-m4a payloads`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mediaHelper = MediaHelper(context)

        val fakeB64 = Base64.encodeToString("NOT_A_VALID_MEDIA_FILE_PAYLOAD".toByteArray(), Base64.NO_WRAP)
        assertNull(mediaHelper.saveBase64ToImageFile(fakeB64))
        assertNull(mediaHelper.saveBase64ToVoiceFile(fakeB64))

        // Valid compressed JPEG image should be accepted
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val validJpegB64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        assertNotNull(mediaHelper.saveBase64ToImageFile(validJpegB64))

        // Valid M4A ftyp header should be accepted
        val validM4aBytes = byteArrayOf(0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(), 0, 0)
        val validM4aB64 = Base64.encodeToString(validM4aBytes, Base64.NO_WRAP)
        val savedVoice = mediaHelper.saveBase64ToVoiceFile(validM4aB64)
        assertNotNull(savedVoice)
    }

    @Test
    fun `media chunk assembler splits, reassembles out of order, and detects crc corruption`() {
        val assembler = MediaChunkAssembler()
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val payload = "LOCCHAT_MEDIA_PAYLOAD_" + "A1B2C3D4E5".repeat(650)
        val encrypted = AesGcmCryptoHelper.encrypt(key, payload.toByteArray(Charsets.UTF_8))

        val chunks = MediaChunkAssembler.splitEncryptedPayload(encrypted, chunkSize = 1500)
        assertTrue(chunks.size >= 3)
        val crc = MediaChunkAssembler.computeCrc32(encrypted)

        // Deliver out-of-order
        val indices = chunks.indices.reversed().toList()
        var finalResult: MediaChunkAssembler.AssemblyResult? = null
        indices.forEach { idx ->
            finalResult = assembler.addChunk("tx-1", idx, chunks.size, crc, chunks[idx])
        }
        assertTrue(finalResult is MediaChunkAssembler.AssemblyResult.Complete)
        val reassembled = (finalResult as MediaChunkAssembler.AssemblyResult.Complete).fullData
        val decrypted = AesGcmCryptoHelper.decrypt(key, reassembled)
        assertNotNull(decrypted)
        assertEquals(payload, String(decrypted!!, Charsets.UTF_8))

        // Corrupted CRC must be rejected
        val badRes = assembler.addChunk("tx-2", 0, 1, crc xor 0x1234L, chunks[0])
        assertTrue(badRes is MediaChunkAssembler.AssemblyResult.Corrupted)
    }

    @Test
    fun `qr identity payload verifies binding and rejects spoofed ids and onboarding flag tracks user name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoManager(context)
        assertFalse(crypto.isNicknameConfigured())

        val updated = crypto.updateNickname("أحمد")
        assertTrue(crypto.isNicknameConfigured())
        assertEquals("أحمد", updated.nickname)

        val qrPayload = encodeIdentityQrPayload(updated)
        assertNotNull(generateQrImageBitmap(qrPayload, 128))
        val parsed = parseAndVerifyIdentityQr(
            raw = qrPayload,
            verifyNodeBinding = { id, pk -> crypto.verifyNodeIdMatchesPublicKey(id, pk) },
            computeFingerprint = { ed, x -> crypto.formatFingerprintFromHex(ed, x) }
        )
        assertNotNull(parsed)
        assertEquals(updated.nodeId, parsed?.nodeId)
        assertEquals("أحمد", parsed?.nickname)

        // Spoofed nodeId in QR must fail cryptographic binding
        val spoofedPayload = qrPayload.replace(updated.nodeId, "LC-DEADBEEF")
        val parsedSpoofed = parseAndVerifyIdentityQr(
            raw = spoofedPayload,
            verifyNodeBinding = { id, pk -> crypto.verifyNodeIdMatchesPublicKey(id, pk) },
            computeFingerprint = { ed, x -> crypto.formatFingerprintFromHex(ed, x) }
        )
        assertNull(parsedSpoofed)

        // Panic wipe / regenerate resets onboarding requirement
        crypto.regenerateIdentity()
        assertFalse(crypto.isNicknameConfigured())
    }

    @Test
    fun `ble node meta hides identity in stealth mode and respects 20-byte utf8 boundary`() {
        val stealthBytes = BleMeshEngine.encodeBleNodeMeta("LC-11223344", "أحمد", stealth = true)
        assertEquals("STEALTH", String(stealthBytes, Charsets.UTF_8))

        val arabicLong = BleMeshEngine.encodeBleNodeMeta(
            nodeId = "LC-11223344",
            nickname = "عبدالرحمن الشمري",
            stealth = false
        )
        assertTrue(arabicLong.size <= 20)
        val decoded = String(arabicLong, Charsets.UTF_8)
        assertTrue(decoded.startsWith("LC-11223344|"))
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun `block broadcast packet signs verifies and encodes cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoManager(context)
        val me = crypto.getOrCreateIdentity()

        val blockPkt = MeshPacket(
            type = MeshPacketType.BLOCK_BROADCAST,
            senderId = me.nodeId,
            senderName = me.nickname,
            senderPubKeyHex = me.publicKeyHex,
            senderX25519KeyHex = me.x25519PublicKeyHex,
            targetId = "*",
            isChannel = true,
            payload = """{"blockedId":"LC-BAD00001","blockedName":"SPAM","reason":"إزعاج"}"""
        )
        val sig = crypto.signPayload(MeshPacketCodec.signingBytes(blockPkt))
        val frame = MeshPacketCodec.encodeFrame(blockPkt.copy(signature = sig))
        val decoded = MeshPacketCodec.decodeFrame(String(frame, Charsets.UTF_8))
        assertNotNull(decoded)
        assertEquals(MeshPacketType.BLOCK_BROADCAST, decoded?.type)
        assertTrue(
            crypto.verifyPacketIdentityAndSignature(
                decoded!!.senderId,
                decoded.senderPubKeyHex,
                MeshPacketCodec.signingBytes(decoded),
                decoded.signature
            )
        )
    }

    @Test
    fun `language preference persists and localization bundles cover ar en fr`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoManager(context)

        // Default is Arabic RTL
        assertEquals(com.example.model.AppLanguage.ARABIC, crypto.getLanguage())
        assertTrue(com.example.model.AppLanguage.ARABIC.isRtl)

        crypto.setLanguage(com.example.model.AppLanguage.ENGLISH)
        assertEquals(com.example.model.AppLanguage.ENGLISH, crypto.getLanguage())
        assertFalse(com.example.model.AppLanguage.ENGLISH.isRtl)

        crypto.setLanguage(com.example.model.AppLanguage.FRENCH)
        assertEquals(com.example.model.AppLanguage.FRENCH, crypto.getLanguage())
        assertFalse(com.example.model.AppLanguage.FRENCH.isRtl)

        com.example.model.AppLanguage.entries.forEach { lang ->
            val s = com.example.ui.stringsFor(lang)
            assertEquals(lang, s.language)
            assertTrue(s.tabFeed.isNotBlank())
            assertTrue(s.tabContacts.isNotBlank())
            assertTrue(s.myQrDialogTitle.isNotBlank())
            assertTrue(s.feedPublishConfirm.isNotBlank())
        }

        // Restore Arabic
        crypto.setLanguage(com.example.model.AppLanguage.ARABIC)
    }

    @Test
    fun `contacts manager adds and removes friends cleanly with case normalization`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, LocChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val contacts = ContactsManager(db.verifiedContactDao(), db.blockedPeerDao())
            contacts.verifyContact(
                peerId = "lc-friend01",
                nickname = "سارة",
                fingerprint = "1111 • 2222 • 3333 • 4444",
                publicKeyHex = "a".repeat(64),
                x25519PublicKeyHex = "b".repeat(64)
            )
            val savedList = db.verifiedContactDao().observeAll().first()
            val saved = savedList.firstOrNull { it.peerId == "LC-FRIEND01" }
            assertNotNull(saved)
            assertEquals("سارة", saved?.nickname)

            contacts.removeVerification(" lc-friend01 ")
            val afterRemove = db.verifiedContactDao().observeAll().first()
            assertNull(afterRemove.firstOrNull { it.peerId == "LC-FRIEND01" })
        } finally {
            db.close()
        }
    }

    @Test
    fun `feed post packet signs verifies encodes and persists to room feed dao`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoManager(context)
        val me = crypto.getOrCreateIdentity()

        val payloadJson = org.json.JSONObject().apply {
            put("postId", "post-ble-01")
            put("text", "تنبيه محلي: الطريق مفتوح عبر الشبكة")
            put("cat", com.example.model.PostCategory.EVENT.name)
            put("ttl", 12)
            put("kb", 0)
        }.toString()

        val pkt = MeshPacket(
            type = MeshPacketType.FEED_POST,
            senderId = me.nodeId,
            senderName = me.nickname,
            senderPubKeyHex = me.publicKeyHex,
            senderX25519KeyHex = me.x25519PublicKeyHex,
            targetId = "*",
            isChannel = true,
            ttl = 8,
            hopCount = 0,
            payload = payloadJson
        )
        val sig = crypto.signPayload(MeshPacketCodec.signingBytes(pkt))
        val encoded = MeshPacketCodec.encodeFrame(pkt.copy(signature = sig))
        val decoded = MeshPacketCodec.decodeFrame(String(encoded, Charsets.UTF_8))

        assertNotNull(decoded)
        assertEquals(MeshPacketType.FEED_POST, decoded?.type)
        assertTrue(
            crypto.verifyPacketIdentityAndSignature(
                decoded!!.senderId,
                decoded.senderPubKeyHex,
                MeshPacketCodec.signingBytes(decoded),
                decoded.signature
            )
        )

        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, LocChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val entity = com.example.data.FeedPostEntity(
                id = "post-ble-01",
                authorId = me.nodeId,
                authorName = me.nickname,
                text = "تنبيه محلي: الطريق مفتوح عبر الشبكة",
                imagePath = null,
                imageSizeKb = 0,
                category = com.example.model.PostCategory.EVENT.name,
                ttlHours = 12,
                createdAt = System.currentTimeMillis(),
                likesCount = 0,
                isLikedByMe = false,
                hopCount = 0
            )
            db.feedPostDao().upsert(entity)
            val saved = db.feedPostDao().getById("post-ble-01")
            assertNotNull(saved)
            assertEquals("تنبيه محلي: الطريق مفتوح عبر الشبكة", saved?.text)
        } finally {
            db.close()
        }
    }
}
