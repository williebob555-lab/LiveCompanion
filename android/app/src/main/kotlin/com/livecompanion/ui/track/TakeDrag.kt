package com.livecompanion.ui.track

import com.livecompanion.state.ArrClip
import com.livecompanion.state.ArrState
import com.livecompanion.state.TakeClip
import com.livecompanion.state.TakeLane
import com.livecompanion.state.TakesState
import kotlin.math.abs

/**
 * Take hold-drag (docs/PREFERENCES.md 2026-10-16): hold a take and drag it up into the main lane = PROMOTE, hold a main-lane
 * clip and drag it down into a take lane = DEMOTE, hold a take and drag it onto another take lane = MOVE. The clip keeps
 * its time; only the lane changes. This file holds the pure parts (drop target, optimistic state patches).
 */
const val DROP_NONE = -2
const val DROP_MAIN = -1

/** A take / main-lane clip being carried by a finger. [srcLane] = -1 for a main-lane clip. [target]: DROP_NONE, DROP_MAIN or a take lane index. */
class TakeDrop(
    val srcLane: Int, val srcIdx: Int, val name: String, val color: Int, val start: Double, val end: Double,
    val y: Float, val target: Int,
) {
    /** What dropping now does, for the highlight label. */
    val label: String? get() = when {
        target == DROP_NONE -> null
        target == DROP_MAIN -> "Promote to main lane"
        srcLane == DROP_MAIN -> "Demote to take lane"
        else -> "Move to this lane"
    }
}

/** Drop target for a finger at [y]: main lane, take lane (by position in [lanes]) or none. A take cannot drop on its own lane. */
fun dropTargetAt(y: Float, rulerPx: Float, mainH: Float, takeH: Float, lanes: List<TakeLane>, srcLane: Int): Int {
    if (y < rulerPx) return DROP_NONE
    if (y < rulerPx + mainH) return if (srcLane == DROP_MAIN) DROP_NONE else DROP_MAIN
    if (takeH <= 0f) return DROP_NONE
    val k = ((y - rulerPx - mainH) / takeH).toInt()
    val lane = lanes.getOrNull(k) ?: return DROP_NONE
    return if (lane.i == srcLane) DROP_NONE else lane.i
}

private fun sameSpan(aS: Double, aE: Double, bS: Double, bE: Double) = abs(aS - bS) < 1e-3 && abs(aE - bE) < 1e-3

fun reindexArr(clips: List<ArrClip>): List<ArrClip> = clips.sortedBy { it.start }.mapIndexed { k, c -> c.copy(i = k) }

/** Optimistic state edits + their "server shows it" tests (SectionGuard). All are no-ops for another track's section. */
object TakeEdits {
    fun arrWith(tr: String, c: ArrClip): (ArrState) -> ArrState = { s ->
        if (s.tr != tr || s.clips.any { it.name == c.name && sameSpan(it.start, it.end, c.start, c.end) }) s
        else s.copy(clips = reindexArr(s.clips + c))
    }

    fun arrHas(tr: String, c: ArrClip): (ArrState) -> Boolean = { s ->
        s.tr != tr || s.clips.any { sameSpan(it.start, it.end, c.start, c.end) && it.name == c.name }
    }

    fun arrWithout(tr: String, c: ArrClip): (ArrState) -> ArrState = { s ->
        if (s.tr != tr) s else s.copy(clips = reindexArr(s.clips.filterNot { it.name == c.name && sameSpan(it.start, it.end, c.start, c.end) }))
    }

    fun arrLacks(tr: String, c: ArrClip): (ArrState) -> Boolean = { s ->
        s.tr != tr || s.clips.none { sameSpan(it.start, it.end, c.start, c.end) && it.name == c.name }
    }

    fun takesWith(tr: String, lane: Int, c: TakeClip): (TakesState) -> TakesState = { s ->
        if (s.tr != tr) s else s.copy(lanes = s.lanes.map { l ->
            if (l.i != lane || l.clips.any { it.name == c.name && sameSpan(it.start, it.end, c.start, c.end) }) l
            else l.copy(clips = (l.clips + c).sortedBy { it.start }.mapIndexed { k, x -> x.copy(i = k) })
        })
    }

    fun takesHas(tr: String, lane: Int, c: TakeClip): (TakesState) -> Boolean = { s ->
        s.tr != tr || s.lanes.firstOrNull { it.i == lane }?.clips?.any { it.name == c.name && sameSpan(it.start, it.end, c.start, c.end) } != false
    }

    fun takesWithout(tr: String, lane: Int, c: TakeClip): (TakesState) -> TakesState = { s ->
        if (s.tr != tr) s else s.copy(lanes = s.lanes.map { l ->
            if (l.i != lane) l
            else l.copy(clips = l.clips.filterNot { it.name == c.name && sameSpan(it.start, it.end, c.start, c.end) }.mapIndexed { k, x -> x.copy(i = k) })
        })
    }

    fun takesLack(tr: String, lane: Int, c: TakeClip): (TakesState) -> Boolean = { s ->
        s.tr != tr || s.lanes.firstOrNull { it.i == lane }?.clips?.none { it.name == c.name && sameSpan(it.start, it.end, c.start, c.end) } != false
    }
}
