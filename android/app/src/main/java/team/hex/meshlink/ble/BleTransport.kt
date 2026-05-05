@file:SuppressLint("MissingPermission")

package team.hex.meshlink.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
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
import team.hex.meshlink.transport.SendHint
import team.hex.meshlink.transport.Transport
import team.hex.meshlink.transport.TransportState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * BLE peer-to-peer transport. Each device acts simultaneously as:
 *
 *   - **Peripheral (GATT server)** advertising the MeshLink service UUID.
 *     Centrals discover us via scan, write fragmented frames to
 *     CHAR_WRITE_UUID and subscribe to notifications on CHAR_NOTIFY_UUID.
 *   - **Central (GATT client)** scanning for the service UUID and
 *     connecting outbound to fan our frames out to peripherals.
 *
 * Concrete robustness over the MVP:
 *   - per-link MTU is tracked and chunk sizes are derived from it
 *   - outbound write queue is serialized per link with retry on failure
 *   - connection count is capped (LRU-evict oldest) to avoid overloading
 *     vendor BLE stacks (typically 4–7 simultaneous GATT links)
 *   - failed outbound connects use exponential backoff before reconnect
 */
class BleTransport(private val context: Context) : Transport {

    override val name: String get() = "ble"
    private val tag = "BleTransport"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val btManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // Centrals connected to our GATT server, with their negotiated MTU.
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverSideMtu = ConcurrentHashMap<String, Int>()
    private val reassemblersServerSide = ConcurrentHashMap<String, Reassembler>()

    // Outbound (we as central) links keyed by remote BLE address, ordered
    // by recency for LRU eviction when we hit MAX_OUTBOUND_LINKS.
    private val outboundLinks = LinkedHashMap<String, OutboundLink>()
    private val outboundLock = Any()

    // Per-address backoff state for outbound reconnects.
    private val backoff = ConcurrentHashMap<String, BackoffState>()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val liveLinkCount: Int
        get() = synchronized(outboundLock) { outboundLinks.size } + subscribers.size

    @Volatile private var advertising = false
    @Volatile private var scanning = false
    private var keepaliveJob: Job? = null

    /**
     * Serialize notify-characteristic broadcasts: BluetoothGattCharacteristic
     * is mutable shared state, so two concurrent `broadcast()` calls would
     * stomp on each other's `value` field and corrupt subscriber payloads.
     */
    private val notifyLock = Any()

    fun isReady(): Boolean = adapter != null && adapter.isEnabled && hasPermissions()

    override fun start() {
        if (_state.value == TransportState.Running) return
        if (!isReady()) {
            Log.w(tag, "BLE not ready (adapter null/off or missing permissions)")
            _state.value = TransportState.Failed
            return
        }
        _state.value = TransportState.Starting
        startGattServer()
        startAdvertising()
        startScanning()
        startKeepalive()
        _state.value = TransportState.Running
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(BleConstants.KEEPALIVE_INTERVAL_MS)
                pruneStaleLinks()
            }
        }
    }

    private fun pruneStaleLinks() {
        val now = System.currentTimeMillis()
        val toClose = mutableListOf<OutboundLink>()
        synchronized(outboundLock) {
            val it = outboundLinks.entries.iterator()
            while (it.hasNext()) {
                val link = it.next().value
                if (now - link.lastActivityMs > BleConstants.LINK_IDLE_TIMEOUT_MS) {
                    toClose += link
                    it.remove()
                }
            }
        }
        toClose.forEach { it.close() }
    }

    override fun stop() {
        if (_state.value == TransportState.Stopped) return
        keepaliveJob?.cancel(); keepaliveJob = null
        stopAdvertising()
        stopScanning()
        synchronized(outboundLock) {
            outboundLinks.values.forEach { it.close() }
            outboundLinks.clear()
        }
        subscribers.clear()
        serverSideMtu.clear()
        reassemblersServerSide.clear()
        runCatching { requirePerms { gattServer?.close() } }
        gattServer = null
        scope.cancel()
        _state.value = TransportState.Stopped
    }

    override fun broadcast(frame: ByteArray, hint: SendHint) {
        val msgId = (System.nanoTime() and 0xFFFF).toInt()
        val notifyCh = notifyChar
        val server = gattServer
        if (notifyCh != null && server != null) {
            for ((addr, device) in subscribers) {
                val mtu = serverSideMtu[addr] ?: BleConstants.DEFAULT_MTU
                val chunkSize = chunkPayloadFor(mtu)
                val chunks = Fragmentation.split(frame, chunkSize, msgId)
                for (chunk in chunks) {
                    // notifyChar.value is mutable shared state — serialize.
                    synchronized(notifyLock) {
                        runCatching {
                            requirePerms {
                                notifyCh.value = chunk
                                @Suppress("DEPRECATION")
                                server.notifyCharacteristicChanged(device, notifyCh, false)
                            }
                        }
                    }
                }
            }
        }
        val snapshot: List<OutboundLink> = synchronized(outboundLock) { outboundLinks.values.toList() }
        for (link in snapshot) {
            val chunkSize = chunkPayloadFor(link.mtu)
            val chunks = Fragmentation.split(frame, chunkSize, msgId)
            for (chunk in chunks) link.enqueueWrite(chunk, hint)
        }
    }

    private fun chunkPayloadFor(mtu: Int): Int {
        // ATT overhead: 3 bytes for opcode + handle. Frame header: 6 bytes.
        // Be conservative: subtract a couple extra bytes, clamp to safe range.
        val raw = mtu - 3 - Fragmentation.HEADER_BYTES - 2
        return raw.coerceIn(BleConstants.MIN_CHUNK_PAYLOAD, BleConstants.LARGE_CHUNK_PAYLOAD)
    }

    // ------------- GATT server (peripheral side) -------------

    private fun startGattServer() {
        val server = requirePerms { btManager.openGattServer(context, gattServerCallback) }
            ?: run { Log.w(tag, "openGattServer returned null"); return }
        gattServer = server

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val writeChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val notifyCh = BluetoothGattCharacteristic(
            BleConstants.CHAR_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyCh.addDescriptor(cccd)
        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyCh)
        notifyChar = notifyCh

        requirePerms { server.addService(service) }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device.address)
                serverSideMtu.remove(device.address)
                reassemblersServerSide.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverSideMtu[device.address] = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.CHAR_WRITE_UUID) {
                val rasm = reassemblersServerSide.getOrPut(device.address) { Reassembler() }
                val full = rasm.feed(value)
                if (full != null) scope.launch { _incoming.emit(full) }
            }
            if (responseNeeded) {
                requirePerms {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) subscribers[device.address] = device else subscribers.remove(device.address)
            }
            if (responseNeeded) {
                requirePerms {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    // ------------- Advertising -------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(tag, "advertise failed: $errorCode")
            advertising = false
        }
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
        }
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        requirePerms { advertiser.startAdvertising(settings, data, advertiseCallback) }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        runCatching { requirePerms { advertiser.stopAdvertising(advertiseCallback) } }
        advertising = false
    }

    // ------------- Scanning + outbound connection -------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val addr = device.address
            synchronized(outboundLock) {
                if (outboundLinks.containsKey(addr)) return
            }
            // Respect backoff window.
            val bo = backoff[addr]
            if (bo != null && System.currentTimeMillis() < bo.nextAttemptAtMs) return
            connectOutbound(device)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "scan failed: $errorCode")
            scanning = false
        }
    }

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        requirePerms { scanner.startScan(filters, settings, scanCallback) }
        scanning = true
    }

    private fun stopScanning() {
        if (!scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        runCatching { requirePerms { scanner.stopScan(scanCallback) } }
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectOutbound(device: BluetoothDevice) {
        synchronized(outboundLock) {
            // LRU-evict oldest link if we hit the cap.
            while (outboundLinks.size >= BleConstants.MAX_OUTBOUND_LINKS) {
                val oldest = outboundLinks.entries.iterator().next()
                oldest.value.close()
                outboundLinks.remove(oldest.key)
            }
            val link = OutboundLink(
                context = context,
                device = device,
                onIncoming = { frame -> scope.launch { _incoming.emit(frame) } },
                onClosed = { addr, success ->
                    synchronized(outboundLock) { outboundLinks.remove(addr) }
                    if (success) backoff.remove(addr) else recordFailure(addr)
                },
            )
            outboundLinks[device.address] = link
            link.connect()
        }
    }

    private fun recordFailure(addr: String) {
        val now = System.currentTimeMillis()
        val prev = backoff[addr]
        val attempt = (prev?.attempt ?: 0) + 1
        val delayMs = min(BleConstants.BACKOFF_MAX_MS, BleConstants.BACKOFF_BASE_MS shl (attempt - 1).coerceAtMost(6))
        backoff[addr] = BackoffState(attempt, now + delayMs)
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
            return needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private inline fun <T> requirePerms(block: () -> T): T = block()

    private data class BackoffState(val attempt: Int, val nextAttemptAtMs: Long)
}

/**
 * Outbound link. Tracks negotiated MTU; queues writes and retries failed
 * ones a bounded number of times before dropping.
 */
internal class OutboundLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onIncoming: (ByteArray) -> Unit,
    private val onClosed: (String, Boolean) -> Unit,
) {
    private val tag = "OutboundLink(${device.address})"
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val reassembler = Reassembler()
    private val writeQueue = ArrayDeque<PendingWrite>()
    private var writing = false
    @Volatile var mtu: Int = BleConstants.DEFAULT_MTU
        private set
    @Volatile private var ready = false
    @Volatile private var everConnected = false
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set

    @SuppressLint("MissingPermission")
    fun connect() {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        everConnected = true
                        runCatching { g.requestMtu(517) }
                        runCatching { g.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) this@OutboundLink.mtu = mtu
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(BleConstants.SERVICE_UUID) ?: run {
                    Log.w(tag, "service not found"); close(); return
                }
                writeChar = svc.getCharacteristic(BleConstants.CHAR_WRITE_UUID)
                val notifyCh = svc.getCharacteristic(BleConstants.CHAR_NOTIFY_UUID)
                if (notifyCh != null) {
                    runCatching { g.setCharacteristicNotification(notifyCh, true) }
                    val cccd = notifyCh.getDescriptor(BleConstants.CCCD_UUID)
                    if (cccd != null) {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        runCatching { g.writeDescriptor(cccd) }
                    }
                }
                ready = true
                pumpQueue()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val data = characteristic.value ?: return
                lastActivityMs = System.currentTimeMillis()
                val full = reassembler.feed(data)
                if (full != null) onIncoming(full)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                synchronized(writeQueue) {
                    writing = false
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        // Retry the front item up to MAX_WRITE_ATTEMPTS times.
                        val front = writeQueue.firstOrNull()
                        if (front != null) {
                            front.attempts = front.attempts + 1
                            if (front.attempts >= BleConstants.MAX_WRITE_ATTEMPTS) {
                                writeQueue.removeFirst()
                            }
                        }
                    } else {
                        writeQueue.removeFirstOrNull()
                    }
                    Unit
                }
                pumpQueue()
            }
        })
    }

    fun enqueueWrite(chunk: ByteArray, hint: SendHint = SendHint.RELIABLE) {
        synchronized(writeQueue) {
            // Cap queue depth so a stuck link doesn't OOM us.
            while (writeQueue.size >= BleConstants.MAX_WRITE_QUEUE) writeQueue.removeFirst()
            writeQueue.addLast(PendingWrite(chunk, hint = hint))
        }
        pumpQueue()
    }

    @SuppressLint("MissingPermission")
    private fun pumpQueue() {
        if (!ready) return
        val ch = writeChar ?: return
        val g = gatt ?: return
        synchronized(writeQueue) {
            if (writing) return
            val next = writeQueue.firstOrNull() ?: return
            writing = true
            ch.writeType = if (next.hint == SendHint.LOW_LATENCY) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            ch.value = next.bytes
            val ok = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false) == true
            if (next.hint == SendHint.LOW_LATENCY) {
                // No callback for write-no-response; treat as instantly done.
                writing = false
                writeQueue.removeFirstOrNull()
                lastActivityMs = System.currentTimeMillis()
            } else if (!ok) {
                writing = false
                next.attempts += 1
                if (next.attempts >= BleConstants.MAX_WRITE_ATTEMPTS) writeQueue.removeFirst()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        if (gatt == null) return
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        ready = false
        onClosed(device.address, everConnected)
    }
}

internal class PendingWrite(
    val bytes: ByteArray,
    var attempts: Int = 0,
    val hint: SendHint = SendHint.RELIABLE,
)
