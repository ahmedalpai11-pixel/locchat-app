package com.example.mesh

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Splits large encrypted media payloads into bounded BLE-friendly chunks with CRC32
 * integrity verification and reassembles out-of-order multi-hop chunks safely.
 */
class MediaChunkAssembler {

    sealed class AssemblyResult {
        data class InProgress(val progress: Float) : AssemblyResult()
        data class Complete(val fullData: String) : AssemblyResult()
        data object Corrupted : AssemblyResult()
        data object Invalid : AssemblyResult()
    }

    private data class BufferEntry(
        val total: Int,
        val expectedCrc: Long,
        val chunks: MutableMap<Int, String> = mutableMapOf(),
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val buffers = ConcurrentHashMap<String, BufferEntry>()

    @Synchronized
    fun addChunk(
        transferId: String,
        index: Int,
        total: Int,
        expectedCrc: Long,
        chunk: String
    ): AssemblyResult {
        if (transferId.isBlank() || total !in 1..MAX_TOTAL_CHUNKS || index !in 0 until total || chunk.isEmpty()) {
            return AssemblyResult.Invalid
        }
        pruneStale()

        val entry = buffers.getOrPut(transferId) {
            if (buffers.size >= MAX_ACTIVE_TRANSFERS) {
                val oldestKey = buffers.entries.minByOrNull { it.value.createdAtMs }?.key
                if (oldestKey != null) buffers.remove(oldestKey)
            }
            BufferEntry(total = total, expectedCrc = expectedCrc)
        }

        if (entry.total != total || entry.expectedCrc != expectedCrc) {
            buffers.remove(transferId)
            return AssemblyResult.Invalid
        }

        entry.chunks[index] = chunk
        if (entry.chunks.size < total) {
            return AssemblyResult.InProgress(entry.chunks.size.toFloat() / total.toFloat())
        }

        val combined = buildString {
            for (i in 0 until total) {
                val part = entry.chunks[i] ?: return AssemblyResult.Invalid
                append(part)
            }
        }
        buffers.remove(transferId)

        return if (computeCrc32(combined) == expectedCrc) {
            AssemblyResult.Complete(combined)
        } else {
            AssemblyResult.Corrupted
        }
    }

    @Synchronized
    fun clear() {
        buffers.clear()
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - TRANSFER_TIMEOUT_MS
        val iter = buffers.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.createdAtMs < cutoff) {
                iter.remove()
            }
        }
    }

    companion object {
        const val CHUNK_SIZE_CHARS = 2000
        const val MAX_TOTAL_CHUNKS = 128
        private const val MAX_ACTIVE_TRANSFERS = 32
        private const val TRANSFER_TIMEOUT_MS = 5 * 60_000L

        fun computeCrc32(data: String): Long {
            val crc = CRC32()
            val bytes = data.toByteArray(Charsets.UTF_8)
            crc.update(bytes, 0, bytes.size)
            return crc.value
        }

        fun splitEncryptedPayload(data: String, chunkSize: Int = CHUNK_SIZE_CHARS): List<String> {
            if (data.isEmpty()) return emptyList()
            return data.chunked(chunkSize)
        }
    }
}
