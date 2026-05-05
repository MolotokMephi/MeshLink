package team.hex.meshlink.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct (P2P) transport for "fat-pipe" mesh traffic — file transfers,
 * media, anything that BLE can't reasonably carry.
 *
 * Design:
 *   - Initialize WifiP2pManager + channel; register the standard P2P
 *     state-change broadcast receiver (added at start time below).
 *   - Discover peers, then auto-connect to those running our group-owner
 *     intent. The owner exposes a TCP port (43211) on its P2P-allocated
 *     interface; peers stream length-prefixed mesh frames over that
 *     socket bidirectionally.
 *
 * Currently implemented:
 *   - TCP framing (4-byte big-endian length prefix + payload)
 *   - Server socket on group owner side
 *   - Live-link bookkeeping & broadcast fan-out
 *
 * Deferred to follow-up commit (see android/TODO.md → "Wi-Fi Direct
 * follow-ups"): the BroadcastReceiver-based discovery/connection state
 * machine. Until then, callers can drive [acceptOn] + [connectTo]
 * directly when they already have peer IP addresses (e.g. via the
 * BLE-mesh ANNOUNCE channel that doubles as a hint for Wi-Fi Direct
 * upgrade).
 */
class WifiDirectTransport(private val context: Context) : Transport {

    override val name: String get() = "wifi-direct"
    private val tag = "WifiDirectTransport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val outbound = ConcurrentHashMap<String, FrameLink>()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val liveLinkCount: Int get() = outbound.size

    private val p2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null

    override fun start() {
        if (_state.value == TransportState.Running) return
        _state.value = TransportState.Starting
        if (!hasPermissions()) {
            Log.w(tag, "missing Wi-Fi P2P permissions")
            _state.value = TransportState.Failed
            return
        }
        val mgr = p2pManager
        if (mgr != null) {
            channel = mgr.initialize(context, Looper.getMainLooper(), null)
        }
        // Start TCP listener immediately. P2P discovery/connect kicks in only
        // when the user opts into a Wi-Fi-Direct upgrade for a particular peer.
        acceptOn(LISTEN_PORT)
        _state.value = TransportState.Running
    }

    override fun stop() {
        serverJob?.cancel(); serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        outbound.values.forEach { runCatching { it.close() } }
        outbound.clear()
        scope.cancel()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray) {
        // No fragmentation header needed — TCP delivers a stream and we
        // length-prefix at the framing layer.
        for (link in outbound.values) link.send(frame)
    }

    /** Listen for inbound mesh frame streams on [port]. */
    fun acceptOn(port: Int) {
        if (serverJob != null) return
        serverJob = scope.launch {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = ss
                while (true) {
                    val sock = try { ss.accept() } catch (_: Throwable) { break }
                    val key = "${sock.inetAddress.hostAddress}:${sock.port}"
                    val link = FrameLink(sock, onFrame = { _incoming.tryEmit(it) },
                        onClose = { outbound.remove(key) })
                    outbound[key] = link
                    link.startReader()
                }
            } catch (t: Throwable) {
                Log.w(tag, "accept loop failed: $t")
            }
        }
    }

    /** Open an outbound stream to a peer that's listening on [LISTEN_PORT]. */
    fun connectTo(host: String, port: Int = LISTEN_PORT) {
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 5_000)
                val key = "${host}:${port}"
                val link = FrameLink(sock, onFrame = { _incoming.tryEmit(it) },
                    onClose = { outbound.remove(key) })
                outbound[key] = link
                link.startReader()
            } catch (t: Throwable) {
                Log.w(tag, "connect to $host:$port failed: $t")
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private inline fun <T> requirePerms(block: () -> T): T = block()

    companion object {
        const val LISTEN_PORT = 43211
    }
}

/** Length-prefixed framing over a TCP socket. */
private class FrameLink(
    private val socket: Socket,
    private val onFrame: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    fun startReader() {
        readerJob = scope.launch { runCatching { readLoop() } ; close() }
    }

    fun send(frame: ByteArray) {
        scope.launch {
            try {
                val out = socket.getOutputStream()
                val len = frame.size
                out.write(byteArrayOf(
                    (len ushr 24).toByte(),
                    (len ushr 16).toByte(),
                    (len ushr 8).toByte(),
                    (len and 0xFF).toByte(),
                ))
                out.write(frame)
                out.flush()
            } catch (_: Throwable) {
                close()
            }
        }
    }

    private fun readLoop() {
        val ins = socket.getInputStream()
        val header = ByteArray(4)
        while (true) {
            if (!fillBuf(ins, header, 4)) return
            val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (len <= 0 || len > MAX_FRAME) return
            val payload = ByteArray(len)
            if (!fillBuf(ins, payload, len)) return
            onFrame(payload)
        }
    }

    private fun fillBuf(ins: java.io.InputStream, buf: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = try { ins.read(buf, off, n - off) } catch (_: Throwable) { return false }
            if (r < 0) return false
            off += r
        }
        return true
    }

    fun close() {
        readerJob?.cancel()
        runCatching { socket.close() }
        scope.cancel()
        onClose()
    }

    companion object { const val MAX_FRAME = 8 * 1024 * 1024 }
}
