package team.hex.meshlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.R
import team.hex.meshlink.ble.BleTransport
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MeshRouter
import team.hex.meshlink.mesh.MsgType
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow
import team.hex.meshlink.ui.MainActivity

/**
 * Foreground service that owns the BLE transport + MeshRouter for the
 * lifetime of the app session. UI binds to it for sending messages and
 * observing peer state.
 */
class MeshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    private lateinit var transport: BleTransport
    private lateinit var router: MeshRouter
    private lateinit var db: MeshDb
    private lateinit var identity: Crypto.IdentityKeys

    inner class LocalBinder : Binder() {
        val service get() = this@MeshService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()

        val app = applicationContext as MeshLinkApp
        identity = app.identity
        router = MeshRouter(identity, app.identityStore.displayName())
        transport = BleTransport(this)
        db = MeshDb.get(this)

        // Wire transport <-> router
        pumpJob = scope.launch {
            launch { transport.incoming.collect { router.onIncoming(it) } }
            launch { router.outgoing.collect { transport.broadcast(it.toBytes()) } }
            launch { collectAppInbox() }
            launch { collectPeers() }
            launch { announceLoop() }
        }

        transport.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        pumpJob?.cancel()
        scope.cancel()
        runCatching { transport.stop() }
        super.onDestroy()
    }

    // ----- API exposed to UI via binder -----

    fun router(): MeshRouter = router

    /**
     * Send an encrypted text message to [peer]. Payload is AES-GCM ciphertext
     * keyed by ECDH(myXPriv, peerXPub). The ciphertext is then placed inside a
     * signed mesh envelope.
     */
    suspend fun sendText(peerNodeId: String, text: String) {
        val xPub = router.peerById(peerNodeId)?.xPub
            ?: db.peerDao().byId(peerNodeId)?.xPub
            ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        val ct = Crypto.aesGcmEncrypt(sessionKey, text.toByteArray(Charsets.UTF_8))
        router.send(MsgType.TEXT, ct, recipientId = peerNodeId)

        db.chatDao().upsert(
            ChatMessageRow(
                msgId = "local-${System.nanoTime()}",
                peerId = peerNodeId,
                outgoing = true,
                body = text,
                ts = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun collectAppInbox() {
        router.appInbox.collect { msg: MeshMessage ->
            when (msg.type) {
                MsgType.TEXT -> handleIncomingText(msg)
                MsgType.PING -> router.send(MsgType.PONG, ByteArray(0), recipientId = msg.senderId, ttl = 4)
                else -> { /* ANNOUNCE handled inside router; others ignored for now */ }
            }
        }
    }

    private suspend fun handleIncomingText(msg: MeshMessage) {
        val peer = router.peerById(msg.senderId) ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, peer.xPub)
        val pt = runCatching {
            Crypto.aesGcmDecrypt(sessionKey, Crypto.unb64(msg.payloadB64))
        }.getOrNull() ?: return
        val text = pt.toString(Charsets.UTF_8)
        db.chatDao().upsert(
            ChatMessageRow(
                msgId = msg.msgId,
                peerId = msg.senderId,
                outgoing = false,
                body = text,
                ts = msg.timestamp,
            )
        )
    }

    private suspend fun collectPeers() {
        router.peerEvents.collect { p ->
            db.peerDao().upsert(
                PeerRow(
                    nodeId = p.nodeId,
                    name = p.displayName,
                    edPub = p.edPub,
                    xPub = p.xPub,
                    lastSeenMs = p.lastSeenMs,
                )
            )
        }
    }

    private suspend fun announceLoop() {
        while (true) {
            router.broadcastAnnounce()
            delay(15_000)
        }
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                getString(R.string.notif_channel_id),
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(FOREGROUND_ID, notif)
        }
    }

    companion object {
        private const val FOREGROUND_ID = 0xBEEF

        fun start(ctx: Context) {
            val intent = Intent(ctx, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
