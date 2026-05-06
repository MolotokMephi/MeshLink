package team.hex.meshlink.pairing

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * NFC-based pairing.
 *
 * Two flows:
 *
 *  - **Read.** When the OS launches us with `ACTION_NDEF_DISCOVERED`,
 *    [NfcPairing.payloadFromIntent] extracts the `meshlink:1:…` string
 *    from the NDEF message and we hand it to the service.
 *  - **Write.** [NfcPairing.writePairingTag] formats a tag with our
 *    own pairing payload so we can hand it to a peer.
 *
 * The encoding is the same `meshlink:1:<base64>` URI the QR encoder
 * produces, so both transports share the [PairingPayload] decode path.
 */
object NfcPairing {

    private const val TAG = "NfcPairing"
    private const val URI_PREFIX = "meshlink:"

    /** Returns the device NfcAdapter or null when NFC isn't available. */
    fun adapter(activity: Activity): NfcAdapter? =
        NfcAdapter.getDefaultAdapter(activity)

    /**
     * Extract a [PairingPayload] from any incoming NFC intent. Returns
     * null when the tag doesn't carry a recognizable MeshLink record.
     */
    fun payloadFromIntent(intent: Intent?): PairingPayload? {
        if (intent == null) return null
        val action = intent.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) return null
        val raw: Array<Parcelable>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
        if (raw.isNullOrEmpty()) return null
        for (parcel in raw) {
            val msg = parcel as? NdefMessage ?: continue
            val text = readText(msg) ?: continue
            val payload = PairingPayload.decodeOrNull(text)
            if (payload != null) return payload
        }
        return null
    }

    /**
     * Write our [payload] into [tag]. Throws on tag-not-ndef or capacity
     * exceeded — callers should guard with try/catch and surface a
     * user-friendly error.
     */
    fun writePairingTag(tag: Tag, payload: PairingPayload): Boolean {
        val record = NdefRecord.createUri(payload.encode())
        val msg = NdefMessage(arrayOf(record, NdefRecord.createApplicationRecord("team.hex.meshlink")))
        val ndef = Ndef.get(tag)
        return try {
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) return false
                if (ndef.maxSize < msg.toByteArray().size) return false
                ndef.writeNdefMessage(msg)
                ndef.close()
                true
            } else {
                val formatable = NdefFormatable.get(tag) ?: return false
                formatable.connect()
                formatable.format(msg)
                formatable.close()
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tag write failed: $t")
            false
        }
    }

    private fun readText(msg: NdefMessage): String? {
        for (record in msg.records) {
            val tnf = record.tnf
            // URI record carrying the meshlink: scheme.
            if (tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type contentEquals NdefRecord.RTD_URI) {
                val raw = record.payload
                if (raw.isNotEmpty()) {
                    // First byte is a URI prefix code; we use 0x00 (no prefix).
                    val uri = String(raw, 1, raw.size - 1, Charsets.UTF_8)
                    if (uri.startsWith(URI_PREFIX)) return uri
                }
            }
            // Plain TEXT record fallback.
            if (tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type contentEquals NdefRecord.RTD_TEXT) {
                val raw = record.payload
                if (raw.size >= 3) {
                    val langLen = raw[0].toInt() and 0x3F
                    val text = String(raw, 1 + langLen, raw.size - 1 - langLen, Charsets.UTF_8)
                    if (text.startsWith(URI_PREFIX)) return text
                }
            }
            // Vendor-specific MIME record.
            if (tnf == NdefRecord.TNF_MIME_MEDIA) {
                val text = String(record.payload, Charsets.UTF_8)
                if (text.startsWith(URI_PREFIX)) return text
            }
        }
        return null
    }
}
