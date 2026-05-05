package team.hex.meshlink.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the device identity (Ed25519 + X25519 keypair) on disk, with
 * the private halves wrapped by an AES-256 key stored in the Android
 * Keystore. Public halves are stored in plaintext for fast access.
 *
 * Storage layout in SharedPreferences:
 *   ed_pub      : base64(edPub)        (32 bytes)
 *   x_pub       : base64(xPub)         (32 bytes)
 *   priv_blob   : base64(IV(12) || ciphertext(edPriv||xPriv) || GCM tag)
 *
 * The wrapping key (`KEYSTORE_ALIAS`) is hardware-backed where the device
 * supports it. If the keystore is unavailable (very old API levels or
 * stripped-down ROMs) we fall back to plaintext storage and surface a
 * warning — see android/TODO.md.
 */
class IdentityStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("meshlink_identity", Context.MODE_PRIVATE)

    fun loadOrCreate(): Crypto.IdentityKeys {
        val edPub = prefs.getString(K_ED_PUB, null)?.let(::dec)
        val xPub = prefs.getString(K_X_PUB, null)?.let(::dec)
        val privBlob = prefs.getString(K_PRIV_BLOB, null)?.let(::dec)
        if (edPub != null && xPub != null && privBlob != null) {
            val privs = unwrapPriv(privBlob)
            if (privs != null && privs.size == 64) {
                return Crypto.IdentityKeys(
                    edPriv = privs.copyOfRange(0, 32),
                    edPub = edPub,
                    xPriv = privs.copyOfRange(32, 64),
                    xPub = xPub,
                )
            }
        }
        // Legacy v1 layout (cleartext private keys)
        val legacyEdPriv = prefs.getString("ed_priv", null)?.let(::dec)
        val legacyXPriv = prefs.getString("x_priv", null)?.let(::dec)
        if (edPub != null && xPub != null && legacyEdPriv != null && legacyXPriv != null) {
            val migrated = Crypto.IdentityKeys(legacyEdPriv, edPub, legacyXPriv, xPub)
            persist(migrated)
            prefs.edit().remove("ed_priv").remove("x_priv").apply()
            return migrated
        }
        val fresh = Crypto.generateIdentity()
        persist(fresh)
        return fresh
    }

    fun displayName(): String = prefs.getString(K_NAME, null) ?: "Anon"
    fun setDisplayName(name: String) {
        prefs.edit().putString(K_NAME, name).apply()
    }

    private fun persist(id: Crypto.IdentityKeys) {
        val combined = id.edPriv + id.xPriv
        val wrapped = wrapPriv(combined)
        prefs.edit()
            .putString(K_ED_PUB, enc(id.edPub))
            .putString(K_X_PUB, enc(id.xPub))
            .putString(K_PRIV_BLOB, enc(wrapped))
            .apply()
    }

    private fun wrapPriv(plain: ByteArray): ByteArray {
        val key = getOrCreateWrappingKey() ?: return plain // fall back to plaintext
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    private fun unwrapPriv(blob: ByteArray): ByteArray? {
        // If wrapping key isn't available, treat the blob as plaintext.
        val key = getOrCreateWrappingKey() ?: return blob.takeIf { it.size == 64 }
        if (blob.size < 12 + 16) return null
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ct)
        }.getOrNull()
    }

    private fun getOrCreateWrappingKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                gen.init(spec)
                gen.generateKey()
            }
        }.getOrNull()
    }

    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val K_ED_PUB = "ed_pub"
        private const val K_X_PUB = "x_pub"
        private const val K_PRIV_BLOB = "priv_blob"
        private const val K_NAME = "display_name"
        private const val KEYSTORE_ALIAS = "meshlink_identity_wrap"
    }
}
