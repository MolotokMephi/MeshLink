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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE peer-to-peer transport.
 *
 * Each device acts as BOTH:
 *   - Peripheral (GATT server) advertising the MeshLink service UUID. Other
 *     peers discover us via scan and connect; they write fragmented frames to
 *     CHAR_WRITE_UUID and subscribe to notifications on CHAR_NOTIFY_UUID.
 *   - Central (GATT client) scanning for peers advertising the service UUID
 *     and connecting outbound to fan out our frames.
 *
 * Frames are pre-fragmented by [Fragmentation] before transmission and
 * reassembled on the receiving side; reassembled frames are emitted on
 * [incoming] and consumed by the MeshRouter.
 *
 * Outbound broadcasting is "send to every connected link". The MeshRouter
 * has already dedup'd and TTL-managed the message; we just push bytes.
 */
class BleTransport(private val context: Context) {

    private val tag = "BleTransport"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val btManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // Devices currently subscribed to our notify characteristic (centrals
    // connected to us). We deliver outbound frames to them via notify().
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()

    // Outbound BLE clients (we are central) keyed by remote address.
    private val outboundLinks = ConcurrentHashMap<String, OutboundLink>()

    // Per-link reassembly buffers.
    private val reassemblersServerSide = ConcurrentHashMap<String, Reassembler>()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    @Volatile private var advertising = false
    @Volatile private var scanning = false

    fun isReady(): Boolean = adapter != null && adapter.isEnabled && hasPermissions()

    fun start() {
        if (!isReady()) {
            Log.w(tag, "BLE not ready (adapter null/off or missing permissions)")
            return
        }
        startGattServer()
        startAdvertising()
        startScanning()
    }

    fun stop() {
        stopAdvertising()
        stopScanning()
        outboundLinks.values.forEach { it.close() }
        outboundLinks.clear()
        subscribers.clear()
        reassemblersServerSide.clear()
        runCatching {
            requirePerms { gattServer?.close() }
        }
        gattServer = null
    }

    /** Broadcast a logical frame to all currently-connected peers. */
    fun broadcast(frame: ByteArray) {
        val msgId = (System.nanoTime() and 0xFFFF).toInt()
        // Pick the more conservative chunk size for cross-stack reliability.
        val chunks = Fragmentation.split(frame, BleConstants.LARGE_CHUNK_PAYLOAD, msgId)
        // Push to every central subscribed to our notify characteristic.
        val notifyCh = notifyChar
        val server = gattServer
        if (notifyCh != null && server != null) {
            for (device in subscribers.values) {
                for (chunk in chunks) {
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
        // Push to every outbound link (we as central writing to peer's CHAR_WRITE).
        for (link in outboundLinks.values) {
            for (chunk in chunks) link.enqueueWrite(chunk)
        }
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
            when (newState) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    subscribers.remove(device.address)
                    reassemblersServerSide.remove(device.address)
                }
            }
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
            if (outboundLinks.containsKey(device.address)) return
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
        val link = OutboundLink(context, device,
            onIncoming = { frame -> scope.launch { _incoming.emit(frame) } },
            onClosed = { addr -> outboundLinks.remove(addr) }
        )
        outboundLinks[device.address] = link
        link.connect()
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
}

/**
 * Outbound (we are central) link to a single peripheral. Writes outbound
 * frames to peer's CHAR_WRITE_UUID, subscribes to its CHAR_NOTIFY_UUID
 * for incoming frames, runs them through a Reassembler.
 */
private class OutboundLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onIncoming: (ByteArray) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val tag = "OutboundLink(${device.address})"
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val reassembler = Reassembler()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    @SuppressLint("MissingPermission")
    fun connect() {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        runCatching { g.requestMtu(517) }
                        runCatching { g.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(tag, "mtu = $mtu")
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
                pumpQueue()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val data = characteristic.value ?: return
                val full = reassembler.feed(data)
                if (full != null) onIncoming(full)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writing = false
                pumpQueue()
            }
        })
    }

    fun enqueueWrite(chunk: ByteArray) {
        synchronized(writeQueue) { writeQueue.addLast(chunk) }
        pumpQueue()
    }

    @SuppressLint("MissingPermission")
    private fun pumpQueue() {
        val ch = writeChar ?: return
        val g = gatt ?: return
        synchronized(writeQueue) {
            if (writing) return
            val next = writeQueue.removeFirstOrNull() ?: return
            writing = true
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = next
            val ok = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false) == true
            if (!ok) writing = false
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        onClosed(device.address)
    }
}
