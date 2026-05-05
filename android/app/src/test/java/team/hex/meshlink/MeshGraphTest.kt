package team.hex.meshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.hex.meshlink.mesh.MeshGraph

class MeshGraphTest {

    @Test fun `direct neighbour is one hop`() {
        val g = MeshGraph(selfId = "me")
        g.observeDirect("alice")
        assertEquals(1, g.distanceTo("alice"))
        assertEquals("alice", g.nextHopTo("alice"))
    }

    @Test fun `bfs finds shortest path through relay chain`() {
        val g = MeshGraph(selfId = "me")
        // me — a — b — c
        g.observeRelayPath(listOf("me", "a", "b", "c"))
        assertEquals(3, g.distanceTo("c"))
        assertEquals("a", g.nextHopTo("c"))
        // shorter alternative via direct edge to b
        g.observeDirect("b")
        assertEquals(1, g.distanceTo("b"))
        assertEquals("b", g.nextHopTo("c"))
        assertEquals(2, g.distanceTo("c"))
    }

    @Test fun `unknown target returns null`() {
        val g = MeshGraph(selfId = "me")
        g.observeDirect("alice")
        assertNull(g.distanceTo("nobody"))
        assertNull(g.nextHopTo("nobody"))
    }

    @Test fun `gc removes stale edges`() {
        val g = MeshGraph(selfId = "me")
        g.observeDirect("alice", nowMs = 0L)
        g.gc(nowMs = MeshGraph.EDGE_TTL_MS + 10_000L)
        assertNull(g.distanceTo("alice"))
    }
}
