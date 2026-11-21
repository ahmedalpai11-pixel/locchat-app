package com.example

import com.example.mesh.MeshPacket
import com.example.mesh.MeshPacketCodec
import com.example.mesh.MeshPacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPacketSigningTest {

    private fun packet() = MeshPacket(
        type = MeshPacketType.CHAT_TEXT,
        senderId = "LC-11223344",
        senderName = "مستخدم",
        senderPubKeyHex = "a".repeat(64),
        senderX25519KeyHex = "b".repeat(64),
        targetId = "#عام-الحي",
        isChannel = true,
        ttl = 5,
        hopCount = 1,
        payload = "{\"body\":\"message\"}",
        timestamp = 1_700_000_000_000L
    )

    @Test
    fun `signature input binds immutable identity and routing fields`() {
        val original = packet()
        val bytes = MeshPacketCodec.signingBytes(original)

        assertNotEquals(bytes.toList(), MeshPacketCodec.signingBytes(original.copy(senderName = "مهاجم")).toList())
        assertNotEquals(bytes.toList(), MeshPacketCodec.signingBytes(original.copy(senderPubKeyHex = "c".repeat(64))).toList())
        assertNotEquals(bytes.toList(), MeshPacketCodec.signingBytes(original.copy(isChannel = false)).toList())
        assertNotEquals(bytes.toList(), MeshPacketCodec.signingBytes(original.copy(targetId = "LC-99999999")).toList())
        assertNotEquals(bytes.toList(), MeshPacketCodec.signingBytes(original.copy(payload = "tampered")).toList())
    }

    @Test
    fun `mesh forwarding mutable ttl and hop count do not invalidate origin signature`() {
        val original = packet()
        val forwarded = original.copy(ttl = original.ttl - 1, hopCount = original.hopCount + 1)
        assertArrayEquals(MeshPacketCodec.signingBytes(original), MeshPacketCodec.signingBytes(forwarded))
    }

    @Test
    fun `canonical encoding distinguishes delimiter-like field values`() {
        val original = packet().copy(senderName = "a|b", targetId = "c")
        val changed = original.copy(senderName = "a", targetId = "b|c")
        assertFalse(MeshPacketCodec.signingBytes(original).contentEquals(MeshPacketCodec.signingBytes(changed)))
        assertTrue(MeshPacketCodec.signingBytes(original).isNotEmpty())
    }
}
