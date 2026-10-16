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

    /** Two-pane mode: the left Session pane is collapsed (rail toggle). */
    var sessionPaneHidden: Boolean
        get() = sp.getBoolean(K_PANE_HIDDEN, false)
        set(v) { sp.edit().putBoolean(K_PANE_HIDDEN, v).apply() }

    /** "land" (default — owner preference), "port" or "auto". */
    var orientation: String
        // Values stored by builds before the explicit-choice flag are ignored: landscape is the default until the
        // user picks an orientation under the current setting (K_ORIENT_SET, version flag).
        get() = if (sp.getBoolean(K_ORIENT_SET, false)) sp.getString(K_ORIENTATION, "land") ?: "land" else "land"
        set(v) { sp.edit().putString(K_ORIENTATION, v).putBoolean(K_ORIENT_SET, true).apply() }

    private companion object {
        const val K_PANE_HIDDEN = "session_pane_hidden"
        const val K_ORIENTATION = "orientation"
        const val K_ORIENT_SET = "orientation_chosen_v2"
        const val K_RECENTS = "recents"
        const val K_LAST_HOST = "last_host"
        const val K_FOLLOW = "follow_live"
        const val K_TEST_CHANNEL = "test_channel"
        const val MAX_RECENTS = 8
    }
}

private val HOST_RE = Regex("^[A-Za-z0-9_]([A-Za-z0-9_.-]*[A-Za-z0-9_])?$")
private val V6_RE = Regex("^[0-9A-Fa-f:.%A-Za-z0-9]+$")

/** Human-readable problem with a typed host string, or null when it is fine (or blank: nothing typed yet). */
fun hostPortError(text: String): String? {
    val s = text.trim()
    if (s.isEmpty()) return null
    if (s.any { it.isWhitespace() }) return "Address must not contain spaces"
    fun portErr(p: String): String? {
        val n = p.toIntOrNull() ?: return "Port must be a number (1-65535)"
        return if (n in 1..65535) null else "Port must be 1-65535"
    }
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end < 0) return "Missing ] in IPv6 address"
        if (end == 1) return "Host is empty"
        if (!V6_RE.matches(s.substring(1, end)) || !s.substring(1, end).contains(':')) return "Not a valid IPv6 address"
        val rest = s.substring(end + 1)
        if (rest.isEmpty()) return null
        if (!rest.startsWith(":")) return "Expected :port after ]"
        return portErr(rest.drop(1))
    }
    val colons = s.count { it == ':' }
    if (colons > 1) return if (V6_RE.matches(s)) null else "Not a valid IPv6 address"
    val host = s.substringBefore(':')
    if (host.isEmpty()) return "Host is empty"
    if (!HOST_RE.matches(host)) return "Host may only contain letters, digits, '.', '-' and '_'"
    if (colons == 1) return portErr(s.substringAfter(':'))
    return null
}

/** "host", "host:port" or "[v6]:port" -> target (default port 9010). Null if blank/invalid (see [hostPortError]). */
fun parseHostPort(text: String, defaultPort: Int = 9010): HostTarget? {
    val s = text.trim()
    if (s.isEmpty() || hostPortError(s) != null) return null
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        val port = s.substring(end + 1).removePrefix(":").ifEmpty { null }?.toInt() ?: defaultPort
        return HostTarget(s.substring(1, end), port)
    }
    val colons = s.count { it == ':' }
    if (colons > 1) return HostTarget(s, defaultPort) // bare IPv6
    if (colons == 1) return HostTarget(s.substringBefore(':'), s.substringAfter(':').toInt())
    return HostTarget(s, defaultPort)
}
