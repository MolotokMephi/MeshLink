package team.hex.meshlink.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A pluggable peer-to-peer link layer for the mesh router.
 *
 * Implementations carry already-fragmented logical frames (the bytes of a
 * [team.hex.meshlink.mesh.MeshMessage]) — the transport itself is responsible
 * for any further chunking required by its physical medium (BLE MTU, UDP
 * datagram size, etc.) and for reassembling them on the other side before
 * surfacing them on [incoming].
 *
 * The mesh router subscribes to [incoming] from every transport and
 * fan-outs every outgoing frame to every transport via [broadcast].
 */
interface Transport {
    /** Human-readable name for logs (e.g. "ble", "lan", "wifi-direct"). */
    val name: String

    /** Reassembled mesh frames received over this transport. */
    val incoming: SharedFlow<ByteArray>

    /** Transport-level liveness for diagnostics. */
    val state: StateFlow<TransportState>

    /** Start up advertising/scanning/listening. Idempotent. */
    fun start()

    /** Tear everything down. Idempotent. */
    fun stop()

    /** Push a logical mesh frame to every reachable peer on this medium. */
    fun broadcast(frame: ByteArray) = broadcast(frame, hint = SendHint.RELIABLE)

    /**
     * Push a logical mesh frame with a delivery hint. Implementations can
     * pick faster/cheaper modes for [SendHint.LOW_LATENCY] payloads (e.g.
     * BLE write-without-response for chat).
     */
    fun broadcast(frame: ByteArray, hint: SendHint)

    /** Number of currently-live remote peers (for connection-cap accounting). */
    val liveLinkCount: Int
}

/**
 * Per-frame quality-of-service hint:
 *   - [RELIABLE]   default; transports use ack'd writes / stream sockets.
 *   - [LOW_LATENCY] small chat-like frames where it's better to drop a
 *     single message than to clog the link's write queue with retries.
 */
enum class SendHint { RELIABLE, LOW_LATENCY }

enum class TransportState { Stopped, Starting, Running, Failed }
