package team.hex.meshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.hex.meshlink.pairing.QrEncoder

class QrEncoderTest {

    @Test fun `version 1 size is 21 modules`() {
        val matrix = QrEncoder.encode("hi")
        assertEquals(21, matrix.w)
        assertEquals(21, matrix.h)
    }

    @Test fun `finder pattern is in place`() {
        val matrix = QrEncoder.encode("MeshLink pairing")
        // Top-left finder: outer 7x7 frame should have all-on edges.
        for (i in 0..6) {
            assertTrue("top edge", matrix[i, 0])
            assertTrue("bottom edge", matrix[i, 6])
            assertTrue("left edge", matrix[0, i])
            assertTrue("right edge", matrix[6, i])
        }
        // Inner 3x3 black square.
        for (y in 2..4) for (x in 2..4) assertTrue("inner $x,$y", matrix[x, y])
    }

    @Test fun `larger payload picks bigger version`() {
        val payload = "a".repeat(150)
        val matrix = QrEncoder.encode(payload)
        // v6 = 41 modules, v7 = 45.
        assertTrue("matrix size grew with payload", matrix.w >= 41)
    }
}
