package com.example.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

enum class MeshPacketType {
    // ملاحظة: الترتيب جزء من البروتوكول السلكي (يُرسل كـ ordinal). أضف الأنواع الجديدة في النهاية فقط.
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

/**
 * بروتوكول سلكي ثنائي (على طريقة bitchat):
 *
 *  [ver:1][type:1][ttl:1][hop:1][flags:1][timestamp:8][packetId:16]
 *  [senderId][target][nameLen:1][name][pubKey:32?][x25519:32?][payloadLen:4][payload][signature:64?]
 *
 *  - معرّفات العقد 16 بايت خام (kind=1) بدل نص.
 *  - الحمولة تُضغط بـ Deflate عندما يكون ذلك أصغر.
 *  - التوقيع Ed25519 خام 64 بايت.
 *  - فوق GATT كل حزمة تُسبق بطول 2 بايت (انظر [StreamFrameBuffer]) لأن الاتصال مستمر ومتدفق.
 */
object MeshPacketCodec {

    const val VERSION = 1
    const val MAX_PACKET_BYTES = 60_000
    private const val MAX_INFLATED_BYTES = 256 * 1024

    private const val F_CHANNEL = 0x01
    private const val F_PUBKEY = 0x02
    private const val F_XKEY = 0x04
    private const val F_SIG = 0x08
    private const val F_COMPRESSED = 0x10

    private const val ID_BROADCAST = 0
    private const val ID_NODE = 1
    private const val ID_TEXT = 2

    private val NODE_ID_REGEX = Regex("^LC-[0-9A-F]{32}$")

    /** يرجع بايتات الحزمة الخام (بدون بادئة الطول). قد يرمي استثناء إن كانت الحقول غير صالحة. */
    fun encodeFrame(packet: MeshPacket): ByteArray {
        var payloadBytes = packet.payload.toByteArray(Charsets.UTF_8)
        var flags = 0
        if (packet.isChannel) flags = flags or F_CHANNEL

        val pub = hexToBytesOrNull(packet.senderPubKeyHex, 32)
        val xk = hexToBytesOrNull(packet.senderX25519KeyHex, 32)
        val sig = decodeB64OrNull(packet.signature)?.takeIf { it.size == 64 }
        if (pub != null) flags = flags or F_PUBKEY
        if (xk != null) flags = flags or F_XKEY
        if (sig != null) flags = flags or F_SIG

        if (payloadBytes.size > 96) {
            val z = deflate(payloadBytes)
            if (z.size < payloadBytes.size) {
                payloadBytes = z
                flags = flags or F_COMPRESSED
            }
        }

        val bos = ByteArrayOutputStream(payloadBytes.size + 192)
        val out = DataOutputStream(bos)
        out.writeByte(VERSION)
        out.writeByte(packet.type.ordinal)
        out.writeByte(packet.ttl.coerceIn(0, 255))
        out.writeByte(packet.hopCount.coerceIn(0, 255))
        out.writeByte(flags)
        out.writeLong(packet.timestamp)

        val uuid = try {
            UUID.fromString(packet.packetId)
        } catch (_: Exception) {
            UUID.nameUUIDFromBytes(packet.packetId.toByteArray(Charsets.UTF_8))
        }
        out.writeLong(uuid.mostSignificantBits)
        out.writeLong(uuid.leastSignificantBits)

        writeId(out, packet.senderId)
        writeId(out, packet.targetId)

        val nameBytes = packet.senderName.take(60).toByteArray(Charsets.UTF_8)
        out.writeByte(nameBytes.size.coerceAtMost(255))
        out.write(nameBytes, 0, nameBytes.size.coerceAtMost(255))

        if (pub != null) out.write(pub)
        if (xk != null) out.write(xk)
        out.writeInt(payloadBytes.size)
        out.write(payloadBytes)
        if (sig != null) out.write(sig)
        out.flush()

        val result = bos.toByteArray()
        require(result.size <= MAX_PACKET_BYTES) { "packet too large: ${result.size}" }
        return result
    }

    fun decodeFrame(data: ByteArray): MeshPacket? {
        return try {
            if (data.size < 30) return null
            val inp = DataInputStream(ByteArrayInputStream(data))
            if (inp.readUnsignedByte() != VERSION) return null
            val type = MeshPacketType.entries.getOrNull(inp.readUnsignedByte()) ?: return null
            val ttl = inp.readUnsignedByte()
            val hop = inp.readUnsignedByte()
            val flags = inp.readUnsignedByte()
            val ts = inp.readLong()
            val msb = inp.readLong()
            val lsb = inp.readLong()
            val senderId = readId(inp) ?: return null
            val targetId = readId(inp) ?: return null
            val nameLen = inp.readUnsignedByte()
            val nameBytes = ByteArray(nameLen).also { inp.readFully(it) }

            val pub = if (flags and F_PUBKEY != 0) ByteArray(32).also { inp.readFully(it) } else null
            val xk = if (flags and F_XKEY != 0) ByteArray(32).also { inp.readFully(it) } else null
            val payloadLen = inp.readInt()
            if (payloadLen < 0 || payloadLen > inp.available()) return null
            var payloadBytes = ByteArray(payloadLen).also { inp.readFully(it) }
            val sig = if (flags and F_SIG != 0) ByteArray(64).also { inp.readFully(it) } else null

            if (flags and F_COMPRESSED != 0) {
                payloadBytes = inflate(payloadBytes) ?: return null
            }

            MeshPacket(
                packetId = UUID(msb, lsb).toString(),
                type = type,
                senderId = senderId,
                senderName = String(nameBytes, Charsets.UTF_8),
                senderPubKeyHex = pub?.toHex() ?: "",
                senderX25519KeyHex = xk?.toHex() ?: "",
                targetId = targetId,
                isChannel = flags and F_CHANNEL != 0,
                ttl = ttl,
                hopCount = hop,
                payload = String(payloadBytes, Charsets.UTF_8),
                timestamp = ts,
                signature = sig?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    /** حقول التوقيع (لا تشمل ttl/hop لأنها تتغير أثناء الترحيل). */
    fun signingBytes(packet: MeshPacket): ByteArray {
        return ("${packet.packetId}|${packet.type.name}|${packet.senderId}|${packet.senderName}|" +
            "${packet.senderX25519KeyHex}|${packet.targetId}|${packet.isChannel}|${packet.payload}|${packet.timestamp}")
            .toByteArray(Charsets.UTF_8)
    }

    /** يضيف بادئة طول 2 بايت (big-endian) للإرسال عبر تدفق GATT. */
    fun withLengthPrefix(frame: ByteArray): ByteArray {
        require(frame.size in 1..0xFFFF) { "bad frame size ${frame.size}" }
        val out = ByteArray(frame.size + 2)
        out[0] = (frame.size ushr 8).toByte()
        out[1] = frame.size.toByte()
        System.arraycopy(frame, 0, out, 2, frame.size)
        return out
    }

    private fun writeId(out: DataOutputStream, id: String) {
        when {
            id == "*" -> out.writeByte(ID_BROADCAST)
            NODE_ID_REGEX.matches(id) -> {
                out.writeByte(ID_NODE)
                out.write(hexToBytesOrNull(id.substring(3), 16)!!)
            }
            else -> {
                val b = id.toByteArray(Charsets.UTF_8)
                require(b.size <= 255) { "id too long" }
                out.writeByte(ID_TEXT)
                out.writeByte(b.size)
                out.write(b)
            }
        }
    }

    private fun readId(inp: DataInputStream): String? {
        return when (inp.readUnsignedByte()) {
            ID_BROADCAST -> "*"
            ID_NODE -> "LC-" + ByteArray(16).also { inp.readFully(it) }.toHex().uppercase()
            ID_TEXT -> {
                val len = inp.readUnsignedByte()
                String(ByteArray(len).also { inp.readFully(it) }, Charsets.UTF_8)
            }
            else -> null
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            d.setInput(data)
            d.finish()
            val bos = ByteArrayOutputStream(data.size)
            val buf = ByteArray(1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inf = Inflater(true)
        return try {
            inf.setInput(data)
            val bos = ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(2048)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bos.write(buf, 0, n)
                if (bos.size() > MAX_INFLATED_BYTES) return null // حماية من zip-bomb
            }
            if (!inf.finished()) null else bos.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inf.end()
        }
    }

    private fun decodeB64OrNull(s: String): ByteArray? {
        if (s.isBlank()) return null
        return try {
            android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    internal fun hexToBytesOrNull(hex: String, expected: Int): ByteArray? {
        val clean = hex.trim()
        if (clean.length != expected * 2) return null
        return try {
            ByteArray(expected) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * مُجمِّع تدفق: يستقبل قطعًا عشوائية الحجم من GATT ويُخرج إطارات كاملة (بادئة طول 2 بايت).
 * غير آمن للخيوط؛ يجب استدعاؤه تحت synchronized من المستدعي.
 */
class StreamFrameBuffer(private val maxFrame: Int = MeshPacketCodec.MAX_PACKET_BYTES) {
    private var buf = ByteArray(1024)
    private var len = 0

    fun feed(chunk: ByteArray): List<ByteArray> {
        if (len + chunk.size > buf.size) {
            val newSize = maxOf(buf.size * 2, len + chunk.size)
            if (newSize > maxFrame * 2) {
                len = 0 // تدفق تالف/مفرط: أعد الضبط
                return emptyList()
            }
            buf = buf.copyOf(newSize)
        }
        System.arraycopy(chunk, 0, buf, len, chunk.size)
        len += chunk.size

        val frames = ArrayList<ByteArray>(2)
        var pos = 0
        while (len - pos >= 2) {
            val frameLen = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            if (frameLen == 0 || frameLen > maxFrame) {
                len = 0 // فقدنا المزامنة
                return frames
            }
            if (len - pos - 2 < frameLen) break
            frames.add(buf.copyOfRange(pos + 2, pos + 2 + frameLen))
            pos += 2 + frameLen
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
        return frames
    }

    fun reset() {
        len = 0
    }
}
