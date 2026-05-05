package team.hex.meshlink.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * Persists the device identity (Ed25519 + X25519 keys) in SharedPreferences.
 *
 * TODO(security): migrate to Android Keystore-wrapped storage. Right now keys
 * sit on disk as base64; that's fine for an MVP but not for production.
 */
class IdentityStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("meshlink_identity", Context.MODE_PRIVATE)

    fun loadOrCreate(): Crypto.IdentityKeys {
        val edPriv = prefs.getString(K_ED_PRIV, null)?.let(::dec)
        val edPub = prefs.getString(K_ED_PUB, null)?.let(::dec)
        val xPriv = prefs.getString(K_X_PRIV, null)?.let(::dec)
        val xPub = prefs.getString(K_X_PUB, null)?.let(::dec)
        if (edPriv != null && edPub != null && xPriv != null && xPub != null) {
            return Crypto.IdentityKeys(edPriv, edPub, xPriv, xPub)
        }
        val fresh = Crypto.generateIdentity()
        prefs.edit()
            .putString(K_ED_PRIV, enc(fresh.edPriv))
            .putString(K_ED_PUB, enc(fresh.edPub))
            .putString(K_X_PRIV, enc(fresh.xPriv))
            .putString(K_X_PUB, enc(fresh.xPub))
            .apply()
        return fresh
    }

    fun displayName(): String = prefs.getString(K_NAME, null) ?: "Anon"
    fun setDisplayName(name: String) {
        prefs.edit().putString(K_NAME, name).apply()
    }

    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val K_ED_PRIV = "ed_priv"
        private const val K_ED_PUB = "ed_pub"
        private const val K_X_PRIV = "x_priv"
        private const val K_X_PUB = "x_pub"
        private const val K_NAME = "display_name"
    }
}
