package team.hex.meshlink.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.OutboxRow
import kotlin.math.min

/**
 * Reliable outbox for messages that require acknowledgement.
 *
 * Caller persists an envelope by calling [enqueue]; the manager wakes on a
 * timer, fetches everything `next_attempt_at <= now`, re-emits it to the
 * mesh router, and reschedules with exponential backoff. Once a
 * DELIVERY_ACK arrives for the msg_id, the corresponding row is marked
 * acked and eventually trimmed.
 *
 * Maximum attempts and base delay are tuned for a sparse, lossy mesh
 * where a recipient may be offline for minutes at a time.
 */
class Outbox(
    private val db: MeshDb,
    private val router: MeshRouter,
    private val maxAttempts: Int = 12,
    private val baseDelayMs: Long = 5_000L,
    private val maxDelayMs: Long = 5 * 60_000L,
    private val pumpIntervalMs: Long = 5_000L,
) {
    private val tag = "Outbox"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpJob: Job? = null

    fun start() {
        if (pumpJob != null) return
        pumpJob = scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Log.w(tag, "tick failed: $it") }
                delay(pumpIntervalMs)
            }
        }
    }

    fun stop() {
        pumpJob?.cancel(); pumpJob = null
    }

    /** Persist a signed envelope and inject it into the mesh once. */
    suspend fun enqueue(envelope: MeshMessage) {
        db.outboxDao().upsert(
            OutboxRow(
                msgId = envelope.msgId,
                recipientId = envelope.recipientId.orEmpty(),
                type = envelope.type,
                frame = envelope.toBytes(),
                attempts = 1,
                nextAttemptAt = System.currentTimeMillis() + baseDelayMs,
                acked = false,
            )
        )
        router.emit(envelope)
    }

    /** Called by the service when DELIVERY_ACK arrives. */
    suspend fun ack(msgId: String) {
        db.outboxDao().markAcked(msgId)
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val due = db.outboxDao().due(now, limit = 32)
        for (row in due) {
            val parsed = runCatching { MeshMessage.fromBytes(row.frame) }.getOrNull()
            if (parsed == null) {
                db.outboxDao().reschedule(row.msgId, maxAttempts, Long.MAX_VALUE)
                continue
            }
            // Refresh timestamp+nonce so the retry isn't rejected by the
            // recipient's anti-replay clock-skew window.
            val refreshed = router.refreshAndSign(parsed)
            db.outboxDao().upsert(OutboxRow(
                msgId = row.msgId,
                recipientId = row.recipientId,
                type = row.type,
                frame = refreshed.toBytes(),
                attempts = row.attempts + 1,
                nextAttemptAt = now + nextDelay(row.attempts),
                acked = row.acked,
                createdAt = row.createdAt,
            ))
            router.resend(refreshed)
        }
        // Janitor.
        db.outboxDao().dropExhausted(maxAttempts)
        db.outboxDao().trimAcked(before = now - 24L * 3600 * 1000)
    }

    private fun nextDelay(attempts: Int): Long =
        min(maxDelayMs, baseDelayMs * (1L shl attempts.coerceIn(0, 6)))
}
