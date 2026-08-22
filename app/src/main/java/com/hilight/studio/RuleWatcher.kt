package com.hilight.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

class RuleWatcher : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        RETIRED_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HiLight rules", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HiLight rules active")
            .setContentText("Grant notification access to remove this")
            .setSmallIcon(R.drawable.hilight_logo)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "rule_watch"
        private const val NOTIFICATION_ID = 1

        private val RETIRED_CHANNELS = listOf("fg_watch", "call_watch")

        fun syncRunning(ctx: Context, rules: List<AppRule>, enabled: Boolean) {
            val needed = LiveTriggers.wanted(ctx, rules, enabled) && !NotificationTrigger.hasAccess(ctx)
            val intent = Intent(ctx, RuleWatcher::class.java)
            if (needed) {
                runCatching { ctx.startForegroundService(intent) }
            } else {
                ctx.stopService(intent)
                runCatching {
                    ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
            }
        }
    }
}
