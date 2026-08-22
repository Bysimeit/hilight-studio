package com.hilight.studio

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log

class ForegroundWatcher : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { Store.get(this) }
    private var lastPkg: String? = null

    private var stopped = false

    private val tick = object : Runnable {
        override fun run() {
            val pkg = if (screenOn()) currentForegroundPackage() else null
            if (pkg != lastPkg) {
                lastPkg = pkg

                main.post {
                    if (!stopped) {
                        val rule = pkg?.let { store.ruleFor(it, Trigger.FOREGROUND) }
                        Log.i(TAG, "foreground ${pkg ?: "-"} rule=${rule?.pattern?.key ?: "none"}")
                        store.setForegroundOverride(if (rule != null) pkg else null, rule)
                    }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("fg-watch").also { it.start() }
        handler = Handler(thread.looper)
        startForeground(1, notification())
        handler.post(tick)
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post { store.setForegroundOverride(null, null) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun screenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - LOOKBACK_MS, now) }.getOrNull() ?: return null
        var pkg: String? = null
        var cls: String? = null
        var seen = false
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    pkg = e.packageName
                    cls = e.className
                    seen = true
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> if (e.packageName == pkg && e.className == cls) {
                    pkg = null
                    cls = null
                    seen = true
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    pkg = null
                    cls = null
                    seen = true
                }
            }
        }
        return if (seen) pkg else lastPkg
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HiLight app watcher", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HiLight Studio")
            .setContentText("Watching for apps with light rules")
            .setSmallIcon(R.drawable.hilight_logo)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HiLightFg"
        private const val CHANNEL = "fg_watch"
        private const val POLL_MS = 1000L
        private const val LOOKBACK_MS = 60_000L

        fun syncRunning(ctx: Context, rules: List<AppRule>, enabled: Boolean) {
            val wanted = enabled && rules.any { it.enabled && it.trigger == Trigger.FOREGROUND }
            val intent = Intent(ctx, ForegroundWatcher::class.java)
            if (wanted && hasUsageAccess(ctx)) {
                runCatching { ctx.startForegroundService(intent) }
            } else {
                ctx.stopService(intent)
            }
        }

        fun needsUsageAccess(ctx: Context, rules: List<AppRule>): Boolean =
            rules.any { it.enabled && it.trigger == Trigger.FOREGROUND } && !hasUsageAccess(ctx)

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = runCatching {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName,
                )
            }.getOrDefault(AppOpsManager.MODE_DEFAULT)
            return when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT ->
                    ctx.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                        PackageManager.PERMISSION_GRANTED

                else -> false
            }
        }
    }
}
