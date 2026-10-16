package com.livecompanion.ui.clip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.livecompanion.net.LiveClient
import com.livecompanion.state.Note
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per-field changes for notes.modify; null = unchanged. */
data class NoteEdit(val id: Int, val p: Int? = null, val t: Double? = null, val d: Double? = null, val v: Int? = null, val prob: Float? = null)

/**
 * Local (optimistic) note list + selection for the opened MIDI clip. Edits apply locally at once and are
 * sent as notes.* ops; the next `clip` section (via [reconcile]) is authoritative.
 */
class NoteEditor(initial: List<Note>, private val client: LiveClient?) {
    var notes by mutableStateOf(initial)
        private set
    var sel by mutableStateOf<Set<Int>>(emptySet())

    /** True while a gesture owns the list; server sections are then deferred. */
    var busy = false
    private var pending: List<Note>? = null
    private var tempId = -1

    fun reconcile(server: List<Note>) {
        if (busy) { pending = server; return }
        notes = server
        sel = sel.filterTo(HashSet()) { id -> server.any { it.id == id } }
    }

    fun endGesture() {
        busy = false
        pending?.let { reconcile(it); pending = null }
    }

    fun selectedNotes(): List<Note> = notes.filter { it.id in sel }

    fun add(list: List<Note>) {
        if (list.isEmpty()) return
        val withIds = list.map { it.copy(id = tempId--) }
        // Like Live: a note drawn onto an identical pitch+start replaces the existing one (no duplicates).
        val gone = collisions(withIds.map { it.id }.toSet(), withIds)
        if (gone.isNotEmpty()) dropLocally(gone)
        notes = notes + withIds
        if (gone.isNotEmpty()) client?.op("notes.remove", "ids" to gone.filter { it >= 0 }.toList())
        client?.op("notes.add", "notes" to JsonArray(list.map { noteJson(it, withId = false) }))
    }

    /** Ids of notes NOT in [moved] that sit on the same pitch and start as any of [targets]. */
    private fun collisions(moved: Set<Int>, targets: List<Note>): Set<Int> {
        if (targets.isEmpty()) return emptySet()
        return notes.filter { n -> n.id !in moved && targets.any { it.p == n.p && abs(it.t - n.t) < 1e-6 } }.mapTo(HashSet()) { it.id }
    }

    private fun dropLocally(ids: Set<Int>) {
        notes = notes.filter { it.id !in ids }
        sel = sel - ids
    }

    fun remove(ids: Set<Int>) {
        if (ids.isEmpty()) return
        notes = notes.filter { it.id !in ids }
        sel = sel - ids
        client?.op("notes.remove", "ids" to ids.filter { it >= 0 }.toList())
    }

    fun modify(edits: List<NoteEdit>, send: Boolean = true) {
        if (edits.isEmpty()) return
        val byId = edits.associateBy { it.id }
        notes = notes.map { n ->
            val e = byId[n.id] ?: return@map n
            n.copy(p = e.p ?: n.p, t = e.t ?: n.t, d = e.d ?: n.d, v = e.v ?: n.v, prob = e.prob ?: n.prob)
        }
        if (!send) return
        if (edits.any { it.p != null || it.t != null }) {
            val moved = byId.keys
            val gone = collisions(moved, notes.filter { it.id in moved })
            if (gone.isNotEmpty()) {
                dropLocally(gone)
                client?.op("notes.remove", "ids" to gone.filter { it >= 0 }.toList())
            }
        }
        val arr = edits.filter { it.id >= 0 }.map { e ->
            buildJsonObject {
                put("id", e.id)
                e.p?.let { put("p", it) }; e.t?.let { put("t", it) }; e.d?.let { put("d", it) }
                e.v?.let { put("v", it) }; e.prob?.let { put("prob", it) }
            }
        }
        if (arr.isNotEmpty()) client?.op("notes.modify", "notes" to JsonArray(arr))
    }

    fun transposeSelected(semis: Int) {
        val ids = sel.filter { it >= 0 }
        if (ids.isEmpty()) return
        val s = selectedNotes()
        val d = clampPitchDelta(semis, s.minOf { it.p }, s.maxOf { it.p })
        if (d == 0) return
        notes = notes.map { if (it.id in sel) it.copy(p = it.p + d) else it }
        client?.op("notes.transpose", "ids" to ids, "semis" to d)
    }

    fun duplicateSelected() {
        val s = selectedNotes()
        if (s.isEmpty()) return
        val start = s.minOf { it.t }
        val end = s.maxOf { it.t + it.d }
        val off = end - start
        notes = notes + s.map { it.copy(id = tempId--, t = it.t + off) }
        client?.op("notes.duplicate", "ids" to s.map { it.id }.filter { it >= 0 }, "offset" to off)
    }

    fun deleteSelected() = remove(sel)

    fun selectAll() { sel = notes.mapTo(HashSet()) { it.id } }

    companion object {
        fun noteJson(n: Note, withId: Boolean): JsonObject = buildJsonObject {
            if (withId) put("id", n.id)
            put("p", n.p); put("t", n.t); put("d", n.d); put("v", n.v); put("m", n.m); put("prob", n.prob)
        }
    }
}
