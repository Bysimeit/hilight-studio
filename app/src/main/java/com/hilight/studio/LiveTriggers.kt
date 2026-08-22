package com.hilight.studio

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log

object LiveTriggers {
    private const val TAG = "HiLightRules"
    private const val POLL_MS = 1000L
    private const val LOOKBACK_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var watchingApps = false
    private var watchingCalls = false
    private var lastPkg: String? = null
    private var callState: Trigger? = null
    private var lastMode = Int.MIN_VALUE

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        main.post { if (watchingCalls) applyCall(mode) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!watchingApps) return
            val pkg = if (screenOn()) currentForegroundPackage() else null
            if (pkg != lastPkg) {
                lastPkg = pkg
                main.post {
                    if (watchingApps) {
                        val store = Store.get(app ?: return@post)
                        val rule = pkg?.let { store.ruleFor(it, Trigger.FOREGROUND) }
                        Log.i(TAG, "foreground ${pkg ?: "-"} rule=${rule?.pattern?.key ?: "none"}")
                        store.setForegroundOverride(if (rule != null) pkg else null, rule)
                    }
                }
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    fun sync(context: Context, rules: List<AppRule>, enabled: Boolean) {
        val ctx = context.applicationContext
        app = ctx
        val live = rules.filter { it.enabled }
        setApps(ctx, enabled && live.any { it.trigger == Trigger.FOREGROUND } && hasUsageAccess(ctx))
        setCalls(ctx, enabled && live.any { it.isCall })
    }

    fun stop() {
        app?.let {
            setApps(it, false)
            setCalls(it, false)
        }
    }

    fun wanted(ctx: Context, rules: List<AppRule>, enabled: Boolean): Boolean {
        val live = rules.filter { it.enabled }
        return enabled &&
            (live.any { it.isCall } ||
                (live.any { it.trigger == Trigger.FOREGROUND } && hasUsageAccess(ctx)))
    }

    private fun setApps(ctx: Context, on: Boolean) {
        if (on == watchingApps) return
        watchingApps = on
        if (on) {
            val t = HandlerThread("rule-watch").also { it.start() }
            thread = t
            handler = Handler(t.looper).also { it.post(tick) }
        } else {
            handler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
            handler = null
            thread = null
            lastPkg = null
            main.post { Store.get(ctx).setForegroundOverride(null, null) }
        }
    }

    private fun setCalls(ctx: Context, on: Boolean) {
        if (on == watchingCalls) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (on) {
            watchingCalls = true
            runCatching { audio?.addOnModeChangedListener(ctx.mainExecutor, modeListener) }
            applyCall(audio?.mode ?: AudioManager.MODE_NORMAL)
        } else {
            watchingCalls = false
            runCatching { audio?.removeOnModeChangedListener(modeListener) }
            callState = null
            lastMode = Int.MIN_VALUE
            main.post { Store.get(ctx).setCallOverride(null) }
        }
    }

    private fun applyCall(mode: Int) {
        val ctx = app ?: return
        if (mode != lastMode) {
            lastMode = mode
            Log.d(TAG, "audio mode $mode (${modeName(mode)})")
        }
        val now = stateOf(mode)
        if (now == callState) return
        callState = now
        val store = Store.get(ctx)
        val rule = now?.let { store.callRule(it) }
        Log.i(TAG, "${now?.name ?: "idle"} rule=${rule?.pattern?.key ?: "none"}")
        store.setCallOverride(rule)
    }

    private fun stateOf(mode: Int): Trigger? = when (mode) {
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_CALL_SCREENING,
        -> Trigger.RINGING

        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_REDIRECT,
        AudioManager.MODE_COMMUNICATION_REDIRECT,
        -> Trigger.CALL

        else -> null
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in_call"
        AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
        AudioManager.MODE_CALL_SCREENING -> "call_screening"
        AudioManager.MODE_CALL_REDIRECT -> "call_redirect"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "communication_redirect"
        else -> "unknown"
    }

    private fun screenOn(): Boolean =
        app?.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun currentForegroundPackage(): String? {
        val usm = app?.getSystemService(UsageStatsManager::class.java) ?: return null
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
