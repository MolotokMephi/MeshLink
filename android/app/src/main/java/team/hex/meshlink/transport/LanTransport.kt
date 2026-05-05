package team.hex.meshlink.transport

import android.content.Context
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
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
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
            _state.value = TransportState.Running
        } catch (t: Throwable) {
            Log.w(tag, "start failed: $t")
            _state.value = TransportState.Failed
            stop()
        }
    }

    override fun stop() {
        rxJob?.cancel(); rxJob = null
        runCatching { socket?.close() }
        socket = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        reassemblers.clear()
        seenSenders = 0
        scope.cancel()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray) {
        val sock = socket ?: return
        val group = runCatching { InetAddress.getByName(GROUP) }.getOrNull() ?: return
        val msgId = (System.nanoTime() and 0xFFFF).toInt()
        val chunks = team.hex.meshlink.ble.Fragmentation.split(frame, MAX_PAYLOAD, msgId)
        for (chunk in chunks) {
            val packet = DatagramPacket(chunk, chunk.size, group, PORT)
            runCatching { sock.send(packet) }
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
    }
}
