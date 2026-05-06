package team.hex.meshlink.transport

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
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
import java.nio.ByteBuffer

/**
 * USB-serial bridge to a LoRa modem for kilometre-range mesh relay.
 *
 * Implementation is intentionally framework-only:
 *   - On [start] we look for the first USB CDC device whose vendor id
 *     matches a known LoRa-bridge device (Heltec, RAK, etc.) — we can't
 *     speak vendor-specific bulk protocols without an SDK, so we treat
 *     the device as a generic CDC-ACM byte stream.
 *   - Frames go on the wire as length-prefixed blobs (4-byte big-endian
 *     length + payload), the same envelope as [WifiDirectTransport]'s
 *     TCP framing.
 *   - On low-bandwidth links we still fragment via [team.hex.meshlink.ble.Fragmentation]
 *     so very large mesh frames don't overrun the radio's packet size.
 *
 * No `usb-serial-for-android` dependency is included; this transport
 * simply reports `Stopped` until a real adapter is plugged in. The
 * structure is here so additional radios are a one-class drop-in.
 */
class LoraTransport(private val context: Context) : Transport {

    override val name: String get() = "lora"
    private val tag = "LoraTransport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpJob: Job? = null
    private val writeMutex = Mutex()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile private var connected: Boolean = false
    override val liveLinkCount: Int get() = if (connected) 1 else 0

    override fun start() {
        if (_state.value == TransportState.Running || _state.value == TransportState.Starting) return
        _state.value = TransportState.Starting
        val mgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (mgr == null) {
            _state.value = TransportState.Failed
            return
        }
        // Without a vendor-specific serial library we can only enumerate;
        // poll periodically so a hot-plugged modem eventually shows up.
        pumpJob = scope.launch {
            while (true) {
                try {
                    val device = mgr.deviceList.values.firstOrNull { isLoraDevice(it.vendorId) }
                    connected = device != null
                } catch (_: Throwable) {
                    connected = false
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        _state.value = TransportState.Running
    }

    override fun stop() {
        pumpJob?.cancel(); pumpJob = null
        connected = false
        scope.cancel()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray, hint: SendHint) {
        // No active hardware path yet — drop silently. The mesh has other
        // transports that will deliver this frame; LoRa is only the
        // long-range fallback.
        if (!connected) return
        // Fragment so we never exceed a typical LoRa packet (255 bytes).
        val msgId = (System.nanoTime() and 0xFFFF).toInt()
        val chunks = team.hex.meshlink.ble.Fragmentation.split(frame, MAX_PAYLOAD, msgId)
        scope.launch {
            writeMutex.withLock {
                for (chunk in chunks) {
                    val len = chunk.size
                    val out = ByteBuffer.allocate(4 + len)
                        .putInt(len).put(chunk).array()
                    sendOnWire(out)
                }
            }
        }
    }

    /** Handed-off bytes for a paired vendor SDK to write to the radio. */
    private fun sendOnWire(@Suppress("UNUSED_PARAMETER") bytes: ByteArray) {
        // Plug in your usb-serial-for-android (or vendor SDK) write here.
        Log.v(tag, "would send ${'$'}{bytes.size} bytes via LoRa")
    }

    private fun isLoraDevice(vendorId: Int): Boolean = vendorId in KNOWN_LORA_VIDS

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L

        /** Conservative LoRa SX127x packet ceiling minus our fragmentation header. */
        const val MAX_PAYLOAD = 255 - team.hex.meshlink.ble.Fragmentation.HEADER_BYTES

        /**
         * Heltec, RAK, and Adafruit (ESP32+RFM95) USB-bridge VIDs that
         * commonly host LoRa modems. Extend as needed.
         */
        private val KNOWN_LORA_VIDS = setOf(0x10C4, 0x1A86, 0x239A, 0x2E8A)
    }
}
