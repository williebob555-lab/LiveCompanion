package com.livecompanion

import com.livecompanion.net.DemoClient
import com.livecompanion.net.LiveException
import com.livecompanion.net.SimDelay
import com.livecompanion.net.jsonArgs
import com.livecompanion.state.ClipState
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Loc
import com.livecompanion.state.Note
import com.livecompanion.state.SectionGuard
import com.livecompanion.state.TakeLane
import com.livecompanion.ui.clip.NoteEdit
import com.livecompanion.ui.clip.NoteEditor
import com.livecompanion.ui.track.DROP_MAIN
import com.livecompanion.ui.track.DROP_NONE
import com.livecompanion.ui.track.dropTargetAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TakesAndSettleTest {
    private fun demo(): Pair<LiveStore, DemoClient> {
        val store = LiveStore()
        val dir = File("src/main/assets/demo")
        val c = DemoClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { n -> File(dir, "$n.json").takeIf { it.exists() }?.readText() }, tickMs = 0)
        c.connect("demo", 0)
        return store to c
    }

    // ---------------------------------------------------------------- SectionGuard
    @Test fun guardKeepsEditUntilServerShowsIt() {
        var now = 0L
        val g = SectionGuard<Int>(600, { now })
        g.add({ 5 }, { it == 5 })
        assertEquals(5, g.reconcile(1))          // stale echo: edit re-applied
        now = 300
        assertEquals(5, g.reconcile(2))
        assertEquals(5, g.reconcile(5))          // server shows it: edit dropped
        assertEquals(0, g.pending())
        assertEquals(9, g.reconcile(9))          // later server changes win
    }

    @Test fun guardExpiresAfterSettleWindow() {
        var now = 0L
        val g = SectionGuard<Int>(600, { now })
        g.add({ 5 }, { it == 5 })
        now = 601
        assertEquals(1, g.reconcile(1))          // never matched: give up, server wins
    }

    private fun clipJson(loopEnd: Double) =
        LiveJson.encodeToJsonElement(ClipState.serializer(), ClipState(loopStart = 0.0, loopEnd = loopEnd))

    @Test fun clipEditSurvivesStaleSection() {
        val s = LiveStore()
        s.updateClip { ClipState(loopStart = 0.0, loopEnd = 4.0) }
        s.editClip({ it.copy(loopEnd = 8.0) })
        assertEquals(8.0, s.clip.value.loopEnd, 0.0)
        s.handleSection("clip", clipJson(4.0))
        assertEquals(8.0, s.clip.value.loopEnd, 0.0)       // stale echo did not snap it back
        s.handleSection("clip", clipJson(8.0))
        s.handleSection("clip", clipJson(6.0))
        assertEquals(6.0, s.clip.value.loopEnd, 0.0)       // once matched, the server drives again
    }

    // ---------------------------------------------------------------- NoteEditor settle
    @Test fun noteEditorHoldsLocalEditAgainstStaleList() {
        var now = 0L
        val n0 = listOf(Note(1, 60, 0.0, 1.0, 100), Note(2, 64, 1.0, 1.0, 100))
        val ed = NoteEditor(n0, null) { now }
        ed.busy = true
        ed.modify(listOf(NoteEdit(1, t = 2.0)))
        ed.reconcile(n0)                         // mid-drag echo is deferred
        ed.endGesture()                          // ... and still does not undo the edit
        assertEquals(2.0, ed.notes.first { it.id == 1 }.t, 0.0)
        now = 700
        ed.flushHeld()                           // never matched: the server wins after 600 ms
        assertEquals(0.0, ed.notes.first { it.id == 1 }.t, 0.0)
    }

    @Test fun noteEditorAcceptsMatchingServerListWithRealIds() {
        var now = 0L
        val ed = NoteEditor(emptyList(), null) { now }
        ed.add(listOf(Note(0, 60, 1.0, 0.5, 100)))
        assertTrue(ed.notes.single().id < 0)
        now = 100
        ed.reconcile(listOf(Note(7, 60, 1.0, 0.5, 100)))
        assertEquals(7, ed.notes.single().id)
    }

    // ---------------------------------------------------------------- take drop target
    @Test fun dropTargets() {
        val lanes = listOf(TakeLane(0, "a"), TakeLane(2, "b"))
        // ruler 20, main lane 100 (y 20..120), take lanes 24 each from 120
        assertEquals(DROP_MAIN, dropTargetAt(60f, 20f, 100f, 24f, lanes, srcLane = 2))
        assertEquals(DROP_NONE, dropTargetAt(60f, 20f, 100f, 24f, lanes, srcLane = DROP_MAIN))   // main clip onto main = plain move
        assertEquals(0, dropTargetAt(125f, 20f, 100f, 24f, lanes, srcLane = DROP_MAIN))
        assertEquals(2, dropTargetAt(150f, 20f, 100f, 24f, lanes, srcLane = 0))
        assertEquals(DROP_NONE, dropTargetAt(150f, 20f, 100f, 24f, lanes, srcLane = 2))          // own lane
        assertEquals(DROP_NONE, dropTargetAt(10f, 20f, 100f, 24f, lanes, srcLane = 0))           // ruler
        assertEquals(DROP_NONE, dropTargetAt(400f, 20f, 100f, 24f, lanes, srcLane = 0))          // below the lanes
    }

    // ---------------------------------------------------------------- demo take ops
    @Test fun demoDemotePromoteAndMove() = runBlocking {
        val (s, c) = demo()
        c.request("focus.set", jsonArgs("tr" to "t3"))
        val lanes0 = s.takes.value.lanes
        assertTrue(s.takes.value.supported && lanes0.size >= 2)
        val arr0 = s.arr.value.clips
        val src = arr0.first()
        val laneI = lanes0.first().i
        val n0 = lanes0.first().clips.size
        c.request("takes.demote", jsonArgs("loc" to Loc.arr("t3", src.i), "lane" to laneI))
        assertEquals(arr0.size - 1, s.arr.value.clips.size)
        val moved = s.takes.value.lanes.first { it.i == laneI }.clips
        assertEquals(n0 + 1, moved.size)
        assertTrue(moved.any { it.start == src.start && it.name == src.name })
        // promote it back
        val idx = moved.first { it.start == src.start && it.name == src.name }.i
        c.request("takes.promote", jsonArgs("loc" to Loc.take("t3", laneI, idx)))
        assertEquals(arr0.size, s.arr.value.clips.size)
        // move between lanes
        val other = s.takes.value.lanes.first { it.i != laneI }
        val before = other.clips.size
        c.request("takes.move", jsonArgs("loc" to Loc.take("t3", laneI, 0), "lane" to other.i))
        assertEquals(before + 1, s.takes.value.lanes.first { it.i == other.i }.clips.size)
        // bad requests are refused
        var refused = false
        try { c.request("takes.demote", jsonArgs("loc" to Loc.take("t3", laneI, 0), "lane" to other.i)) } catch (e: LiveException) { refused = true }
        assertTrue(refused)
    }

    @Test fun demoArrMoveAndTrim() = runBlocking {
        val (s, c) = demo()
        c.request("focus.set", jsonArgs("tr" to "t0"))
        val first = s.arr.value.clips.first()
        c.request("arr.move", jsonArgs("loc" to Loc.arr("t0", first.i), "start" to 100.0))
        assertTrue(s.arr.value.clips.any { it.start == 100.0 && it.name == first.name })
        val moved = s.arr.value.clips.first { it.start == 100.0 }
        c.request("arr.trim", jsonArgs("loc" to Loc.arr("t0", moved.i), "end" to moved.start + 2.0))
        assertEquals(moved.start + 2.0, s.arr.value.clips.first { it.start == 100.0 }.end, 1e-9)
    }

    @Test fun demoSlotMoveRefusesOccupiedUnlessSwap() = runBlocking {
        val (s, c) = demo()
        val occ = s.slots.value.slots.filter { it.hasClip }
        assertTrue(occ.size >= 2)
        var refused = false
        try { c.request("slot.move", jsonArgs("tr" to "t0", "i" to occ[0].i, "to_tr" to "t0", "to_i" to occ[1].i)) } catch (e: LiveException) { refused = true }
        assertTrue(refused)
        c.request("slot.move", jsonArgs("tr" to "t0", "i" to occ[0].i, "to_tr" to "t0", "to_i" to occ[1].i, "swap" to true))
        assertEquals(occ[0].clip!!.name, s.slots.value.slots.first { it.i == occ[1].i }.clip!!.name)
        assertEquals(occ[1].clip!!.name, s.slots.value.slots.first { it.i == occ[0].i }.clip!!.name)
    }

    @Test fun demoServesBundledPeaks() = runBlocking {
        val (s, c) = demo()
        c.request("focus.set", jsonArgs("tr" to "t3"))
        val clip = s.arr.value.clips.first { it.audio }
        val d = c.request("clip.peaks", jsonArgs("loc" to Loc.arr("t3", clip.i), "n" to 256))
        val p = com.livecompanion.net.parsePeaks(d.toString())
        assertEquals(256, p.n)
        assertTrue(p.max.any { it > 0.1f })
        assertTrue(p.seconds > 1.0)
    }

    @Test fun simulatedDelayHoldsRequestBack() = runBlocking {
        val (s, c) = demo()
        SimDelay.ms = 120
        try {
            val t0 = System.nanoTime()
            c.request("track.set", jsonArgs("tr" to "t0", "k" to "mute", "v" to true))
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 100)
            assertTrue(s.tracks.value.find("t0")!!.mute)
        } finally { SimDelay.ms = 0 }
    }
}
