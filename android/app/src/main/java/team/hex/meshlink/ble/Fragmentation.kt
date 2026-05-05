package team.hex.meshlink.ble

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Splits a logical mesh frame across BLE GATT writes. BLE MTU defaults to 23
 * bytes (20 usable for ATT_WRITE) and can negotiate up to 517. We never trust
 * the negotiated MTU; we just chunk to whatever the link reports and rebuild
 * on the other side using a small frame header.
 *
 * Wire format per chunk:
 *   byte[0..1]  msgId (uint16, randomly chosen by sender)
 *   byte[2..3]  index (uint16)
 *   byte[4..5]  total (uint16)
 *   byte[6..]   payload bytes
 *
 * Header is 6 bytes. Pick chunkPayloadSize = effectiveMtu - 6.
 */
object Fragmentation {
    const val HEADER_BYTES = 6
    private const val MAX_FRAGMENT_AGE_MS = 30_000L

    fun split(payload: ByteArray, chunkPayloadSize: Int, msgId: Int): List<ByteArray> {
        require(chunkPayloadSize > 0)
        val total = ((payload.size + chunkPayloadSize - 1) / chunkPayloadSize).coerceAtLeast(1)
        require(total <= 0xFFFF) { "frame too large for 16-bit total" }
        return List(total) { i ->
            val start = i * chunkPayloadSize
            val end = minOf(payload.size, start + chunkPayloadSize)
            val out = ByteArray(HEADER_BYTES + (end - start))
            val bb = ByteBuffer.wrap(out)
            bb.putShort(msgId.toShort())
            bb.putShort(i.toShort())
            bb.putShort(total.toShort())
            System.arraycopy(payload, start, out, HEADER_BYTES, end - start)
            out
        }
    }
}

/** Per-peer reassembly buffer. Not thread-safe; callers must serialize. */
class Reassembler {
    private data class Pending(
        val total: Int,
        val parts: Array<ByteArray?>,
        val createdAtMs: Long,
        var received: Int = 0,
    )

    private val byMsgId = ConcurrentHashMap<Int, Pending>()

    /** Returns a complete frame if this chunk finished one, else null. */
    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.size < Fragmentation.HEADER_BYTES) return null
        val bb = ByteBuffer.wrap(chunk)
        val msgId = bb.short.toInt() and 0xFFFF
        val index = bb.short.toInt() and 0xFFFF
        val total = bb.short.toInt() and 0xFFFF
        if (total == 0 || index >= total) return null

        val payload = chunk.copyOfRange(Fragmentation.HEADER_BYTES, chunk.size)

        // Single-chunk fast path.
        if (total == 1 && index == 0) return payload

        val pending = byMsgId.getOrPut(msgId) {
            Pending(total, arrayOfNulls(total), System.currentTimeMillis())
        }
        if (pending.total != total) {
            // Conflicting reassembly state: drop and restart with this chunk.
            byMsgId.remove(msgId)
            return feed(chunk)
        }
        if (pending.parts[index] == null) {
            pending.parts[index] = payload
            pending.received++
        }
        if (pending.received == pending.total) {
            byMsgId.remove(msgId)
            val totalSize = pending.parts.sumOf { it!!.size }
            val out = ByteArray(totalSize)
            var off = 0
            for (p in pending.parts) {
                System.arraycopy(p!!, 0, out, off, p.size)
                off += p.size
            }
            return out
        }
        sweepStale()
        return null
    }

    private fun sweepStale() {
        val now = System.currentTimeMillis()
        val it = byMsgId.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAtMs > 30_000L) it.remove()
        }
    }
}
