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
import team.hex.meshlink.groups.PeerChain
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MeshRouter
import team.hex.meshlink.mesh.MsgType
import team.hex.meshlink.mesh.Outbox
import team.hex.meshlink.mesh.PeerIdentity
import team.hex.meshlink.mesh.SenderKeyDistribution
import team.hex.meshlink.mesh.WifiHintPayload
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow
import team.hex.meshlink.storage.RoomSeenStore
import team.hex.meshlink.transport.LanTransport
import team.hex.meshlink.transport.LoraTransport
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
    private lateinit var peerChain: PeerChain
    private lateinit var voiceRecorder: VoiceRecorder
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
            initialDisplayName = app.identityStore.displayName(),
            seenStore = RoomSeenStore(db),
        )
        outbox = Outbox(db, router)
        fileTransfer = FileTransfer(this, db, router) { peerId, type, payload ->
            sendEncrypted1to1(peerId, type, payload)
        }
        groups = Groups(db, identity.nodeId()) { peerId, type, payload ->
            sendEncrypted1to1(peerId, type, payload)
        }
        peerChain = PeerChain(db, identity.nodeId(), identity.xPriv)
        voiceRecorder = VoiceRecorder(this)

        transports = listOf(
            BleTransport(this),
            LanTransport(this),
            WifiDirectTransport(this),
            LoraTransport(this),
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
            // Transports must start AFTER the incoming collectors are wired
            // up — `incoming` is a SharedFlow with no replay, so frames
            // emitted before subscription land in the void. On startup that
            // tended to lose the very first ANNOUNCE from peers already in
            // range, leaving the local mesh "blind" until the next 15s tick.
            for (t in transports) runCatching { t.start() }
            launch { announceLoop() }
        }

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
        // Forward-secret per-message ratchet — each text consumes a chain
        // key step on our send chain to this peer.
        val ct = peerChain.encrypt(peerNodeId, xPub, text.toByteArray(Charsets.UTF_8)) ?: return
        val envelope = router.buildSigned(MsgType.TEXT, ct, recipientId = peerNodeId)

        db.chatDao().upsert(ChatMessageRow(
            msgId = envelope.msgId,
            scopeId = peerNodeId,
            scopeKind = SCOPE_PEER,
            senderId = router.nodeId,
            outgoing = true,
            body = text,
            ts = envelope.timestamp,
            // Optimistic — the envelope is on the wire as soon as enqueue
            // returns. Outbox flips to "delivered" on DELIVERY_ACK or to
            // "failed" once we've exhausted retries; the user no longer
            // stares at the pending dots forever when the recipient is
            // genuinely offline.
            delivery = "sent",
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
     * Snapshot of every transport's runtime state so the home screen can
     * show a "Bluetooth not granted / Wi-Fi unavailable" banner instead
     * of leaving the user staring at an empty peer list with no idea why.
     */
    fun transportHealth(): List<TransportHealth> = transports.map { t ->
        TransportHealth(
            name = t.name,
            state = t.state.value,
            liveLinks = t.liveLinkCount,
            details = t.details,
        )
    }

    /**
     * Re-run start() on every transport that's currently Failed or
     * Stopped. Called from the UI after the user grants additional
     * permissions or toggles Bluetooth on — without this, the perm
     * change was invisible until the foreground service died and
     * respawned.
     */
    fun restartTransports() {
        for (t in transports) {
            val s = t.state.value
            if (s == team.hex.meshlink.transport.TransportState.Failed ||
                s == team.hex.meshlink.transport.TransportState.Stopped) {
                runCatching { t.start() }
            }
        }
    }

    fun directNeighbourCount(): Int = router.graph.snapshot().directNeighbours

    /**
     * Persist a new display name and refresh the router so the next
     * outgoing announce carries it. Without this hook, the prefs change
     * was invisible to the mesh until the foreground service restarted.
     */
    fun setDisplayName(name: String) {
        val app = applicationContext as MeshLinkApp
        app.identityStore.setDisplayName(name)
        router.displayName = name
        scope.launch { router.broadcastAnnounce() }
    }

    /** Tap-and-hold UI calls this when the user starts holding the mic. */
    fun startVoiceNote(): Boolean = voiceRecorder.start()

    /**
     * Stop the active voice recording and ship it to [peerId] as a file
     * transfer. Returns true on success, false if there was no active
     * recording or the file couldn't be sent.
     */
    suspend fun stopAndSendVoiceNote(peerId: String): Boolean {
        val file = voiceRecorder.stop() ?: return false
        if (!file.exists() || file.length() <= 0) {
            file.delete(); return false
        }
        runCatching {
            fileTransfer.offer(peerId, file.asVoiceNoteUri(),
                "voice-${file.nameWithoutExtension}.m4a")
        }
        return true
    }

    /** Drop the recording without sending. */
    fun cancelVoiceNote() = voiceRecorder.cancel()

    fun isRecordingVoiceNote(): Boolean = voiceRecorder.isRecording

    /** Called by the chat screen on entry; clears unread badges + dismisses tray. */
    suspend fun markScopeRead(scopeId: String, scopeKind: String) {
        db.chatDao().markScopeRead(scopeId, scopeKind)
        Notifications.cancelForScope(this, scopeId)
    }

    /**
     * Generic helper used by FileTransfer/Groups: encrypt arbitrary payload
     * with peer's session key, send via mesh router.
     */
    private suspend fun sendEncrypted1to1(peerId: String, type: Int, payload: ByteArray) {
        val xPub = router.peerById(peerId)?.xPub
            ?: db.peerDao().byId(peerId)?.xPub
            ?: return
        // Route every 1:1 ciphertext through the forward-secret chain so
        // file offers, group invites, sender-key distributions, acks and
        // typing pings all share the same forward-secret guarantees as
        // chat text.
        val ct = peerChain.encrypt(peerId, xPub, payload) ?: run {
            val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
            Crypto.aesGcmEncrypt(sessionKey, payload)
        }
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
                MsgType.WIFI_HINT -> handleWifiHint(msg)
                MsgType.GROUP_SENDER_KEY -> handleSenderKey(msg)
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
            read = false,
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
            read = false,
        ))
        val groupName = db.groupDao().byId(groupId)?.name
            ?: getString(R.string.tab_groups)
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
        groups.acceptInvite(invite, fromPeer = msg.senderId)
    }

    private suspend fun handleReadReceipt(msg: MeshMessage) {
        val pt = decryptFromPeer(msg) ?: return
        db.chatDao().setDelivery(pt.toString(Charsets.UTF_8), "read")
    }

    private suspend fun decryptFromPeer(msg: MeshMessage): ByteArray? {
        val xPub = router.peerById(msg.senderId)?.xPub
            ?: db.peerDao().byId(msg.senderId)?.xPub
            ?: return null
        val raw = Crypto.unb64(msg.payloadB64)
        // Prefer the forward-secret chain ratchet; fall back to legacy
        // raw AES-GCM(session_key) so peers that haven't migrated yet
        // still get their messages decrypted.
        peerChain.decrypt(msg.senderId, xPub, raw)?.let { return it }
        val sessionKey = Crypto.deriveSessionKey(identity.xPriv, xPub)
        return runCatching {
            Crypto.aesGcmDecrypt(sessionKey, raw)
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
                title = getString(R.string.trust_warning_title, conflict.current.displayName),
                text = getString(R.string.trust_warning_text),
            )
        }
    }

    private suspend fun announceLoop() {
        var hintCounter = 0
        while (true) {
            router.broadcastAnnounce()
            router.graph.gc()
            // Every fourth announce (~60s) advertise our Wi-Fi address so
            // peers in the mesh can promote BLE-relayed flows to a fat
            // TCP back-channel for files / voice / large group blasts.
            if (hintCounter++ % 4 == 0) broadcastWifiHint()
            delay(15_000)
        }
    }

    /**
     * Tell the mesh "if you can reach me on this IPv4 / port, prefer it
     * over BLE for everything bigger than a chat message." Receivers feed
     * the address into [LanTransport.connectTcp] (LAN back-channel) and
     * [WifiDirectTransport.connectTo] (WD fat pipe). Unreachable peers
     * silently drop, so this is a hint, not a requirement.
     */
    private fun broadcastWifiHint() {
        val ip = pickLocalIpv4() ?: return
        val payload = WifiHintPayload(
            host = ip,
            lanPort = LanTransport.TCP_PORT,
            wdPort = WifiDirectTransport.LISTEN_PORT,
        ).encode()
        router.send(MsgType.WIFI_HINT, payload, recipientId = null, ttl = 3)
    }

    private fun handleWifiHint(msg: MeshMessage) {
        val hint = WifiHintPayload.decodeOrNull(Crypto.unb64(msg.payloadB64)) ?: return
        // Only chase the upgrade for peers we trust enough to have an
        // identity for — otherwise we'd connect to whoever screams loudest.
        if (router.peerById(msg.senderId) == null) return
        for (t in transports) when (t) {
            is LanTransport -> t.connectTcp(hint.host, hint.lanPort)
            is WifiDirectTransport -> t.connectTo(hint.host, hint.wdPort)
            else -> {}
        }
    }

    private suspend fun handleSenderKey(msg: MeshMessage) {
        val pt = decryptFromPeer(msg) ?: return
        val seed = SenderKeyDistribution.decodeOrNull(pt) ?: return
        groups.acceptSenderKey(seed.groupId, msg.senderId, seed)
    }

    private fun pickLocalIpv4(): String? {
        return runCatching {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (a in iface.inetAddresses) {
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        return@runCatching a.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
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

    /** UI-friendly per-transport status. */
    data class TransportHealth(
        val name: String,
        val state: team.hex.meshlink.transport.TransportState,
        val liveLinks: Int,
        val details: String? = null,
    )

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
