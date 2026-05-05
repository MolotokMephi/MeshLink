package team.hex.meshlink.mesh

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Adjacency graph of the mesh.
 *
 * Vertices are node ids. Edges are derived from two evidence sources:
 *
 *   1. **Direct links.** [observeDirect] is called by the router whenever a
 *      transport reports that a frame from `peer` arrived without ever
 *      transiting another node (i.e. the relay_path was empty when the
 *      message was originally signed by us-or-them, plus our own node sat
 *      at the head). This is the strongest signal that we are one hop
 *      apart.
 *   2. **Inferred links.** [observeRelayPath] consumes the relay_path of an
 *      arriving envelope. Consecutive entries imply that those two nodes
 *      were one hop apart at the time of the relay. This lets us learn
 *      about the topology beyond our own direct neighbourhood.
 *
 * Edges decay if they are not refreshed: see [EDGE_TTL_MS]. Concurrent
 * reads (BFS / `nextHopTo`) and writes are guarded by a single mutex —
 * the graph is small enough (≪10⁴ nodes in any realistic mesh) that
 * shortest-path searches are O(V+E) and microsecond-cheap.
 *
 * The router uses [nextHopTo] to **bias** flooding: when we have a known
 * shortest path to the recipient and a live link to that hop, we send
 * unicast on that link first; flooding still happens as a fallback so
 * the mesh remains correct even if the graph is stale.
 */
class MeshGraph(private val selfId: String) {

    private data class Edge(var lastSeenMs: Long)

    private val adjacency = ConcurrentHashMap<String, MutableMap<String, Edge>>()
    private val lock = Any()

    /** Record evidence that [a] and [b] were one hop apart at [nowMs]. */
    fun observeEdge(a: String, b: String, nowMs: Long = System.currentTimeMillis()) {
        if (a == b || a.isEmpty() || b.isEmpty()) return
        synchronized(lock) {
            edge(a, b).lastSeenMs = nowMs
            edge(b, a).lastSeenMs = nowMs
        }
    }

    /** A peer talked to us directly (hop=0 in the relay path before we appended). */
    fun observeDirect(peer: String, nowMs: Long = System.currentTimeMillis()) {
        observeEdge(selfId, peer, nowMs)
    }

    /**
     * Treat consecutive entries in [relayPath] as evidence of one-hop links.
     * The path is the list of nodes the envelope visited in order.
     */
    fun observeRelayPath(relayPath: List<String>, nowMs: Long = System.currentTimeMillis()) {
        if (relayPath.size < 2) return
        for (i in 0 until relayPath.size - 1) {
            observeEdge(relayPath[i], relayPath[i + 1], nowMs)
        }
    }

    /** Drop edges older than [EDGE_TTL_MS]. Cheap O(E). */
    fun gc(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val it = adjacency.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val edges = e.value
                val ie = edges.entries.iterator()
                while (ie.hasNext()) {
                    if (nowMs - ie.next().value.lastSeenMs > EDGE_TTL_MS) ie.remove()
                }
                if (edges.isEmpty()) it.remove()
            }
        }
    }

    /** Snapshot the neighbour set of [node] (live edges only). */
    fun neighbours(node: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
        synchronized(lock) {
            val map = adjacency[node] ?: return emptySet()
            return map.entries.asSequence()
                .filter { nowMs - it.value.lastSeenMs <= EDGE_TTL_MS }
                .map { it.key }
                .toSet()
        }
    }

    /** Shortest hop count from [selfId] to [target], or null if unreachable. */
    fun distanceTo(target: String): Int? = shortestPath(target)?.let { it.size - 1 }

    /**
     * BFS shortest path (`selfId → … → target`). Edges with `lastSeenMs`
     * older than [EDGE_TTL_MS] are ignored.
     */
    fun shortestPath(target: String): List<String>? {
        if (target == selfId) return listOf(selfId)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val parent = HashMap<String, String?>()
            parent[selfId] = null
            val queue = ArrayDeque<String>()
            queue.add(selfId)
            while (queue.isNotEmpty()) {
                val cur = queue.poll()
                if (cur == target) {
                    val out = ArrayList<String>()
                    var c: String? = cur
                    while (c != null) { out.add(c); c = parent[c] }
                    out.reverse()
                    return out
                }
                val edges = adjacency[cur] ?: continue
                for ((next, edge) in edges) {
                    if (now - edge.lastSeenMs > EDGE_TTL_MS) continue
                    if (parent.containsKey(next)) continue
                    parent[next] = cur
                    queue.add(next)
                }
            }
        }
        return null
    }

    /**
     * The first hop on the shortest path from us to [target], or null if
     * we don't know one. The router uses this to pick a unicast next hop
     * before falling back to flooding.
     */
    fun nextHopTo(target: String): String? {
        val path = shortestPath(target) ?: return null
        return path.getOrNull(1)
    }

    /** For diagnostics / settings UI. */
    fun snapshot(): GraphSnapshot {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            var edges = 0
            for (m in adjacency.values) edges += m.values.count { now - it.lastSeenMs <= EDGE_TTL_MS }
            return GraphSnapshot(
                nodes = adjacency.size,
                edges = edges / 2,
                directNeighbours = neighbours(selfId, now).size,
            )
        }
    }

    private fun edge(a: String, b: String): Edge {
        val map = adjacency.getOrPut(a) { HashMap() }
        return map.getOrPut(b) { Edge(0L) }
    }

    companion object {
        /** Edges go stale 90s after their last refresh — three announce intervals. */
        const val EDGE_TTL_MS: Long = 90_000L
    }
}

data class GraphSnapshot(val nodes: Int, val edges: Int, val directNeighbours: Int)
