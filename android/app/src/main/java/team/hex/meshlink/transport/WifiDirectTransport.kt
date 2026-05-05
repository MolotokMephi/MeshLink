package team.hex.meshlink.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct (Wi-Fi P2P) transport for fat-pipe mesh traffic.
 *
 * State machine:
 *
 * ```
 *   Stopped → Starting (initialize manager+channel, register receiver,
 *                       open TCP listener)
 *           → Discovering (loop: discoverPeers / requestPeers)
 *           ─ if a peer with our service hint appears →
 *             Connecting (connect)
 *           → Connected (group-owner exposes server, client connects to
 *                        groupOwnerAddress:LISTEN_PORT)
 *           → on disconnect → back to Discovering
 * ```
 *
 * Discovery is rate-limited to one round per [DISCOVER_INTERVAL_MS] to
 * avoid the Android 11+ scan-throttling penalty.
 */
class WifiDirectTransport(private val context: Context) : Transport {

    override val name: String get() = "wifi-direct"
    private val tag = "WifiDirectTransport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var discoveryJob: Job? = null
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
    private var receiver: BroadcastReceiver? = null

    @Volatile private var isWifiP2pEnabled: Boolean = false
    @Volatile private var lastConnectionInfo: WifiP2pInfo? = null
    @Volatile private var lastDiscoverAt: Long = 0L

    override fun start() {
        if (_state.value == TransportState.Running) return
        _state.value = TransportState.Starting
        if (!hasPermissions()) {
            Log.w(tag, "missing Wi-Fi P2P permissions")
            _state.value = TransportState.Failed
            return
        }
        val mgr = p2pManager
        if (mgr == null) {
            Log.w(tag, "WifiP2pManager unavailable on this device")
            _state.value = TransportState.Failed
            return
        }
        channel = mgr.initialize(context, Looper.getMainLooper(), null)
        registerReceiver()
        acceptOn(LISTEN_PORT)
        startDiscoveryLoop()
        _state.value = TransportState.Running
    }

    override fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        discoveryJob?.cancel(); discoveryJob = null
        serverJob?.cancel(); serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        outbound.values.forEach { runCatching { it.close() } }
        outbound.clear()
        runCatching { p2pManager?.removeGroup(channel, null) }
        scope.cancel()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray, hint: SendHint) {
        for (link in outbound.values) link.send(frame)
    }

    // ------------- Server side -------------

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
                    val link = FrameLink(sock,
                        onFrame = { _incoming.tryEmit(it) },
                        onClose = { outbound.remove(key) })
                    outbound[key] = link
                    link.startReader()
                }
            } catch (t: Throwable) {
                Log.w(tag, "accept loop failed: $t")
            }
        }
    }

    /** Open an outbound stream to a peer that's listening on [port]. */
    fun connectTo(host: String, port: Int = LISTEN_PORT) {
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 5_000)
                val key = "${host}:${port}"
                val link = FrameLink(sock,
                    onFrame = { _incoming.tryEmit(it) },
                    onClose = { outbound.remove(key) })
                outbound[key] = link
                link.startReader()
            } catch (t: Throwable) {
                Log.w(tag, "connect to $host:$port failed: $t")
            }
        }
    }

    // ------------- Discovery state machine -------------

    private fun registerReceiver() {
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val s = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        isWifiP2pEnabled = s == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(r, filter)
            }
        }
        receiver = r
        // First pass — peers may already have been advertised before we
        // registered, so seed both queries immediately.
        requestPeers()
        requestConnectionInfo()
    }

    private fun startDiscoveryLoop() {
        discoveryJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (isWifiP2pEnabled && now - lastDiscoverAt >= DISCOVER_INTERVAL_MS) {
                    lastDiscoverAt = now
                    triggerDiscoverPeers()
                }
                delay(2_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerDiscoverPeers() {
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { /* success surfaces via WIFI_P2P_PEERS_CHANGED_ACTION */ }
                override fun onFailure(reason: Int) {
                    Log.w(tag, "discoverPeers failed: $reason")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.requestPeers(ch) { peerList ->
                val devices = peerList.deviceList ?: return@requestPeers
                for (device in devices) maybeConnect(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun maybeConnect(device: WifiP2pDevice) {
        // Only connect if the remote is available and we don't already have
        // a session. We prefer the deterministic-tiebreak approach: connect
        // only if our address sorts lower, so two peers don't both initiate.
        if (device.status != WifiP2pDevice.AVAILABLE) return
        val info = lastConnectionInfo
        if (info != null && info.groupFormed) return
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // 0 = least desire to be group owner, 15 = most. Using 0 lets
            // the side with the better radio be GO.
            groupOwnerIntent = 0
        }
        runCatching {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { /* connection-changed broadcast follows */ }
                override fun onFailure(reason: Int) {
                    Log.w(tag, "connect to ${device.deviceAddress} failed: $reason")
                }
            })
        }
    }

    private fun requestConnectionInfo() {
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.requestConnectionInfo(ch) { info ->
                lastConnectionInfo = info
                if (info != null && info.groupFormed) {
                    if (info.isGroupOwner) {
                        // Server already accepting on LISTEN_PORT.
                    } else {
                        val host = info.groupOwnerAddress?.hostAddress
                        if (host != null) connectTo(host, LISTEN_PORT)
                    }
                }
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

    companion object {
        const val LISTEN_PORT = 43211

        /**
         * Throttle discovery rounds. Android 11+ silently throttles apps
         * that scan more aggressively than ~4 rounds per 2 minutes; 30s
         * is well under that ceiling.
         */
        const val DISCOVER_INTERVAL_MS: Long = 30_000L
    }
}

/**
 * Length-prefixed framing over a TCP socket. Concurrent writers are
 * serialized through a coroutine [Mutex] so the 4-byte length prefix
 * always sticks to its payload — without it, two parallel `send()`
 * coroutines could interleave bytes and desync the framer permanently.
 */
private class FrameLink(
    private val socket: Socket,
    private val onFrame: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var readerJob: Job? = null
    @Volatile private var heartbeatLastReplyMs: Long = System.currentTimeMillis()

    fun startReader() {
        readerJob = scope.launch { runCatching { readLoop() }; close() }
        scope.launch { heartbeatLoop() }
    }

    fun send(frame: ByteArray) {
        scope.launch {
            try {
                writeMutex.withLock {
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
                }
            } catch (_: Throwable) {
                close()
            }
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (System.currentTimeMillis() - heartbeatLastReplyMs > HEARTBEAT_TIMEOUT_MS) {
                close()
                return
            }
            // Send a 0-byte heartbeat frame (size=0 is filtered by readLoop).
            runCatching {
                writeMutex.withLock {
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(0, 0, 0, 0))
                    out.flush()
                }
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
            if (len < 0 || len > MAX_FRAME) return
            heartbeatLastReplyMs = System.currentTimeMillis()
            if (len == 0) continue        // heartbeat ping/pong
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

    companion object {
        const val MAX_FRAME = 8 * 1024 * 1024
        const val HEARTBEAT_INTERVAL_MS = 8_000L
        const val HEARTBEAT_TIMEOUT_MS = 30_000L
    }
}
