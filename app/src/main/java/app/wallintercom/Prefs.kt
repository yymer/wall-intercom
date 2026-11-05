package app.wallintercom

import android.content.Context
import android.os.Build

/** All user settings, stored in SharedPreferences (thread-safe). */
class Prefs(ctx: Context) {
    private val res = ctx.applicationContext.resources
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

    /** Name shown on the other tablet . Must differ between the two tablets. */
    var myName: String get() = str("myName", Build.MODEL); set(v) = put("myName", v)
    var peerIp: String get() = str("peerIp", ""); set(v) = put("peerIp", v)
    /** Learned automatically from the other tablet. */
    var peerName: String get() = str("peerName", res.getString(R.string.default_peer_name)); set(v) = put("peerName", v)
    var port: Int get() = int("port", 47555); set(v) = put("port", v)

    /** The slideshow shows either an album (gallery folder) or a folder picked with "Other folder…". */
    var albumId: String? get() = p.getString("albumId", null); set(v) = put("albumId", v)
    var albumName: String? get() = p.getString("albumName", null); set(v) = put("albumName", v)
    var folderUri: String? get() = p.getString("folderUri", null); set(v) = put("folderUri", v)
    var slideSeconds: Int get() = int("slideSeconds", 20); set(v) = put("slideSeconds", v)
    var fillScreen: Boolean get() = bool("fillScreen", true); set(v) = put("fillScreen", v)

    var idleBrightness: Int get() = int("idleBrightness", 40); set(v) = put("idleBrightness", v)
    var nightStart: Int get() = int("nightStart", 22); set(v) = put("nightStart", v)
    var nightEnd: Int get() = int("nightEnd", 7); set(v) = put("nightEnd", v)
    var nightBrightness: Int get() = int("nightBrightness", 2); set(v) = put("nightBrightness", v)

    var callVolume: Int get() = int("callVolume", 80); set(v) = put("callVolume", v)
    var maxCallMinutes: Int get() = int("maxCallMinutes", 15); set(v) = put("maxCallMinutes", v)
    var hdVideo: Boolean get() = bool("hdVideo", false); set(v) = put("hdVideo", v)
    var bellVolume: Int get() = int("bellVolume", 80); set(v) = put("bellVolume", v)
    var hwEcho: Boolean get() = bool("hwEcho", false); set(v) = put("hwEcho", v)
    /** Off by default: someone in the room has to press Answer before the camera turns on. */
    var autoAnswer: Boolean get() = bool("autoAnswer", false); set(v) = put("autoAnswer", v)

    fun isNight(hour: Int): Boolean {
        val s = nightStart
        val e = nightEnd
        return when {
            s == e -> false
            s < e -> hour in s until e
            else -> hour >= s || hour < e
        }
    }
}
