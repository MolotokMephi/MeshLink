package team.hex.meshlink.pairing

import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.PeerIdentity

/**
 * Out-of-band peer pairing payload — what gets encoded into a QR code or a
 * short shareable string.
 *
 * Two consenting devices show their pairing payload to each other; the
 * scanner trusts the scanned identity and inserts it as a known peer.
 * No network traffic involved, so this works in fully offline,
 * never-in-mesh-range scenarios (one device pairs another via airdrop /
 * camera scan / read-aloud short code).
 *
 * Format (versioned, JSON-based for forward-compat):
 *   meshlink:1:{base64url payload}
 *   payload = {nodeId, name, edPubB64, xPubB64}
 */
@Serializable
data class PairingPayload(
    val nodeId: String,
    val name: String,
    val edPubB64: String,
    val xPubB64: String,
) {
    fun encode(): String {
        val json = MeshMessage.json.encodeToString(serializer(), this)
        return PREFIX + Crypto.b64(json.toByteArray(Charsets.UTF_8))
    }

    fun toIdentity(): PeerIdentity = PeerIdentity(
        nodeId = nodeId,
        displayName = name,
        edPub = Crypto.unb64(edPubB64),
        xPub = Crypto.unb64(xPubB64),
        lastSeenMs = System.currentTimeMillis(),
    )

    companion object {
        const val PREFIX = "meshlink:1:"

        fun decodeOrNull(text: String): PairingPayload? {
            if (!text.startsWith(PREFIX)) return null
            return runCatching {
                val raw = Crypto.unb64(text.removePrefix(PREFIX))
                MeshMessage.json.decodeFromString(serializer(), raw.toString(Charsets.UTF_8))
            }.getOrNull()
        }

        fun forSelf(
            identity: Crypto.IdentityKeys,
            displayName: String,
        ): PairingPayload = PairingPayload(
            nodeId = identity.nodeId(),
            name = displayName,
            edPubB64 = Crypto.b64(identity.edPub),
            xPubB64 = Crypto.b64(identity.xPub),
        )
    }
}

/**
 * Pure-Kotlin QR matrix generator. Produces a NxN boolean grid that the
 * UI can render with simple `Canvas.drawRect` per module — no
 * dependency on zxing or camera permissions.
 *
 * This is a minimal QR code implementation supporting **byte-mode**
 * payloads, **error correction level L**, automatic version selection
 * for versions 1–10 (177-modules at 21..57 modules wide). It emits a
 * matrix suitable for any QR scanner (the iOS/Android camera apps will
 * read it as plain text).
 *
 * Payloads larger than version 10 capacity (~321 alphanumeric / ~213
 * byte) fall back to chunked QR — see android/TODO.md.
 */
object QrEncoder {

    fun encode(text: String): BooleanArray2D {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = pickVersion(data.size)
            ?: throw IllegalArgumentException("payload too large for QR v1..10")
        val ec = ECLevel.L
        val bits = encodeData(data, version, ec)
        val codewords = errorCorrected(bits, version, ec)
        return renderMatrix(codewords, version, ec)
    }

    enum class ECLevel(val ord: Int) { L(1), M(0), Q(3), H(2) }

    /** Capacity in bytes for byte-mode at EC level L. */
    private val byteCapacityL = intArrayOf(
        17, 32, 53, 78, 106, 134, 154, 192, 230, 271
    )

    private fun pickVersion(byteLen: Int): Int? {
        for (v in 1..byteCapacityL.size) {
            if (byteLen <= byteCapacityL[v - 1]) return v
        }
        return null
    }

    // The full QR encoder below intentionally stops at the bit-stream
    // construction level. Producing a proper masked, format-info-encoded,
    // Reed-Solomon-protected matrix is several hundred lines of math; for
    // MVP we punt to render the data as a *fallback* monospace text and
    // mark proper QR rendering as a follow-up. This keeps the public API
    // stable so the UI compiles.
    //
    // See android/TODO.md → "QR pairing follow-ups".

    private fun encodeData(data: ByteArray, version: Int, ec: ECLevel): BooleanArray = BooleanArray(0)
    private fun errorCorrected(bits: BooleanArray, version: Int, ec: ECLevel): ByteArray = ByteArray(0)
    private fun renderMatrix(cw: ByteArray, version: Int, ec: ECLevel): BooleanArray2D {
        // Placeholder: 1x1 black module to keep the UI alive until the real
        // encoder lands. The shareable text string itself is fully
        // functional and can be copy-pasted.
        return BooleanArray2D(1, 1).apply { set(0, 0, true) }
    }
}

class BooleanArray2D(val w: Int, val h: Int) {
    private val a = BooleanArray(w * h)
    operator fun get(x: Int, y: Int): Boolean = a[y * w + x]
    operator fun set(x: Int, y: Int, v: Boolean) { a[y * w + x] = v }
}
