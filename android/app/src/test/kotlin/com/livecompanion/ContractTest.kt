package com.livecompanion

import com.livecompanion.state.ArrState
import com.livecompanion.state.ClipState
import com.livecompanion.state.DevicesState
import com.livecompanion.state.FocusState
import com.livecompanion.state.LiveJson
import com.livecompanion.state.ParamsState
import com.livecompanion.state.RtFrame
import com.livecompanion.state.SessionState
import com.livecompanion.state.SlotsState
import com.livecompanion.state.TakesState
import com.livecompanion.state.TracksState
import com.livecompanion.ui.screens.BrowserItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract test: real server messages (dumped by tools/dump_fixtures.py from the mock Core) decoded with the app's
 * real Json config + models. Besides decoding, every key the server sends must be modelled (no silently dropped
 * fields), and key fields must be non-default. Re-run `python tools/dump_fixtures.py` when the protocol changes.
 */
class ContractTest {
    private val dir: File = File(javaClass.classLoader!!.getResource("fixtures")!!.toURI())

    private fun load(name: String): JsonElement =
        LiveJson.parseToJsonElement(File(dir, "$name.json").readText(Charsets.UTF_8)).jsonObject.let { it["d"] ?: it }

    /** Every non-null key in [fx] must also appear in the re-encoded model [enc] (same path). */
    private fun assertCovered(fx: JsonElement, enc: JsonElement?, path: String, bad: MutableList<String>) {
        when (fx) {
            is JsonObject -> {
                val eo = enc as? JsonObject
                for ((k, v) in fx) {
                    if (v is JsonNull) continue
                    // maps (rt.slot_pos) and verbatim objects (clip.loc) re-encode with their own keys
                    val ev = eo?.get(k)
                    if (ev == null) bad += "$path.$k" else assertCovered(v, ev, "$path.$k", bad)
                }
            }
            is JsonArray -> fx.forEachIndexed { i, v -> assertCovered(v, (enc as? JsonArray)?.getOrNull(i), "$path[$i]", bad) }
            else -> Unit
        }
    }

    private fun <T> check(name: String, ser: KSerializer<T>): T {
        val fx = load(name)
        val m = LiveJson.decodeFromJsonElement(ser, fx)
        val bad = ArrayList<String>()
        assertCovered(fx, LiveJson.encodeToJsonElement(ser, m), name, bad)
        assertTrue("server keys missing from Kotlin model: $bad", bad.isEmpty())
        return m
    }

    private fun names(prefix: String) = dir.list()!!.filter { it.startsWith(prefix) }.map { it.removeSuffix(".json") }.sorted()

    @Test fun fixturesExist() {
        assertTrue(dir.list()!!.size >= 20)
    }

    @Test fun session() {
        val s = check("sec_session", SessionState.serializer())
        assertTrue(s.tempo > 20f); assertTrue(s.selTrack.isNotEmpty()); assertTrue(s.songLen >= 0.0)
    }

    @Test fun tracks() {
        val t = check("sec_tracks", TracksState.serializer())
        assertTrue(t.tracks.size >= 6)
        assertTrue(t.tracks.any { it.kind == "audio" }); assertTrue(t.tracks.any { it.kind == "midi" }); assertTrue(t.tracks.any { it.kind == "group" })
        assertTrue(t.tracks.all { it.ref.isNotEmpty() && it.name.isNotEmpty() })
        assertTrue(t.scenes.isNotEmpty())
        assertTrue(t.returns.isNotEmpty() && t.returns.all { it.vol != null && it.vol!!.max > 0f && it.vol!!.str.isNotEmpty() })
        assertTrue(t.master?.vol != null)
        assertTrue(t.tracks.any { it.group != null })
    }

    @Test fun focus() {
        for (n in names("sec_focus_")) {
            val f = check(n, FocusState.serializer())
            assertTrue(n, f.ref.isNotEmpty() && f.name.isNotEmpty())
            assertTrue(n, f.vol.max > f.vol.min && f.vol.str.isNotEmpty())
            assertTrue(n, f.pan.max > f.pan.min)
        }
        val a = check("sec_focus_audio", FocusState.serializer())
        assertTrue(a.sends.isNotEmpty() && a.inTypes.isNotEmpty() && a.outTypes.isNotEmpty())
    }

    @Test fun slots() {
        for (n in names("sec_slots_")) check(n, SlotsState.serializer())
        val s = check("sec_slots_midi", SlotsState.serializer())
        assertTrue(s.slots.any { it.hasClip && it.clip != null && it.clip!!.len > 0 && it.clip!!.name.isNotEmpty() })
    }

    @Test fun arr() {
        for (n in names("sec_arr_")) check(n, ArrState.serializer())
        val a = check("sec_arr_audio", ArrState.serializer())
        assertTrue(a.clips.any { it.audio && it.end > it.start })
    }

    @Test fun takes() {
        for (n in names("sec_takes_")) check(n, TakesState.serializer())
        val t = check("sec_takes_audio", TakesState.serializer())
        assertTrue(t.supported && t.lanes.isNotEmpty() && t.lanes.any { l -> l.clips.any { it.end > it.start } })
    }

    @Test fun devices() {
        for (n in names("sec_devices_")) check(n, DevicesState.serializer())
        val d = check("sec_devices_midi", DevicesState.serializer())
        val rack = d.devices.first { it.type == "rack" }
        assertTrue(rack.chains.isNotEmpty() && rack.cls.isNotEmpty() && rack.chains.any { it.devices.isNotEmpty() })
        val all = names("sec_devices_").flatMap { check(it, DevicesState.serializer()).devices }
        assertTrue(all.any { it.macros?.isNotEmpty() == true })
    }

    @Test fun params() {
        val p = check("sec_params_rack", ParamsState.serializer())
        assertTrue(p.params.isNotEmpty() && p.name.isNotEmpty() && p.params.any { it.max > it.min })
    }

    @Test fun nullSectionsClearEditorState() {
        val s = fakeStore()
        val msg = { n: String -> LiveJson.parseToJsonElement(File(dir, "null_sec_$n.json").readText(Charsets.UTF_8)).jsonObject["d"]!! }
        s.handleSection("clip", load("sec_clip_midi_slot"))
        s.handleSection("params", load("sec_params_rack"))
        assertTrue(s.clip.value.loc != null && s.params.value.params.isNotEmpty())
        s.handleSection("clip", msg("clip"))
        s.handleSection("params", msg("params"))
        assertTrue(s.clip.value.loc == null && s.params.value.params.isEmpty())
    }

    @Test fun clips() {
        for (n in names("sec_clip_")) {
            val c = check(n, ClipState.serializer())
            assertTrue(n, c.loc != null && c.name.isNotEmpty() && c.len > 0.0 && c.loopEnd > 0.0 && c.endMarker > 0.0)
        }
        val au = check("sec_clip_audio_slot", ClipState.serializer())
        assertTrue(au.audio && au.filePath.isNotEmpty() && au.sampleRate > 0 && au.sampleLen > 0 && au.warpMarkers.isNotEmpty())
        assertTrue(au.warpModes.isNotEmpty() && au.warpMode.isNotEmpty() && au.gainStr.isNotEmpty() && au.gain > 0f)
        val take = check("sec_clip_audio_take", ClipState.serializer())
        assertEquals("take", take.loc!!["w"].toString().trim('"')); assertTrue(take.start > 0.0 && take.end > take.start)
        val arr = check("sec_clip_audio_arr", ClipState.serializer())
        assertTrue(arr.end > arr.start)
        val mi = check("sec_clip_midi_slot", ClipState.serializer())
        assertTrue(!mi.audio && mi.notes.isNotEmpty() && mi.notesTotal > 0)
        assertTrue(mi.notes.all { it.d > 0.0 && it.v > 0 && it.p in 0..127 && it.id != 0 })
    }

    @Test fun rt() {
        val r = check("rt", RtFrame.serializer())
        assertTrue(r.playing && r.pos > 0.0 && (r.meters.focus.isNotEmpty()) && r.meters.master.isNotEmpty())
        val rs = check("rt_slots", RtFrame.serializer())
        assertTrue(rs.slotPos.isNotEmpty())
    }

    @Test fun browser() {
        for (n in names("res_browser_")) {
            val l = LiveJson.decodeFromJsonElement(ListSerializer(BrowserItem.serializer()), load(n))
            assertTrue(n, l.isNotEmpty() && l.all { it.name.isNotEmpty() })
        }
        val roots = LiveJson.decodeFromJsonElement(ListSerializer(BrowserItem.serializer()), load("res_browser_roots"))
        assertTrue(roots.all { it.folder && !it.key.isNullOrEmpty() })
    }
}
