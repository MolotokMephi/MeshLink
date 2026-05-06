package team.hex.meshlink.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * IPv4 UDP-multicast transport for mesh frames.
 *
 * Runs on any Wi-Fi network or hotspot — including offline Wi-Fi (router
 * with no upstream internet, or one device sharing a hotspot). Uses a
 * fixed multicast group (239.42.42.42) and port (43210) so peers find
 * each other without out-of-band coordination.
 *
 * Handles MTU constraints by limiting frame size; if a logical mesh frame
 * exceeds [MAX_DATAGRAM] bytes it's split with [LanFragmentation], which
 * mirrors the BLE [Fragmentation] header on top of UDP.
 *
 * Frames are emitted on [incoming] only after reassembly.
 */
class LanTransport(private val context: Context) : Transport {

    override val name: String get() = "lan"
    private val tag = "LanTransport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rxJob: Job? = null

    private var socket: MulticastSocket? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var tcpServer: ServerSocket? = null
    private var tcpJob: Job? = null
    private val tcpLinks = ConcurrentHashMap<String, LanTcpLink>()

    /** Connection attempts in flight, deduped by key, so we don't spam SYNs. */
    private val tcpAttempts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val reassemblers = ConcurrentHashMap<String, team.hex.meshlink.ble.Reassembler>()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile private var seenSenders: Long = 0
    override val liveLinkCount: Int get() = seenSenders.toInt()

    override fun start() {
        if (_state.value == TransportState.Running) return
        _state.value = TransportState.Starting
        try {
            // Hold the multicast lock so the device doesn't filter group traffic.
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createMulticastLock("meshlink-lan").apply {
                setReferenceCounted(false)
                acquire()
            }
            val sock = MulticastSocket(PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 0
            }
            val group = InetAddress.getByName(GROUP)
            // Join on every Wi-Fi-like interface so AP-mode + STA-mode both work.
            for (iface in usableInterfaces()) {
                runCatching { sock.joinGroup(java.net.InetSocketAddress(group, PORT), iface) }
            }
            socket = sock
            rxJob = scope.launch { rxLoop(sock, group) }
            startTcpServer()
            registerNetworkCallback()
            _state.value = TransportState.Running
        } catch (t: Throwable) {
            Log.w(tag, "start failed: $t")
            _state.value = TransportState.Failed
            stop()
        }
    }

    /**
     * Re-join the multicast group on every Wi-Fi-capable interface whenever
     * connectivity changes — Android can switch between Wi-Fi/hotspot/Wi-Fi
     * Direct and silently lose the multicast subscription.
     */
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        connectivityManager = cm
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { rejoinAllInterfaces() }
            override fun onLost(network: Network) { rejoinAllInterfaces() }
            override fun onLinkPropertiesChanged(
                network: Network, lp: android.net.LinkProperties,
            ) { rejoinAllInterfaces() }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }
        networkCallback = cb
    }

    private fun rejoinAllInterfaces() {
        val sock = socket ?: return
        val group = runCatching { InetAddress.getByName(GROUP) }.getOrNull() ?: return
        for (iface in usableInterfaces()) {
            runCatching { sock.leaveGroup(java.net.InetSocketAddress(group, PORT), iface) }
            runCatching { sock.joinGroup(java.net.InetSocketAddress(group, PORT), iface) }
        }
    }

    override fun stop() {
        rxJob?.cancel(); rxJob = null
        runCatching { socket?.close() }
        socket = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        runCatching {
            val cb = networkCallback
            if (cb != null) connectivityManager?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
        connectivityManager = null
        tcpJob?.cancel(); tcpJob = null
        runCatching { tcpServer?.close() }
        tcpServer = null
        tcpLinks.values.forEach { runCatching { it.close() } }
        tcpLinks.clear()
        reassemblers.clear()
        seenSenders = 0
        scope.cancel()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray, hint: SendHint) {
        // Frames that don't fit a single multicast datagram still get
        // fragmented on UDP, but we additionally fan them out over any
        // open TCP back-channels — those negotiate window size and recover
        // from packet loss without our help, which matters on hotspot APs
        // with aggressive multicast filtering or tiny effective MTU.
        if (frame.size > TCP_FALLBACK_THRESHOLD && tcpLinks.isNotEmpty()) {
            for (link in tcpLinks.values) link.send(frame)
        }
        val sock = socket ?: return
        val group = runCatching { InetAddress.getByName(GROUP) }.getOrNull() ?: return
        val msgId = (System.nanoTime() and 0xFFFF).toInt()
        val chunks = team.hex.meshlink.ble.Fragmentation.split(frame, MAX_PAYLOAD, msgId)
        for (chunk in chunks) {
            val packet = DatagramPacket(chunk, chunk.size, group, PORT)
            runCatching { sock.send(packet) }
        }
        // Also broadcast on 255.255.255.255 (limited broadcast) for hotspots
        // that filter multicast traffic between AP clients but pass broadcast.
        runCatching {
            val bcast = InetAddress.getByName("255.255.255.255")
            for (chunk in chunks) sock.send(DatagramPacket(chunk, chunk.size, bcast, PORT))
        }
    }

    /**
     * TCP fallback. Anyone who learns our address via UDP can open a
     * length-prefixed framed connection on [TCP_PORT]; we use it for
     * payloads bigger than [TCP_FALLBACK_THRESHOLD] (file chunks, big
     * group announcements). Inbound peers reuse [_incoming] so the
     * router doesn't need to know about the back-channel.
     */
    private fun startTcpServer() {
        if (tcpJob != null) return
        tcpJob = scope.launch {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TCP_PORT))
                }
            } catch (t: Throwable) {
                Log.w(tag, "tcp listen failed: $t")
                return@launch
            }
            tcpServer = ss
            while (true) {
                val sock = try { ss.accept() } catch (_: Throwable) { break }
                val key = "${sock.inetAddress.hostAddress}:${sock.port}"
                val link = LanTcpLink(
                    socket = sock,
                    onFrame = { _incoming.tryEmit(it) },
                    onClose = { tcpLinks.remove(key) },
                )
                tcpLinks[key] = link
                link.startReader()
            }
        }
    }

    /**
     * Open an outbound TCP back-channel to [host]. Surfaced for callers
     * that already learned a peer address via UDP discovery and want a
     * reliable fat-pipe (e.g. file-transfer prefers TCP when available).
     */
    fun connectTcp(host: String, port: Int = TCP_PORT) {
        val key = "$host:$port"
        if (tcpLinks.containsKey(key)) return                  // already linked
        if (!tcpAttempts.add(key)) return                      // attempt in flight
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 5_000)
                val link = LanTcpLink(
                    socket = sock,
                    onFrame = { _incoming.tryEmit(it) },
                    onClose = {
                        tcpLinks.remove(key)
                        tcpAttempts.remove(key)
                    },
                )
                tcpLinks[key] = link
                link.startReader()
            } catch (t: Throwable) {
                Log.w(tag, "tcp connect $host:$port failed: $t")
                tcpAttempts.remove(key)
            }
        }
    }

    private suspend fun rxLoop(sock: MulticastSocket, group: InetAddress) {
        val buf = ByteArray(MAX_DATAGRAM)
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (t: Throwable) {
                if (sock.isClosed) break
                continue
            }
            // Skip our own broadcast loopback (best-effort).
            if (isSelfAddress(pkt.address)) continue
            val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val key = pkt.address.hostAddress ?: "?"
            // Asymmetric-multicast workaround: as soon as we receive a
            // datagram from a source we'll open a TCP back-channel to it.
            // On hotspots / corporate APs that filter multicast in one
            // direction this guarantees both peers can deliver to each
            // other once at least one direction works.
            if (key != "?" && key !in tcpLinks.keys.map { it.substringBefore(":") }) {
                connectTcp(key)
            }
            val rasm = reassemblers.getOrPut(key) {
                seenSenders++
                team.hex.meshlink.ble.Reassembler()
            }
            val full = rasm.feed(data)
            if (full != null) _incoming.emit(full)
        }
    }

    private fun usableInterfaces(): List<NetworkInterface> {
        val out = mutableListOf<NetworkInterface>()
        for (i in NetworkInterface.getNetworkInterfaces()) {
            if (!i.isUp || i.isLoopback || !i.supportsMulticast()) continue
            out.add(i)
        }
        return out
    }

    private fun isSelfAddress(addr: InetAddress): Boolean {
        return runCatching {
            for (i in NetworkInterface.getNetworkInterfaces()) {
                for (a in i.inetAddresses) if (a == addr) return true
            }
            false
        }.getOrDefault(false)
    }

    companion object {
        private const val GROUP = "239.42.42.42"
        private const val PORT = 43210

        /** Conservative IPv4 link MTU - IP/UDP headers; fits everywhere. */
        const val MAX_DATAGRAM = 1400
        const val MAX_PAYLOAD = MAX_DATAGRAM - team.hex.meshlink.ble.Fragmentation.HEADER_BYTES

        /** TCP back-channel — used by [connectTcp] for fat payloads. */
        const val TCP_PORT = 43212

        /**
         * Above this frame size we duplicate onto any open TCP link in
         * addition to UDP multicast. Picked just below the IPv4 MTU so
         * single-packet messages stay UDP-only.
         */
        const val TCP_FALLBACK_THRESHOLD = 1200
    }
}

/** Length-prefixed TCP framing for the LanTransport back-channel. */
private class LanTcpLink(
    private val socket: Socket,
    private val onFrame: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var readerJob: Job? = null

    fun startReader() {
        readerJob = scope.launch { runCatching { readLoop() }; close() }
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
                        (len ushr  8).toByte(),
                        (len and 0xFF).toByte(),
                    ))
                    out.write(frame)
                    out.flush()
                }
            } catch (_: Throwable) { close() }
        }
    }

    private fun readLoop() {
        val ins = socket.getInputStream()
        val header = ByteArray(4)
        while (true) {
            if (!fillBuf(ins, header, 4)) return
            val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl  8) or
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
