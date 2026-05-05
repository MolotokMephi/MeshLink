package team.hex.meshlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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
import team.hex.meshlink.files.FileTransfer
import team.hex.meshlink.groups.GroupInvitePayload
import team.hex.meshlink.groups.Groups
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MeshRouter
import team.hex.meshlink.mesh.MsgType
import team.hex.meshlink.mesh.Outbox
import team.hex.meshlink.mesh.PeerIdentity
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow
import team.hex.meshlink.storage.RoomSeenStore
import team.hex.meshlink.transport.LanTransport
import team.hex.meshlink.transport.SendHint
import team.hex.meshlink.transport.Transport
import team.hex.meshlink.transport.WifiDirectTransport

/**
 * Foreground service. Owns:
 *   - Identity (loaded by [MeshLinkApp])
 *   - Multiple transports (BLE, LAN multicast, optional Wi-Fi Direct)
 *   - The MeshRouter (signing, dedup, anti-replay, rate-limit, flooding)
 *   - The reliable Outbox
 *   - File transfer + group chat helpers
 *
 * UI binds via the local binder for sending messages and triggering
 * pairing flows.
 */
class MeshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    private lateinit var transports: List<Transport>
    private lateinit var router: MeshRouter
    private lateinit var outbox: Outbox
    private lateinit var fileTransfer: FileTransfer
    private lateinit var groups: Groups
    private lateinit var db: MeshDb
    private lateinit var identity: Crypto.IdentityKeys

    inner class LocalBinder : Binder() {
        val service get() = this@MeshService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundCompat()

        val app = applicationContext as MeshLinkApp
        identity = app.identity
        db = MeshDb.get(this)

        router = MeshRouter(
            identity = identity,
            displayName = app.identityStore.displayName(),
            seenStore = RoomSeenStore(db),
        )
        outbox = Outbox(db, router)
        fileTransfer = FileTransfer(this, db, router) { peerId, type, payload ->
            sendEncrypted1to1(peerId, type, payload)
        }
        groups = Groups(db) { peerId, type, payload ->
            sendEncrypted1to1(peerId, type, payload)
        }

        transports = listOf(
            BleTransport(this),
            LanTransport(this),
            WifiDirectTransport(this),
        )

        pumpJob = scope.launch {
            // Block transport ingestion until the persistent seen-cache is in
            // place, so we don't double-process messages that already finished
            // a previous boot cycle.
            router.hydrate()
            for (t in transports) launch { t.incoming.collect { router.onIncoming(it) } }
            launch { router.outgoing.collect { msg ->
                val hint = if (msg.type in LOW_LATENCY_TYPES) SendHint.LOW_LATENCY else SendHint.RELIABLE
                val bytes = msg.toBytes()
                for (t in transports) t.broadcast(bytes, hint)
            } }
            launch { collectAppInbox() }
            launch { collectPeers() }
            launch { collectIdentityConflicts() }
            launch { announceLoop() }
        }

        for (t in transports) t.start()
        outbox.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        pumpJob?.cancel()
        outbox.stop()
        for (t in transports) runCatching { t.stop() }
        scope.cancel()
        super.onDestroy()
    }

    // ----- API exposed to UI -----

    fun router(): MeshRouter = router
    fun groupsHelper(): Groups = groups
    fun fileTransfer(): FileTransfer = fileTransfer

    /** Pair a peer out-of-band (QR / shared string). */
    suspend fun acceptPairing(payload: PairingPayload) {
        val pid = payload.toIdentity()
        router.trustPeer(pid)
        db.peerDao().upsert(PeerRow(
            nodeId = pid.nodeId,
            name = pid.displayName,
            edPub = pid.edPub,
            xPub = pid.xPub,
            lastSeenMs = pid.lastSeenMs,
            trusted = true,
        ))
    }

    /** Signed text via outbox (reliable, retried until DELIVERY_ACK). */
    suspend fun sendText(peerNodeId: String, text: String) {
        val xPub = router.peerById(peerNodeId)?.xPub
            ?: db.peerDao().byId(peerNodeId)?.xPub
            ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        val ct = Crypto.aesGcmEncrypt(sessionKey, text.toByteArray(Charsets.UTF_8))
        val envelope = router.buildSigned(MsgType.TEXT, ct, recipientId = peerNodeId)

        db.chatDao().upsert(ChatMessageRow(
            msgId = envelope.msgId,
            scopeId = peerNodeId,
            scopeKind = SCOPE_PEER,
            senderId = router.nodeId,
            outgoing = true,
            body = text,
            ts = envelope.timestamp,
            delivery = "pending",
        ))
        outbox.enqueue(envelope)
    }

    /** Group message: encrypt under shared key, broadcast with groupId. */
    suspend fun sendGroupText(groupId: String, text: String) {
        val ct = groups.encryptGroupText(groupId, text) ?: return
        val msgId = router.send(MsgType.GROUP_TEXT, ct, recipientId = null, groupId = groupId)
        db.chatDao().upsert(ChatMessageRow(
            msgId = msgId,
            scopeId = groupId,
            scopeKind = SCOPE_GROUP,
            senderId = router.nodeId,
            outgoing = true,
            body = text,
            ts = System.currentTimeMillis(),
            delivery = "sent",
        ))
    }

    suspend fun sendTyping(peerNodeId: String) {
        val xPub = router.peerById(peerNodeId)?.xPub ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        val ct = Crypto.aesGcmEncrypt(sessionKey, ByteArray(0))
        router.send(MsgType.TYPING, ct, recipientId = peerNodeId, ttl = 4)
    }

    suspend fun sendReadReceipt(peerNodeId: String, msgId: String) {
        val xPub = router.peerById(peerNodeId)?.xPub ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        val ct = Crypto.aesGcmEncrypt(sessionKey, msgId.toByteArray(Charsets.UTF_8))
        router.send(MsgType.READ_RECEIPT, ct, recipientId = peerNodeId, ttl = 4)
    }

    suspend fun offerFile(peerId: String, uri: Uri, displayName: String): String =
        fileTransfer.offer(peerId, uri, displayName)

    /**
     * Generic helper used by FileTransfer/Groups: encrypt arbitrary payload
     * with peer's session key, send via mesh router.
     */
    private suspend fun sendEncrypted1to1(peerId: String, type: Int, payload: ByteArray) {
        val xPub = router.peerById(peerId)?.xPub
            ?: db.peerDao().byId(peerId)?.xPub
            ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        val ct = Crypto.aesGcmEncrypt(sessionKey, payload)
        router.send(type, ct, recipientId = peerId)
    }

    private suspend fun collectAppInbox() {
        router.appInbox.collect { msg: MeshMessage ->
            when (msg.type) {
                MsgType.TEXT -> handleIncomingText(msg)
                MsgType.GROUP_TEXT -> handleIncomingGroupText(msg)
                MsgType.GROUP_INVITE -> handleIncomingGroupInvite(msg)
                MsgType.PING -> router.send(MsgType.PONG, ByteArray(0), recipientId = msg.senderId, ttl = 4)
                MsgType.READ_RECEIPT -> handleReadReceipt(msg)
                MsgType.TYPING -> { /* surfaced via UI subscription in future */ }
                MsgType.DELIVERY_ACK -> {
                    val acked = decryptFromPeer(msg)?.toString(Charsets.UTF_8) ?: return@collect
                    outbox.ack(acked)
                    db.chatDao().setDelivery(acked, "delivered")
                }
                MsgType.FILE_OFFER, MsgType.FILE_ACCEPT, MsgType.FILE_REJECT,
                MsgType.FILE_CHUNK, MsgType.FILE_COMPLETE -> {
                    val pt = decryptFromPeer(msg) ?: return@collect
                    fileTransfer.onIncomingPlaintext(msg, pt)
                }
                else -> { /* ANNOUNCE handled inside router; others ignored */ }
            }
        }
    }

    private suspend fun handleIncomingText(msg: MeshMessage) {
        val pt = decryptFromPeer(msg) ?: return
        val text = pt.toString(Charsets.UTF_8)
        db.chatDao().upsert(ChatMessageRow(
            msgId = msg.msgId,
            scopeId = msg.senderId,
            scopeKind = SCOPE_PEER,
            senderId = msg.senderId,
            outgoing = false,
            body = text,
            ts = msg.timestamp,
            delivery = "delivered",
        ))
        Notifications.postMessage(
            this,
            scopeId = msg.senderId,
            scopeKind = SCOPE_PEER,
            title = router.peerById(msg.senderId)?.displayName ?: msg.senderName,
            text = text,
        )
        // Send DELIVERY_ACK back to sender (encrypted).
        val xPub = router.peerById(msg.senderId)?.xPub ?: return
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        val ack = Crypto.aesGcmEncrypt(sessionKey, msg.msgId.toByteArray(Charsets.UTF_8))
        router.send(MsgType.DELIVERY_ACK, ack, recipientId = msg.senderId, ttl = 6)
    }

    private suspend fun handleIncomingGroupText(msg: MeshMessage) {
        val groupId = msg.groupId ?: return
        val ct = Crypto.unb64(msg.payloadB64)
        val text = groups.decryptGroupText(groupId, ct) ?: return
        db.chatDao().upsert(ChatMessageRow(
            msgId = msg.msgId,
            scopeId = groupId,
            scopeKind = SCOPE_GROUP,
            senderId = msg.senderId,
            outgoing = false,
            body = text,
            ts = msg.timestamp,
            delivery = "delivered",
        ))
        val groupName = db.groupDao().byId(groupId)?.name ?: "Group"
        val senderName = router.peerById(msg.senderId)?.displayName ?: msg.senderName
        Notifications.postMessage(
            this,
            scopeId = groupId,
            scopeKind = SCOPE_GROUP,
            title = groupName,
            text = "$senderName: $text",
        )
    }

    private suspend fun handleIncomingGroupInvite(msg: MeshMessage) {
        val pt = decryptFromPeer(msg) ?: return
        val invite = GroupInvitePayload.fromBytes(pt) ?: return
        groups.acceptInvite(invite)
    }

    private suspend fun handleReadReceipt(msg: MeshMessage) {
        val pt = decryptFromPeer(msg) ?: return
        db.chatDao().setDelivery(pt.toString(Charsets.UTF_8), "read")
    }

    private fun decryptFromPeer(msg: MeshMessage): ByteArray? {
        val xPub = router.peerById(msg.senderId)?.xPub ?: return null
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        return runCatching {
            Crypto.aesGcmDecrypt(sessionKey, Crypto.unb64(msg.payloadB64))
        }.getOrNull()
    }

    private suspend fun collectPeers() {
        router.peerEvents.collect { p: PeerIdentity ->
            db.peerDao().upsert(PeerRow(
                nodeId = p.nodeId,
                name = p.displayName,
                edPub = p.edPub,
                xPub = p.xPub,
                lastSeenMs = p.lastSeenMs,
            ))
        }
    }

    private suspend fun collectIdentityConflicts() {
        router.identityConflicts.collect { conflict ->
            // Down-grade trust on the existing peer record so UI surfaces it.
            db.peerDao().setTrusted(conflict.current.nodeId, false)
            Notifications.postTrustWarning(
                ctx = this,
                scopeId = conflict.current.nodeId,
                title = "Identity changed for ${conflict.current.displayName}",
                text = "This peer's signing key changed. They may have reset their device — " +
                    "or someone is impersonating them. Re-pair to restore trust.",
            )
        }
    }

    private suspend fun announceLoop() {
        while (true) {
            router.broadcastAnnounce()
            router.graph.gc()
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
            Intent(this, team.hex.meshlink.ui.MainActivity::class.java),
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
                FOREGROUND_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(FOREGROUND_ID, notif)
        }
    }

    companion object {
        private const val FOREGROUND_ID = 0xBEEF
        const val SCOPE_PEER = "peer"
        const val SCOPE_GROUP = "group"

        /**
         * Message types where dropping a single in-flight chunk costs less
         * than blocking the link with retries — chat texts, typing pings,
         * announces, read receipts. Files and acks stay reliable.
         */
        private val LOW_LATENCY_TYPES = setOf(
            MsgType.TYPING,
            MsgType.PING,
            MsgType.PONG,
            MsgType.ANNOUNCE,
            MsgType.READ_RECEIPT,
        )

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
