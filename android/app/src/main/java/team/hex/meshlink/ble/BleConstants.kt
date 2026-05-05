package team.hex.meshlink.ble

import java.util.UUID

/**
 * Stable UUIDs and tunables for the MeshLink BLE GATT service.
 * Random v4 UUIDs; they have to match across all peers so devices can
 * advertise & filter scans by a single service UUID.
 */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("a4b18b30-4d8c-4c9c-9b9e-2f3c4e8a1f01")
    val CHAR_WRITE_UUID: UUID = UUID.fromString("a4b18b30-4d8c-4c9c-9b9e-2f3c4e8a1f02")
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("a4b18b30-4d8c-4c9c-9b9e-2f3c4e8a1f03")

    /** Standard CCCD (0x2902). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Default ATT MTU when nothing has been negotiated. */
    const val DEFAULT_MTU = 23

    /** Hard floor for chunk payload (matches default MTU). */
    const val MIN_CHUNK_PAYLOAD = 12

    /** Cross-vendor stable ceiling for chunk payload (~244 bytes per write). */
    const val LARGE_CHUNK_PAYLOAD = 244

    /** Max simultaneous outbound GATT links; vendor stacks typically support 4–7. */
    const val MAX_OUTBOUND_LINKS = 5

    /** Per-write retry cap before we drop a chunk. */
    const val MAX_WRITE_ATTEMPTS = 3

    /** Per-link write queue cap (oldest pruned first). */
    const val MAX_WRITE_QUEUE = 256

    const val BACKOFF_BASE_MS = 2_000L
    const val BACKOFF_MAX_MS = 60_000L
}
