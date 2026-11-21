package com.example.mesh

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class MeshPacketType {
    PEER_HELLO,
    NODE_ANNOUNCE,
    CHAT_TEXT,
    CHAT_MEDIA,
    MEDIA_CHUNK,
    REACTION,
    DELIVERY_ACK,
    FEED_POST,
    FEED_COMMENT,
    BLOCK_BROADCAST
}

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val type: MeshPacketType,
    val senderId: String,
    val senderName: String,
    val senderPubKeyHex: String,
    val senderX25519KeyHex: String = "",
    val targetId: String,
    val isChannel: Boolean,
    val ttl: Int = 7,
    val hopCount: Int = 0,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val signature: String = ""
)

object MeshPacketCodec {

    private const val FRAME_MAGIC = "LM1:"

    fun encodeFrame(packet: MeshPacket): ByteArray {
        val obj = JSONObject().apply {
            put("id", packet.packetId)
            put("t", packet.type.name)
            put("sid", packet.senderId)
            put("sn", packet.senderName)
            put("spk", packet.senderPubKeyHex)
            if (packet.senderX25519KeyHex.isNotEmpty()) put("sxk", packet.senderX25519KeyHex)
            put("tid", packet.targetId)
            put("ch", packet.isChannel)
            put("ttl", packet.ttl)
            put("hop", packet.hopCount)
            put("pl", packet.payload)
            put("ts", packet.timestamp)
            put("sig", packet.signature)
        }
        val jsonBytes = obj.toString().toByteArray(Charsets.UTF_8)
        val bodyB64 = if (jsonBytes.size > 180) {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(jsonBytes) }
            "Z" + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } else {
            "R" + android.util.Base64.encodeToString(jsonBytes, android.util.Base64.NO_WRAP)
        }
        return "$FRAME_MAGIC$bodyB64\n".toByteArray(Charsets.UTF_8)
    }

    fun decodeFrame(rawFrame: String): MeshPacket? {
        return try {
            val trimmed = rawFrame.trim()
            if (trimmed.isEmpty()) return null
            val jsonStr = if (trimmed.startsWith(FRAME_MAGIC)) {
                val body = trimmed.removePrefix(FRAME_MAGIC)
                if (body.length < 2) return null
                val flag = body[0]
                val decoded = android.util.Base64.decode(body.substring(1), android.util.Base64.NO_WRAP)
                val rawBytes = if (flag == 'Z') {
                    GZIPInputStream(ByteArrayInputStream(decoded)).use { it.readBytes() }
                } else {
                    decoded
                }
                String(rawBytes, Charsets.UTF_8)
            } else {
                trimmed
            }

            val obj = JSONObject(jsonStr)
            MeshPacket(
                packetId = obj.getString("id"),
                type = MeshPacketType.valueOf(obj.getString("t")),
                senderId = obj.getString("sid"),
                senderName = obj.optString("sn", "عقدة قريبة"),
                senderPubKeyHex = obj.optString("spk", ""),
                senderX25519KeyHex = obj.optString("sxk", ""),
                targetId = obj.optString("tid", "*"),
                isChannel = obj.optBoolean("ch", false),
                ttl = obj.optInt("ttl", 6),
                hopCount = obj.optInt("hop", 0),
                payload = obj.optString("pl", ""),
                timestamp = obj.optLong("ts", System.currentTimeMillis()),
                signature = obj.optString("sig", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pure JVM-safe encoding/decoding helper for unit tests without android.util.Base64 dependency.
     */
    fun signingBytes(packet: MeshPacket): ByteArray {
        return "${packet.packetId}|${packet.type.name}|${packet.senderId}|${packet.senderX25519KeyHex}|${packet.targetId}|${packet.payload}|${packet.timestamp}"
            .toByteArray(Charsets.UTF_8)
    }
}
