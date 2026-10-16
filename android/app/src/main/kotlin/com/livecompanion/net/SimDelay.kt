package com.livecompanion.net

/**
 * Developer toggle (More -> Help / Developer): every request is held back by [ms] before it reaches Live (or the demo),
 * so the server echo arrives that much later. Used to prove that drags never lag or snap back (principle 3).
 * Honoured by [LiveClient.request] and [DemoClient.request]. Off (0) by default; the setting is persisted by Prefs.
 */
object SimDelay {
    const val DEFAULT_MS = 300L
    @Volatile var ms: Long = 0L

    /** Dev builds only: debuggable apps and test-channel pre-releases (the owner's phone runs those). */
    fun available(context: android.content.Context): Boolean {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return true
        return com.livecompanion.update.Version.parse(com.livecompanion.update.AppUpdates.installedVersion(context))?.preRelease != null
    }
}
