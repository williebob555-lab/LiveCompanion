package com.livecompanion.state

/**
 * "Settle" half of the instant-feel rule (docs/PREFERENCES.md principle 3) for state that lives in a server section.
 * An optimistic edit registers a [patch] plus a [done] test. Every incoming (authoritative) section is passed through
 * [reconcile]: while an edit is younger than [settleMs] and the server value does not show it yet, the patch is
 * re-applied on top of the incoming value, so a stale echo can never make an edited marker/clip/slot snap back.
 * An edit disappears when the server shows it ([done]) or after [settleMs].
 */
class SectionGuard<T>(
    private val settleMs: Long = SETTLE_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Edit<T>(val patch: (T) -> T, val done: (T) -> Boolean, val until: Long)

    private val edits = ArrayList<Edit<T>>()

    @Synchronized
    fun add(patch: (T) -> T, done: (T) -> Boolean) {
        val now = clock()
        edits.removeAll { now >= it.until }
        edits.add(Edit(patch, done, clock() + settleMs))
    }

    @Synchronized
    fun reconcile(incoming: T): T {
        if (edits.isEmpty()) return incoming
        val now = clock()
        var r = incoming
        val it = edits.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now >= e.until || e.done(r)) { it.remove(); continue }
            r = e.patch(r)
        }
        return r
    }

    @Synchronized fun clear() = edits.clear()
    @Synchronized fun pending(): Int = edits.size

    companion object { const val SETTLE_MS = 600L }
}
