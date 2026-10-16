package com.livecompanion.data

import android.content.Context
import com.livecompanion.net.HostTarget

/** Small SharedPreferences-backed settings: recent hosts, follow toggle, update channel. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("livecompanion", Context.MODE_PRIVATE)

    fun recents(): List<HostTarget> =
        (sp.getString(K_RECENTS, "") ?: "").split('\n').mapNotNull { parseHostPort(it) }

    fun addRecent(t: HostTarget) {
        val list = (listOf(t) + recents().filter { it != t }).take(MAX_RECENTS)
        sp.edit().putString(K_RECENTS, list.joinToString("\n") { it.label }).apply()
    }

    fun removeRecent(t: HostTarget) {
        val list = recents().filter { it != t }
        sp.edit().putString(K_RECENTS, list.joinToString("\n") { it.label }).apply()
    }

    /** Host connected when the app was last used; cleared by an explicit disconnect. Drives auto-reconnect on launch. */
    var lastHost: HostTarget?
        get() = parseHostPort(sp.getString(K_LAST_HOST, "") ?: "")
        set(v) { sp.edit().putString(K_LAST_HOST, v?.label ?: "").apply() }

    var followLive: Boolean
        get() = sp.getBoolean(K_FOLLOW, false)
        set(v) { sp.edit().putBoolean(K_FOLLOW, v).apply() }

    var testChannel: Boolean
        get() = sp.getBoolean(K_TEST_CHANNEL, false)
        set(v) { sp.edit().putBoolean(K_TEST_CHANNEL, v).apply() }

    /** "land" (default — owner preference), "port" or "auto". */
    var orientation: String
        get() = sp.getString(K_ORIENTATION, "land") ?: "land"
        set(v) { sp.edit().putString(K_ORIENTATION, v).apply() }

    private companion object {
        const val K_ORIENTATION = "orientation"
        const val K_RECENTS = "recents"
        const val K_LAST_HOST = "last_host"
        const val K_FOLLOW = "follow_live"
        const val K_TEST_CHANNEL = "test_channel"
        const val MAX_RECENTS = 8
    }
}

/** "host", "host:port" or "[v6]:port" -> target (default port 9010). Null if blank/invalid. */
fun parseHostPort(text: String, defaultPort: Int = 9010): HostTarget? {
    val s = text.trim()
    if (s.isEmpty()) return null
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end < 0) return null
        val port = s.substring(end + 1).removePrefix(":").ifEmpty { null }?.toIntOrNull() ?: defaultPort
        return HostTarget(s.substring(1, end), port)
    }
    val colons = s.count { it == ':' }
    if (colons > 1) return HostTarget(s, defaultPort) // bare IPv6
    if (colons == 1) {
        val port = s.substringAfter(':').toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val host = s.substringBefore(':')
        return if (host.isEmpty()) null else HostTarget(host, port)
    }
    return HostTarget(s, defaultPort)
}
