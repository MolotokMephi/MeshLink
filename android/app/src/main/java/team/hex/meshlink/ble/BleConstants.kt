package team.hex.meshlink.ble

import java.util.UUID

/**
 * Stable UUIDs for the MeshLink BLE GATT service. These are random v4 UUIDs;
 * they have to match across all peers so devices can advertise & filter scans
 * by a single service UUID.
 */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("a4b18b30-4d8c-4c9c-9b9e-2f3c4e8a1f01")

    /** Frame ingress: writers (centrals) push frames here. */
    val CHAR_WRITE_UUID: UUID = UUID.fromString("a4b18b30-4d8c-4c9c-9b9e-2f3c4e8a1f02")

    /** Frame egress: peripherals notify subscribers with reassembled frames. */
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("a4b18b30-4d8c-4c9c-9b9e-2f3c4e8a1f03")

    /** Standard CCCD (0x2902). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Conservative chunk size that fits in default MTU (23) - 3 ATT - 6 frame hdr. */
    const val DEFAULT_CHUNK_PAYLOAD: Int = 14

    /** Bumped target chunk after MTU negotiation (517 - 3 - 6 = 508). */
    const val LARGE_CHUNK_PAYLOAD: Int = 244 // safer plateau for cross-vendor stability
}
