package dev.muto.app.vpn

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.muto.app.MainActivity
import dev.muto.app.R

/**
 * The ongoing notification a foreground service must show.
 *
 * It is deliberately quiet - low importance, no sound, no badge - but it carries the two controls
 * people reach for most: pause when a site is broken, and stop.
 */
class VpnNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_status_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: ProtectionState, blockedCount: Long) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(
                context.getString(
                    when (state) {
                        ProtectionState.PAUSED -> R.string.notification_paused
                        ProtectionState.STARTING -> R.string.notification_starting
                        else -> R.string.notification_active
                    },
                ),
            )
            .setContentText(context.resources.getQuantityString(
                R.plurals.notification_blocked_count,
                blockedCount.toInt().coerceAtLeast(0),
                blockedCount,
            ))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                context.getString(
                    if (state == ProtectionState.PAUSED) R.string.action_resume else R.string.action_pause,
                ),
                serviceAction(
                    if (state == ProtectionState.PAUSED) MutoVpnService.ACTION_RESUME else MutoVpnService.ACTION_PAUSE,
                ),
            )
            .addAction(0, context.getString(R.string.action_stop), serviceAction(MutoVpnService.ACTION_STOP))
            .build()

    /**
     * Refreshes the existing notification. Silently does nothing without notification permission,
     * which is fine: the foreground service itself is unaffected.
     */
    @SuppressLint("MissingPermission") // Without the permission the notify is simply dropped;
    // the foreground service and the filtering it does are unaffected either way.
    fun update(state: ProtectionState, blockedCount: Long) {
        runCatching { manager.notify(NOTIFICATION_ID, build(state, blockedCount)) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, MutoVpnService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "muto_status"
        const val NOTIFICATION_ID = 1
    }
}
