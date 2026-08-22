package com.hilight.studio

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject

/**
 * Patterns the renderer understands.
 *
 * [cycleMeaningRes] spells out what one "cycle" is for each pattern, because it means something
 * different every time. [usesSpeed] is false for the patterns whose maths ignore speedMs entirely —
 * those must not show a cycle slider that does nothing.
 *
 * The labels are resource ids rather than strings because this enum is read from the renderer's state
 * layer and from a Quick Settings tile as well as from Compose, and none of those has a Context to
 * resolve a string with at the point the enum is declared.
 */
enum class Pattern(
    val key: String,
    @StringRes val labelRes: Int,
    val usesSpeed: Boolean = true,
    @StringRes val cycleMeaningRes: Int? = null,
    /**
     * Set only where the full name does not fit a narrow control.
     *
     * The Live tab's effect tiles and the per-LED fill buttons give a pattern a third of a row, which
     * "Rainbow" survives and レインボー does not — it wraps and then clips. Read through
     * [shortLabelRes], which falls back to the full name.
     */
    @StringRes private val narrowLabelRes: Int? = null,
) {
    OFF("off", R.string.pattern_off, usesSpeed = false),
    SOLID("solid", R.string.pattern_solid, usesSpeed = false),
    GRADIENT("gradient", R.string.pattern_gradient, usesSpeed = false),
    BREATHE("breathe", R.string.pattern_breathe, cycleMeaningRes = R.string.cycle_breathe),
    BLINK("blink", R.string.pattern_blink, cycleMeaningRes = R.string.cycle_blink),
    PULSE("pulse", R.string.pattern_pulse, cycleMeaningRes = R.string.cycle_pulse),
    CHASE("chase", R.string.pattern_chase, cycleMeaningRes = R.string.cycle_chase),
    COMET("comet", R.string.pattern_comet, cycleMeaningRes = R.string.cycle_comet),
    WAVE("wave", R.string.pattern_wave, cycleMeaningRes = R.string.cycle_wave),
    RAINBOW(
        "rainbow", R.string.pattern_rainbow, cycleMeaningRes = R.string.cycle_rainbow,
        narrowLabelRes = R.string.pattern_rainbow_short,
    ),
    RANDOM("random", R.string.pattern_random, usesSpeed = false),
    CUSTOM("custom", R.string.pattern_custom, usesSpeed = false);

    /** The name to show where a third of a row is all there is. */
    @get:StringRes
    val shortLabelRes: Int get() = narrowLabelRes ?: labelRes

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: SOLID
    }
}

enum class Trigger { NOTIFICATION, FOREGROUND, RINGING, CALL }

data class Ambient(
    val pattern: Pattern = Pattern.OFF,
    val color: Int = 0xFF7C4DFF.toInt(),
    val secondColor: Int = 0xFF00E5FF.toInt(),
    val perLed: List<Int> = List(LED_COUNT) { 0xFF7C4DFF.toInt() },
    val brightness: Float = 0.7f,
    val speedMs: Int = 2500,
    val rainbowSpread: Boolean = true,
    val randomIntervalMs: Int = 1500,
    val randomPerLed: Boolean = true,
    val randomSmooth: Boolean = true,
    val randomSaturation: Float = 1f,
    val rotateMs: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", pattern.key)
        put("brightness", brightness.toDouble())
        put("speedMs", speedMs)
        put("spread", rainbowSpread)
        put("randomIntervalMs", randomIntervalMs)
        put("randomPerLed", randomPerLed)
        put("randomSmooth", randomSmooth)
        put("randomSaturation", randomSaturation.toDouble())
        put("rotateMs", rotateMs)
        when (pattern) {
            Pattern.CUSTOM -> put("colors", JSONArray().also { a -> perLed.forEach { a.put(it.toUInt().toLong()) } })
            Pattern.GRADIENT -> put(
                "colors",
                JSONArray().put(color.toUInt().toLong()).put(secondColor.toUInt().toLong())
            )
            else -> put("color", color.toUInt().toLong())
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = Ambient(
            pattern = Pattern.of(o.optString("pattern", "off")),
            color = o.optLong("color", 0xFF7C4DFFL).toInt(),
            secondColor = o.optLong("secondColor", 0xFF00E5FFL).toInt(),
            perLed = o.optJSONArray("perLed")?.let { a ->
                (0 until a.length()).map { a.optLong(it).toInt() }
            }?.takeIf { it.size == LED_COUNT } ?: List(LED_COUNT) { 0xFF7C4DFF.toInt() },
            brightness = o.optDouble("brightness", 0.7).toFloat(),
            speedMs = o.optInt("speedMs", 2500),
            rainbowSpread = o.optBoolean("rainbowSpread", true),
            randomIntervalMs = o.optInt("randomIntervalMs", 1500),
            randomPerLed = o.optBoolean("randomPerLed", true),
            randomSmooth = o.optBoolean("randomSmooth", true),
            randomSaturation = o.optDouble("randomSaturation", 1.0).toFloat(),
            rotateMs = o.optInt("rotateMs", 0),
        )
    }

    fun toPrefsJson(): JSONObject = JSONObject().apply {
        put("pattern", pattern.key)
        put("color", color.toUInt().toLong())
        put("secondColor", secondColor.toUInt().toLong())
        put("perLed", JSONArray().also { a -> perLed.forEach { a.put(it.toUInt().toLong()) } })
        put("brightness", brightness.toDouble())
        put("speedMs", speedMs)
        put("rainbowSpread", rainbowSpread)
        put("randomIntervalMs", randomIntervalMs)
        put("randomPerLed", randomPerLed)
        put("randomSmooth", randomSmooth)
        put("randomSaturation", randomSaturation.toDouble())
        put("rotateMs", rotateMs)
    }
}

data class AppRule(
    val pkg: String,
    val label: String,
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.NOTIFICATION,
    val pattern: Pattern = Pattern.PULSE,
    val randomColor: Boolean = false,
    val color: Int = 0xFF00E676.toInt(),
    val durationMs: Int = 10_000,
    val speedMs: Int = 800,
    val brightness: Float = 1f,
    val onlyWhenScreenOff: Boolean = false,

    val keyword: String = "",
    /**
     * Per-conversation rules — "green when Sujay messages on WhatsApp".
     *
     * [conversationKey] is the notification's `shortcutId`, the stable per-chat id. It is filled in
     * the first time a matching notification is seen, even for a rule created from the contact
     * picker, after which renaming the contact can no longer break the rule. [conversationName] is
     * the fallback for apps that set no shortcutId, and what the card shows.
     */
    val conversationKey: String? = null,
    val conversationName: String? = null,
    /** also fire when this person speaks inside a group, not only in their own chat */
    val includeGroups: Boolean = false,
    /**
     * Whether the chat this rule was made from is itself a group.
     *
     * Carried on the rule rather than looked up, because the learned-chat list is capped and a rule
     * made from the contact picker was never in it at all — so the card had no way to tell a group
     * from a person, and the editor could not explain why the "also in groups" switch is irrelevant
     * for a rule that already names a group.
     */
    val conversationIsGroup: Boolean = false,
) {
    val isCall: Boolean get() = trigger == Trigger.RINGING || trigger == Trigger.CALL

    val isCatchAll: Boolean get() = pkg == ANY_APP && !isCall

    /** True for a rule scoped to one chat rather than to a whole app. */
    val isConversationRule: Boolean
        get() = !conversationKey.isNullOrBlank() || !conversationName.isNullOrBlank()

    /**
     * Identity for storage.
     *
     * Package plus trigger used to be enough, but an app can now hold several rules — one per
     * conversation, plus a plain one for everything else — so the conversation has to be part of it.
     */
    val id: String get() = "$pkg|${trigger.name}|${conversationKey ?: conversationName ?: ""}"

    fun toPrefsJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg)
        put("label", label)
        put("enabled", enabled)
        put("trigger", trigger.name)
        put("pattern", pattern.key)
        put("randomColor", randomColor)
        put("color", color.toUInt().toLong())
        put("durationMs", durationMs)
        put("speedMs", speedMs)
        put("brightness", brightness.toDouble())
        put("onlyWhenScreenOff", onlyWhenScreenOff)
        put("keyword", keyword)
        conversationKey?.let { put("conversationKey", it) }
        conversationName?.let { put("conversationName", it) }
        put("includeGroups", includeGroups)
        put("conversationIsGroup", conversationIsGroup)
    }

    companion object {
        const val ANY_APP = "*"
        const val CALL_STATE = "@call"
        const val RINGING_STATE = "@ringing"

        fun incomingCall() = AppRule(
            pkg = RINGING_STATE,
            label = "Incoming call",
            enabled = false,
            trigger = Trigger.RINGING,
            pattern = Pattern.PULSE,
            color = 0xFF00E676.toInt(),
            durationMs = 0,
            speedMs = 900,
        )

        fun onCall() = AppRule(
            pkg = CALL_STATE,
            label = "On a call",
            enabled = false,
            trigger = Trigger.CALL,
            pattern = Pattern.BREATHE,
            color = 0xFF00E5FF.toInt(),
            durationMs = 0,
            speedMs = 2600,
        )

        fun fromJson(o: JSONObject) = AppRule(
            pkg = o.getString("pkg"),
            label = o.optString("label", o.getString("pkg")),
            enabled = o.optBoolean("enabled", true),
            trigger = runCatching { Trigger.valueOf(o.optString("trigger", "NOTIFICATION")) }
                .getOrDefault(Trigger.NOTIFICATION),
            pattern = Pattern.of(o.optString("pattern", "pulse")),
            randomColor = o.optBoolean("randomColor", false),
            color = o.optLong("color", 0xFF00E676L).toInt(),
            durationMs = o.optInt("durationMs", 10_000),
            speedMs = o.optInt("speedMs", 800),
            brightness = o.optDouble("brightness", 1.0).toFloat(),
            onlyWhenScreenOff = o.optBoolean("onlyWhenScreenOff", false),
            keyword = o.optString("keyword", ""),
            conversationKey = o.optString("conversationKey", "").takeIf { it.isNotEmpty() },
            conversationName = o.optString("conversationName", "").takeIf { it.isNotEmpty() },
            includeGroups = o.optBoolean("includeGroups", false),
            conversationIsGroup = o.optBoolean("conversationIsGroup", false),
        )
    }
}

data class Preset(val name: String, val ambient: Ambient) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("ambient", ambient.toPrefsJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            name = o.optString("name", "Preset"),
            ambient = Ambient.fromJson(o.getJSONObject("ambient")),
        )
    }
}

/** Why the array is being held dark despite the master switch being on. */
enum class Suppression(@StringRes val shortRes: Int) {
    QUIET_HOURS(R.string.suppression_quiet_hours),
    LOW_BATTERY(R.string.suppression_low_battery),
    POWER_SAVER(R.string.suppression_power_saver),
    SCREEN_ON(R.string.suppression_screen_on),
}

object Limits {
    const val BATTERY_DEFAULT_PCT = 10
    const val BATTERY_MIN_PCT = 5
    const val BATTERY_MAX_PCT = 50
    const val AMBIENT_DEFAULT_MS = 30_000
    const val AMBIENT_MAX_MS = 300_000
    const val RULE_DEFAULT_MS = 10_000
    const val RULE_MAX_MS = 60_000
    const val WARN_ABOVE_MS = 30_000
}

data class HelperStatus(
    val alive: Boolean,
    val ageMs: Long = -1,
    val pid: Int = -1,
    val ledCount: Int = 0,
    val sessionOpen: Boolean = false,
    val mode: String = "-",

    val ambientRemainingMs: Long = 0,

    val ambientHeld: Boolean = false,

    val resting: Boolean = false,

    val dutyPct: Int = 0,
)

const val LED_COUNT = 8
