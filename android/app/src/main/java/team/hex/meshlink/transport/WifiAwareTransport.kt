@file:SuppressLint("MissingPermission")

package team.hex.meshlink.transport

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import team.hex.meshlink.ble.Fragmentation
import team.hex.meshlink.ble.Reassembler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware (Neighbor Awareness Networking) transport.
 *
 * Wi-Fi Aware lets two nearby phones exchange messages **without** an
 * access point or pre-existing Wi-Fi network — purely peer-to-peer at
 * the L2 layer. It's the closest thing Android has to AirDrop and is
 * the right primitive for an offline-first messenger when no LAN is
 * available.
 *
 * Hardware support is limited (most current Pixels and high-end Samsungs
 * carry it; mid-range phones often do not). On unsupported hardware the
 * transport stops in [TransportState.Failed] with `details = "no_aware_hw"`
 * and the rest of the app is unaffected.
 *
 * Message-size limit: Aware caps each `sendMessage` at ~255 bytes. We
 * fragment with the same header BLE uses ([Fragmentation]); large frames
 * (files / voice notes) are dropped here on purpose — they ride
 * LanTransport / WifiDirectTransport instead.
 */
class WifiAwareTransport(private val context: Context) : Transport {

    override val name: String get() = "wifi-aware"
    private val tag = "WifiAwareTransport"

    private val handlerThread = HandlerThread("wifi-aware").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile private var lastDetails: String? = null
    override val details: String? get() = lastDetails

    private var awareManager: WifiAwareManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    /** Discovered peer handles, indexed by peer.toString() for stability. */
    private val peerHandles = ConcurrentHashMap<String, PeerHandle>()
    private val reassemblers = ConcurrentHashMap<String, Reassembler>()
    private val msgIdSeq = AtomicInteger(1)

    override val liveLinkCount: Int get() = peerHandles.size

    override fun start() {
        if (_state.value == TransportState.Running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastDetails = "no_aware_hw"
            _state.value = TransportState.Failed
            return
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            lastDetails = "no_aware_hw"
            _state.value = TransportState.Failed
            return
        }
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (mgr == null || !mgr.isAvailable) {
            lastDetails = "aware_unavailable"
            _state.value = TransportState.Failed
            return
        }
        _state.value = TransportState.Starting
        awareManager = mgr
        try {
            mgr.attach(awareAttachCallback, handler)
        } catch (t: Throwable) {
            Log.w(tag, "attach failed: $t")
            lastDetails = "attach_failed"
            _state.value = TransportState.Failed
        }
    }

    override fun stop() {
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { awareSession?.close() }
        publishSession = null
        subscribeSession = null
        awareSession = null
        peerHandles.clear()
        reassemblers.clear()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray, hint: SendHint) {
        // Aware caps each message at ~255 bytes; large frames don't fit
        // and would only block the queue. Files / voice notes go via
        // LAN / Wi-Fi Direct anyway.
        if (frame.size > MAX_AWARE_FRAME) return
        val pub = publishSession ?: return
        val msgId = msgIdSeq.getAndIncrement() and 0xFFFF
        val chunks = Fragmentation.split(frame, MAX_CHUNK_PAYLOAD, msgId)
        for ((peerKey, peer) in peerHandles) {
            for (chunk in chunks) {
                runCatching {
                    pub.sendMessage(peer, msgIdSeq.getAndIncrement(), chunk)
                }.onFailure {
                    Log.w(tag, "sendMessage to $peerKey failed: $it")
                }
            }
        }
    }

    private val awareAttachCallback = object : AttachCallback() {
        override fun onAttached(session: WifiAwareSession) {
            awareSession = session
            startPublish(session)
            startSubscribe(session)
            _state.value = TransportState.Running
        }
        override fun onAttachFailed() {
            lastDetails = "attach_failed"
            _state.value = TransportState.Failed
        }
    }

    private fun startPublish(session: WifiAwareSession) {
        val cfg = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session.publish(cfg, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSession = session
            }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                handlePeerMessage(peer, message)
            }
            override fun onSessionTerminated() {
                publishSession = null
            }
        }, handler)
    }

    private fun startSubscribe(session: WifiAwareSession) {
        val cfg = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session.subscribe(cfg, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
            }
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?,
            ) {
                peerHandles[peerHandle.toString()] = peerHandle
            }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                handlePeerMessage(peer, message)
            }
            override fun onSessionTerminated() {
                subscribeSession = null
                peerHandles.clear()
            }
        }, handler)
    }

    private fun handlePeerMessage(peer: PeerHandle, message: ByteArray) {
        peerHandles[peer.toString()] = peer
        val rasm = reassemblers.getOrPut(peer.toString()) { Reassembler() }
        val full = rasm.feed(message)
        if (full != null) _incoming.tryEmit(full)
    }

    companion object {
        private const val SERVICE_NAME = "meshlink-aware"
        /** Maximum logical mesh frame we'll squeeze through Aware. */
        const val MAX_AWARE_FRAME = 4 * 1024
        /** Max chunk size: Aware allows ~255 bytes per sendMessage. */
        private const val MAX_CHUNK_PAYLOAD = 240 - Fragmentation.HEADER_BYTES
    }
}
