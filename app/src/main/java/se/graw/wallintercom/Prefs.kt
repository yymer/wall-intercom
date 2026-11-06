package se.graw.wallintercom

import android.content.Context
import android.os.Build

/** All user settings, stored in SharedPreferences (thread-safe). */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = p.getString(key, def) ?: def
    private fun int(key: String, def: Int) = p.getInt(key, def)
    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun put(key: String, v: Any?) = p.edit().apply {
        when (v) {
            null -> remove(key)
            is String -> putString(key, v)
            is Int -> putInt(key, v)
            is Boolean -> putBoolean(key, v)
        }
    }.apply()

    /** Name of this tablet, shown on the other tablets. */
    var myName: String get() = str("myName", Build.MODEL); set(v) = put("myName", v)
    /** Same TCP (calls) and UDP (discovery) port on all tablets. */
    var port: Int get() = int("port", 47555); set(v) = put("port", v)

    /** Permanent random identity of this tablet; the other tablets know it by this. */
    val deviceId: String
        get() = synchronized(Prefs::class.java) {
            p.getString("deviceId", null)
                ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).commit() }
        }

    /** The paired tablets as JSON; use [Peers]. */
    var peersJson: String? get() = p.getString("peers", null); set(v) = put("peers", v)

    /** Off: pairing requests from other tablets are dropped, with nothing shown or sent back. */
    var allowPairing: Boolean get() = bool("allowPairing", true); set(v) = put("allowPairing", v)
    /** Leaves tablets that aren't paired off the main screen. They're still in the settings. */
    var hideUnpaired: Boolean get() = bool("hideUnpaired", false); set(v) = put("hideUnpaired", v)

    /** The slideshow shows either an album (gallery folder) or a folder picked with "Other folder…". */
    var albumId: String? get() = p.getString("albumId", null); set(v) = put("albumId", v)
    var albumName: String? get() = p.getString("albumName", null); set(v) = put("albumName", v)
    var folderUri: String? get() = p.getString("folderUri", null); set(v) = put("folderUri", v)
    var slideSeconds: Int get() = int("slideSeconds", 20); set(v) = put("slideSeconds", v)
    var fillScreen: Boolean get() = bool("fillScreen", true); set(v) = put("fillScreen", v)

    var idleBrightness: Int get() = int("idleBrightness", 40); set(v) = put("idleBrightness", v)
    var nightMode: Boolean get() = bool("nightMode", false); set(v) = put("nightMode", v)
    /** When night starts and ends, in minutes after midnight. */
    var nightStart: Int get() = int("nightStartMinutes", 22 * 60); set(v) = put("nightStartMinutes", v)
    var nightEnd: Int get() = int("nightEndMinutes", 7 * 60); set(v) = put("nightEndMinutes", v)
    var nightBrightness: Int get() = int("nightBrightness", 2); set(v) = put("nightBrightness", v)

    var callVolume: Int get() = int("callVolume", 80); set(v) = put("callVolume", v)
    var maxCallMinutes: Int get() = int("maxCallMinutes", 15); set(v) = put("maxCallMinutes", v)
    var hdVideo: Boolean get() = bool("hdVideo", false); set(v) = put("hdVideo", v)
    var bellVolume: Int get() = int("bellVolume", 80); set(v) = put("bellVolume", v)
    var hwEcho: Boolean get() = bool("hwEcho", false); set(v) = put("hwEcho", v)
    /** Start the app (and listen for calls) when the tablet is turned on. Off by default. */
    var autoStart: Boolean get() = bool("autoStart", false); set(v) = put("autoStart", v)
    /** Off by default: someone in the room has to press Answer before the camera turns on. */
    var autoAnswer: Boolean get() = bool("autoAnswer", false); set(v) = put("autoAnswer", v)

    /**
     * Whether Wall Intercom offers itself as the tablet's Home app. Stored by Android as the
     * enabled state of the HomeScreen alias in the manifest, not in these settings.
     */
    object HomeScreen {
        private fun component(ctx: Context) = android.content.ComponentName(ctx, "se.graw.wallintercom.HomeScreen")

        fun isEnabled(ctx: Context) = ctx.packageManager.getComponentEnabledSetting(component(ctx)) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        fun setEnabled(ctx: Context, on: Boolean) {
            if (on == isEnabled(ctx)) return
            ctx.packageManager.setComponentEnabledSetting(
                component(ctx),
                if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, // = off (manifest)
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun isNight(minuteOfDay: Int): Boolean {
        if (!nightMode) return false
        val s = nightStart
        val e = nightEnd
        return when {
            s == e -> false
            s < e -> minuteOfDay in s until e
            else -> minuteOfDay >= s || minuteOfDay < e
        }
    }
}
