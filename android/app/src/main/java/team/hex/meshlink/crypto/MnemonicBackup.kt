package team.hex.meshlink.crypto

import java.security.MessageDigest

/**
 * Identity recovery code.
 *
 * Persistent identity = (edPriv, edPub, xPriv, xPub) = 4 × 32 bytes. The
 * X25519 key is derivable from the Ed25519 seed in principle but the two
 * key generations in this app are independent, so we back up both raw
 * private halves explicitly. Format:
 *
 *   "meshlink-recovery:1:" + base32_no_pad(edPriv ‖ xPriv ‖ sha256(edPriv‖xPriv)[0..3])
 *
 * The four-byte trailing checksum guards against typos; the base32
 * alphabet is Crockford's (0–9, a–z minus i,l,o,u) so the user can read
 * it aloud without ambiguity. 64 bytes of payload + 4 bytes checksum
 * → 68 bytes → 109 base32 characters; we display them in groups of 5.
 *
 * **Anyone with this string can impersonate you.** The UI surfaces it
 * only after an explicit user action and recommends offline storage.
 */
object MnemonicBackup {

    private const val PREFIX = "meshlink-recovery:1:"
    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    fun exportPhrase(identity: Crypto.IdentityKeys): String {
        val payload = identity.edPriv + identity.xPriv
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val checksum = digest.copyOfRange(0, 4)
        val raw = payload + checksum
        val encoded = base32(raw)
        // Group into 5-char clusters separated by dashes for readability.
        val grouped = encoded.chunked(5).joinToString("-")
        return "$PREFIX$grouped"
    }

    /**
     * Rebuild an [Crypto.IdentityKeys] from a recovery phrase. Returns
     * null on bad checksum / malformed payload. The Ed25519 + X25519
     * public halves are recomputed from the private seeds via Bouncy
     * Castle so the caller doesn't need to record them.
     */
    fun importPhrase(text: String): Crypto.IdentityKeys? {
        val body = text.trim().removePrefix(PREFIX).replace("-", "").lowercase()
        val raw = base32Decode(body) ?: return null
        if (raw.size != 32 + 32 + 4) return null
        val edPriv = raw.copyOfRange(0, 32)
        val xPriv = raw.copyOfRange(32, 64)
        val checksum = raw.copyOfRange(64, 68)
        val expect = MessageDigest.getInstance("SHA-256").digest(edPriv + xPriv)
        if (!checksum.contentEquals(expect.copyOfRange(0, 4))) return null

        val edPub = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(edPriv, 0)
            .generatePublicKey().encoded
        val xPub = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(xPriv, 0)
            .generatePublicKey().encoded
        return Crypto.IdentityKeys(edPriv, edPub, xPriv, xPub)
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(value ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(value shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray? {
        var value = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (ch in s) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.add(((value ushr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
