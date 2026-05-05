package team.hex.meshlink

import android.app.Application
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.crypto.IdentityStore

class MeshLinkApp : Application() {
    lateinit var identityStore: IdentityStore
        private set
    lateinit var identity: Crypto.IdentityKeys
        private set

    override fun onCreate() {
        super.onCreate()
        identityStore = IdentityStore(this)
        identity = identityStore.loadOrCreate()
    }
}
