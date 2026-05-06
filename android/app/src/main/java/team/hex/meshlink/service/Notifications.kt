@file:SuppressLint("MissingPermission")

package team.hex.meshlink.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import team.hex.meshlink.R
import team.hex.meshlink.ui.MainActivity

/**
 * In-process push notifications for mesh chat events. We don't use FCM —
 * the foreground service that owns the mesh stack also surfaces incoming
 * messages straight to the system tray, working with no internet at all.
 *
 *   - Channel `meshlink_messages` carries chat messages (peer + group).
 *   - Channel `meshlink_service` (defined elsewhere) carries the ongoing
 *     "mesh active" foreground notification.
 *
 * Each notification is keyed by its scope id (peer id or group id) so
 * subsequent messages from the same chat update the existing post
 * instead of stacking infinitely. Tapping the notification deep-links
 * into the chat for that scope.
 */
object Notifications {

    const val CHANNEL_MESSAGES = "meshlink_messages"
    const val CHANNEL_PAIRING = "meshlink_pairing"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            val ch = NotificationChannel(
                CHANNEL_MESSAGES,
                ctx.getString(R.string.notif_messages_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.notif_messages_desc)
                enableLights(true)
                enableVibration(true)
            }
            mgr.createNotificationChannel(ch)
        }
        if (mgr.getNotificationChannel(CHANNEL_PAIRING) == null) {
            val ch = NotificationChannel(
                CHANNEL_PAIRING,
                ctx.getString(R.string.notif_pairing_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            mgr.createNotificationChannel(ch)
        }
    }

    /**
     * Post a chat-message notification. [scopeId] is the peer node id
     * (1:1 chat) or the group id (group chat); [scopeKind] is one of the
     * `MeshService.SCOPE_*` constants.
     */
    fun postMessage(
        ctx: Context,
        scopeId: String,
        scopeKind: String,
        title: String,
        text: String,
    ) {
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_SCOPE_ID, scopeId)
            putExtra(EXTRA_OPEN_SCOPE_KIND, scopeKind)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, scopeId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(idFor(scopeId), notif) }
    }

    fun cancelForScope(ctx: Context, scopeId: String) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(idFor(scopeId)) }
    }

    /** Surface a trust-on-first-use mismatch on the pairing channel. */
    fun postTrustWarning(ctx: Context, scopeId: String, title: String, text: String) {
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, scopeId.hashCode() xor 0x55, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_PAIRING)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx).notify(0x20_00_00_00 or (scopeId.hashCode() and 0xFFFFFF), notif)
        }
    }

    private fun idFor(scopeId: String): Int = 0x10_00_00_00 or (scopeId.hashCode() and 0x0FFFFFFF)

    const val EXTRA_OPEN_SCOPE_ID = "meshlink.open_scope_id"
    const val EXTRA_OPEN_SCOPE_KIND = "meshlink.open_scope_kind"
}
