package com.livecompanion.ui.track

import com.livecompanion.net.LiveClient
import com.livecompanion.net.LiveException
import com.livecompanion.net.jsonArgs
import com.livecompanion.state.ArrClip
import com.livecompanion.state.LiveStore
import com.livecompanion.state.TakeClip
import com.livecompanion.state.Loc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Take promote / demote / move with instant feel: the local arr / takes sections change at once (kept for ~600 ms against
 * stale server echoes, see SectionGuard) and the op is sent; if Live refuses (e.g. `unsupported`), the notice is shown and
 * the server state is re-requested so nothing stays wrong.
 */
class TakeOps(private val store: LiveStore, private val client: LiveClient, private val scope: CoroutineScope) {
    private fun fail(tr: String, e: LiveException) {
        store.notice(e.message ?: "Take edit failed")
        store.dropEdits()
        client.op("focus.set", "tr" to tr)      // Live re-sends arr/takes: back to the truth
    }

    private fun send(tr: String, op: String, vararg args: Pair<String, Any?>) {
        scope.launch {
            try { client.request(op, jsonArgs(*args)) } catch (e: LiveException) { fail(tr, e) }
        }
    }

    fun promote(tr: String, lane: Int, i: Int) {
        val tk = store.takes.value.lanes.firstOrNull { it.i == lane }?.clips?.firstOrNull { it.i == i } ?: return
        val audio = store.focus.value.kind != "midi"
        val ac = ArrClip(name = tk.name, color = tk.color, start = tk.start, end = tk.end, audio = audio)
        store.editArr(TakeEdits.arrWith(tr, ac), TakeEdits.arrHas(tr, ac))
        send(tr, "takes.promote", "loc" to Loc.take(tr, lane, i))
    }

    fun demote(tr: String, arrI: Int, lane: Int) {
        val ac = store.arr.value.clips.firstOrNull { it.i == arrI } ?: return
        val tk = TakeClip(0, ac.name, ac.color, ac.start, ac.end)
        store.editArr(TakeEdits.arrWithout(tr, ac), TakeEdits.arrLacks(tr, ac))
        store.editTakes(TakeEdits.takesWith(tr, lane, tk), TakeEdits.takesHas(tr, lane, tk))
        send(tr, "takes.demote", "loc" to Loc.arr(tr, arrI), "lane" to lane)
    }

    fun move(tr: String, lane: Int, i: Int, toLane: Int) {
        if (lane == toLane) return
        val tk = store.takes.value.lanes.firstOrNull { it.i == lane }?.clips?.firstOrNull { it.i == i } ?: return
        store.editTakes(TakeEdits.takesWithout(tr, lane, tk), TakeEdits.takesLack(tr, lane, tk))
        store.editTakes(TakeEdits.takesWith(tr, toLane, tk), TakeEdits.takesHas(tr, toLane, tk))
        send(tr, "takes.move", "loc" to Loc.take(tr, lane, i), "lane" to toLane)
    }
}
