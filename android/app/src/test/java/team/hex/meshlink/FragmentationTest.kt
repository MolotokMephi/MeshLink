package team.hex.meshlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.hex.meshlink.ble.Fragmentation
import team.hex.meshlink.ble.Reassembler
import kotlin.random.Random

class FragmentationTest {

    @Test fun `single chunk fast path`() {
        val payload = ByteArray(10) { it.toByte() }
        val chunks = Fragmentation.split(payload, chunkPayloadSize = 100, msgId = 7)
        assertEquals(1, chunks.size)
        val rasm = Reassembler()
        val full = rasm.feed(chunks[0])
        assertArrayEquals(payload, full)
    }

    @Test fun `multi chunk roundtrip`() {
        val payload = Random(42).nextBytes(2049)
        val chunks = Fragmentation.split(payload, chunkPayloadSize = 200, msgId = 0xABCD)
        val rasm = Reassembler()
        var assembled: ByteArray? = null
        for ((i, c) in chunks.withIndex()) {
            val out = rasm.feed(c)
            if (i < chunks.lastIndex) assertNull("partial assembly leaked at $i", out)
            else assembled = out
        }
        assertArrayEquals(payload, assembled)
    }

    @Test fun `out of order chunks reassemble`() {
        val payload = Random(1).nextBytes(1024)
        val chunks = Fragmentation.split(payload, chunkPayloadSize = 64, msgId = 1).toMutableList().apply { reverse() }
        val rasm = Reassembler()
        var out: ByteArray? = null
        for (c in chunks) {
            val r = rasm.feed(c)
            if (r != null) out = r
        }
        assertArrayEquals(payload, out)
    }

    @Test fun `garbage chunk is dropped`() {
        val rasm = Reassembler()
        // Header too short
        assertNull(rasm.feed(ByteArray(2)))
        // Index >= total
        val bad = ByteArray(Fragmentation.HEADER_BYTES + 1)
        bad[0] = 0; bad[1] = 1
        bad[2] = 0; bad[3] = 5     // index 5
        bad[4] = 0; bad[5] = 3     // total 3
        assertNull(rasm.feed(bad))
    }
}
