package com.livecompanion

import com.livecompanion.net.ConnStatus
import com.livecompanion.net.DemoClient
import com.livecompanion.net.jsonArgs
import com.livecompanion.state.LiveStore
import com.livecompanion.state.Loc
import com.livecompanion.state.SlotStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DemoClientTest {
    private fun demo(): Pair<LiveStore, DemoClient> {
        val store = LiveStore()
        val dir = File("src/main/assets/demo")
        val c = DemoClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { n -> File(dir, "$n.json").takeIf { it.exists() }?.readText() }, tickMs = 0)
        c.connect("demo", 0)
        return store to c
    }

    @Test fun connectLoadsRealisticSet() {
        val (s, c) = demo()
        assertEquals(ConnStatus.CONNECTED, c.state.value.status)
        assertTrue(s.tracks.value.tracks.size >= 6)
        assertEquals("t0", s.focus.value.ref)
        assertTrue(s.slots.value.slots.any { it.hasClip })
        assertTrue(s.devices.value.devices.isNotEmpty())
    }

    @Test fun trackAndTransportOps() = runBlocking {
        val (s, c) = demo()
        c.request("track.set", jsonArgs("tr" to "t0", "k" to "mute", "v" to true))
        assertTrue(s.tracks.value.find("t0")!!.mute); assertTrue(s.focus.value.mute)
        c.request("track.set", jsonArgs("tr" to "t0", "k" to "vol", "v" to 0.5))
        assertEquals(0.5f, s.focus.value.vol.v, 0.001f)
        c.request("song.set", jsonArgs("k" to "tempo", "v" to 140.0))
        assertEquals(140f, s.session.value.tempo, 0.01f)
        c.request("focus.set", jsonArgs("tr" to "t3"))
        assertEquals("t3", s.focus.value.ref); assertEquals("t3", s.slots.value.tr)
        // names
        c.request("track.set", jsonArgs("tr" to "t3", "k" to "name", "v" to "Lead"))
        assertEquals("Lead", s.focus.value.name)
        val n = s.tracks.value.tracks.size
        c.request("track.create", jsonArgs("kind" to "audio", "i" to 1))
        assertEquals(n + 1, s.tracks.value.tracks.size)
        c.request("track.delete", jsonArgs("tr" to s.focus.value.ref))
        assertEquals(n, s.tracks.value.tracks.size)
    }

    @Test fun playbackAdvancesAndLoops() = runBlocking {
        val (s, c) = demo()
        c.request("song.play")
        c.tick(1.0)
        assertTrue(s.session.value.playing)
        assertEquals(s.session.value.tempo / 60.0, s.session.value.pos, 0.001)
        assertTrue(s.rt.value.playing)
        c.request("song.set", jsonArgs("k" to "loop", "v" to true))
        c.request("song.set", jsonArgs("k" to "loop_len", "v" to 4.0))
        c.request("song.seek", jsonArgs("beats" to 3.9))
        c.tick(0.5)
        assertTrue(s.session.value.pos < 4.0)
        c.request("song.stop")
        assertFalse(s.session.value.playing)
    }

    @Test fun slotFireAndNotes() = runBlocking {
        val (s, c) = demo()
        val i = s.slots.value.slots.first { it.hasClip }.i
        c.request("slot.fire", jsonArgs("tr" to "t0", "i" to i))
        assertEquals(SlotStatus.PLAYING, s.slots.value.slots.first { it.i == i }.status)
        c.request("clip.open", jsonArgs("loc" to Loc.slot("t0", i)))
        val before = s.clip.value.notes.size
        assertTrue(before > 0)
        c.request("clip.set", jsonArgs("loc" to Loc.slot("t0", i), "k" to "name", "v" to "Renamed"))
        assertEquals("Renamed", s.clip.value.name)
        assertEquals("Renamed", s.slots.value.slots.first { it.i == i }.clip!!.name)
        val ids = s.clip.value.notes.take(2).map { it.id }
        c.request("notes.remove", jsonArgs("ids" to ids))
        assertEquals(before - 2, s.clip.value.notes.size)
        c.request("clip.close")
        assertEquals(null, s.clip.value.loc)
    }

    @Test fun unknownOpIsOkWithNotice() = runBlocking {
        val (s, c) = demo()
        c.request("song.undo")
        val r = c.request("totally.unknown")
        assertEquals(kotlinx.serialization.json.JsonNull, r)
    }
}
