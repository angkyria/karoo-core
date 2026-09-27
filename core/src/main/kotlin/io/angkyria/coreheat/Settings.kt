package io.angkyria.coreheat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** How a field carries its heat zone colour. */
enum class ZoneColorMode {
    /** No zone colour at all; every value in the normal text colour. */
    OFF,

    /** The number itself takes the zone colour. */
    TEXT,

    /** The whole field is filled with the zone colour, the number set in black or white. */
    FILL,
    ;

    companion object {
        fun from(name: String?): ZoneColorMode? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Everything about how a field is drawn, as opposed to what it says. Carried as one object so a
 * field still combines five flows: the typed combine() stops at five, and the stream, the
 * profile, the zone mode and test mode already take four of them.
 */
data class Appearance(val raisedTail: Boolean)

/**
 * Settings that apply to every CORE Heat field at once, edited in the CORE Heat app.
 *
 * The activity and the extension service share a process, so a change reaches a live field
 * through the flows below without any IPC of our own.
 */
object Settings {

    private const val PREFS = "coreheat"
    private const val KEY_ZONE_COLOR_MODE = "zone_color_mode"
    private const val KEY_TEST_MODE = "test_mode"
    private const val KEY_RAISED_TAIL = "raised_decimals"

    private const val KEY_CORE_SENSOR_LINK = "core_sensor_link"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Colouring the number is the default. */
    fun zoneColorMode(context: Context): ZoneColorMode =
        ZoneColorMode.from(prefs(context).getString(KEY_ZONE_COLOR_MODE, null)) ?: ZoneColorMode.TEXT

    fun setZoneColorMode(context: Context, mode: ZoneColorMode) {
        prefs(context).edit().putString(KEY_ZONE_COLOR_MODE, mode.name).apply()
    }

    /**
     * Makes every field show its demo value instead of live data, so the fields can be
     * screenshotted and looked at without a ride or a sensor.
     *
     * Debug builds only, and enforced here rather than only in the UI: a release installed over a
     * debug build inherits its data directory, so hiding the switch would leave a rider with demo
     * values and no way to turn them off. Plausible-but-false heat readings on a bike computer
     * are worth keeping out of reach.
     */
    fun testMode(context: Context): Boolean =
        BuildConfig.DEBUG && prefs(context).getBoolean(KEY_TEST_MODE, false)

    fun setTestMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TEST_MODE, enabled).apply()
    }

    /**
     * Whether a value's decimal is drawn small and raised. On by default; off puts every value
     * back at one size.
     */
    fun raisedTail(context: Context): Boolean = prefs(context).getBoolean(KEY_RAISED_TAIL, true)

    fun setRaisedTail(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAISED_TAIL, enabled).apply()
    }

    /**
     * Whether CORE Heat connects to the CORE sensor itself to read its Heat Strain Index. On by
     * default: without it the index is estimated, and the permission it needs is asked for
     * separately anyway. Off is for a rider whose sensor has no Bluetooth connection to spare.
     */
    fun coreSensorLink(context: Context): Boolean = prefs(context).getBoolean(KEY_CORE_SENSOR_LINK, true)

    fun setCoreSensorLink(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CORE_SENSOR_LINK, enabled).apply()
    }

    fun coreSensorLinkFlow(context: Context): Flow<Boolean> =
        prefFlow(context, setOf(KEY_CORE_SENSOR_LINK), ::coreSensorLink)

    fun zoneColorModeFlow(context: Context): Flow<ZoneColorMode> =
        prefFlow(context, setOf(KEY_ZONE_COLOR_MODE), ::zoneColorMode)

    fun testModeFlow(context: Context): Flow<Boolean> = prefFlow(context, setOf(KEY_TEST_MODE), ::testMode)

    fun appearanceFlow(context: Context): Flow<Appearance> =
        prefFlow(context, setOf(KEY_RAISED_TAIL)) { Appearance(raisedTail(it)) }

    private fun <T> prefFlow(
        context: Context,
        keys: Set<String>,
        read: (Context) -> T,
    ): Flow<T> = callbackFlow {
        val prefs = prefs(context)
        trySendBlocking(read(context))
        // The listener fires on the main thread, so send without blocking. changed is null when
        // the preferences are cleared wholesale (API 30+), which also means our value changed.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed in keys) trySend(read(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
