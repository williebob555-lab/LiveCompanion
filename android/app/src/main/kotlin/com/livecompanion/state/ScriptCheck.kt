package com.livecompanion.state

/** Lowest `script_version` (sys.info) of the Live remote script this app build works with. */
const val MIN_SCRIPT_VERSION = 3

/** Ops this app version relies on; a script that lacks any of them is older than the app. */
val REQUIRED_OPS: Set<String> = setOf(
    "clip.peaks", "track.move", "track.move_check", "cue.move", "takes.demote", "takes.move",
    "arr.move", "arr.trim", "clip.scrub", "device.move",
)

/** Result of comparing Live's script (sys.info) with what this app needs. [checked] false until sys.info answered. */
data class ScriptStatus(
    val checked: Boolean = false, val version: Int? = null, val missing: List<String> = emptyList(),
    /** The running script has `sys.reload` (hot reload from disk without restarting Live). */
    val canReload: Boolean = false,
) {
    val outdated: Boolean get() = checked && (missing.isNotEmpty() || (version ?: 0) < MIN_SCRIPT_VERSION)
}

fun evaluateScript(version: Int?, ops: Collection<String>): ScriptStatus =
    ScriptStatus(true, version, REQUIRED_OPS.filter { it !in ops }.sorted(), "sys.reload" in ops)

const val SCRIPT_RELOADABLE_TEXT = "Live runs an older LiveCompanion script - run the installer on the PC, then tap Reload (no Live restart needed)"
const val SCRIPT_OUTDATED_TEXT = "LiveCompanion script in Live is outdated - run the installer (or tools/install) and restart Live"
