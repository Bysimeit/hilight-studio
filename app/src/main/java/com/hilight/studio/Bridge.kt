package com.hilight.studio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

object Bridge {
    private const val TAG = "HiLightBridge"
    const val DIR_NAME = "hilight"
    const val DEVICE_DIR = "/storage/emulated/0/Android/data/com.hilight.studio/files/hilight"

    private fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), DIR_NAME).apply { if (!exists()) mkdirs() }

    fun stateFile(ctx: Context) = File(dir(ctx), "state.json")
    private fun statusFile(ctx: Context) = File(dir(ctx), "helper_status.json")

    fun ensureFiles(ctx: Context) {
        runCatching {
            dir(ctx)
            stateFile(ctx).let { if (!it.exists()) it.writeText("{\"enabled\":false}") }
            statusFile(ctx).let { if (!it.exists()) it.writeText("{}") }
        }.onFailure { Log.w(TAG, "could not prepare bridge files", it) }
    }

    fun stateJson(
        enabled: Boolean,
        priority: Int,
        ambient: Ambient,
        alert: JSONObject?,
        ambientTimeoutMs: Int = Limits.AMBIENT_DEFAULT_MS,

        arm: Boolean = true,

        dim: Float = 1f,
        privacyRules: List<PrivacyRule> = emptyList(),
        privacyObserverEnabled: Boolean = false,
        privacyOutputEnabled: Boolean = privacyObserverEnabled,
        stateRevision: Long = 0,
    ): String =
        JSONObject().apply {
            put("v", 2)
            put("stateRevision", stateRevision)
            put("enabled", enabled)
            put("priority", priority)
            put("ambientTimeoutMs", ambientTimeoutMs)
            put("arm", arm)
            put("dim", dim.toDouble())
            put("ambient", ambient.toJson())
            put("privacyObserverEnabled", privacyObserverEnabled)
            put("privacyOutputEnabled", privacyOutputEnabled)
            put("privacyRules", JSONArray().also { out ->
                privacyRules.filter { it.enabled }.forEach { out.put(it.toRendererJson()) }
            })
            if (alert != null) put("alert", alert)
        }.toString()

    @Synchronized
    fun writeState(ctx: Context, json: String) {
        runCatching {
            val target = stateFile(ctx)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "state write failed", it) }
    }

    fun readStatus(ctx: Context): HelperStatus {
        val f = statusFile(ctx)
        if (!f.exists()) return HelperStatus(alive = false)
        return try {
            val o = JSONObject(f.readText())
            val age = System.currentTimeMillis() - o.optLong("ts", 0)
            HelperStatus(

                alive = age in -5_000..4_000,
                ageMs = age,
                pid = o.optInt("pid", -1),
                uid = o.optInt("uid", -1),
                owner = o.optString("owner", "adb"),
                ledCount = o.optInt("ledCount", 0),
                sessionOpen = o.optBoolean("session", false),
                mode = o.optString("mode", "-"),
                ambientRemainingMs = o.optLong("ambientRemainingMs", 0),
                ambientHeld = o.optBoolean("ambientHeld", false),
                resting = o.optBoolean("resting", false),
                dutyPct = o.optInt("dutyPct", 0),
                appliedStateRevision = o.optLong("appliedStateRevision", 0),
                privacyObserverEnabled = o.optBoolean("privacyObserverEnabled", false),
                privacyObserverState = o.optString("privacyObserverState", "stopped"),
                privacyPhase = o.optString("privacyPhase", "inactive"),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "unreadable status", t)
            HelperStatus(alive = false)
        }
    }

    fun alertJson(
        id: Long,
        pattern: Pattern,
        color: Int,
        durationMs: Int,
        speedMs: Int,
        brightness: Float,
        source: AlertSource,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("pattern", pattern.key)
        put("color", color.toUInt().toLong())
        put("durationMs", durationMs)
        put("speedMs", speedMs)
        put("brightness", brightness.toDouble())
        put("source", source.key)
        put("spread", true)
        put("randomIntervalMs", 500)
        put("randomPerLed", true)
        put("randomSmooth", true)
    }

    fun nextAlertId(): Long = SystemClock.elapsedRealtime()
}
