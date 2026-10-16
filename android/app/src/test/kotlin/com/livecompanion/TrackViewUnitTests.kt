package com.livecompanion

import com.livecompanion.state.ArrClip
import com.livecompanion.ui.track.PlayheadClock
import com.livecompanion.ui.track.Snap
import com.livecompanion.ui.track.beatUnit
import com.livecompanion.ui.track.beatsPerBar
import com.livecompanion.ui.track.contentBeat
import com.livecompanion.ui.track.edgeZone
import com.livecompanion.ui.track.extrapolate
import com.livecompanion.ui.track.parsePos
import com.livecompanion.ui.track.placeNotes
import com.livecompanion.ui.track.posString
import com.livecompanion.ui.track.snapTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackViewUnitTests {
    @Test fun signatureMath() {
        assertEquals(4.0, beatsPerBar(4, 4), 1e-9)
        assertEquals(3.0, beatsPerBar(6, 8), 1e-9)
        assertEquals(0.5, beatUnit(8), 1e-9)
        assertEquals(0.25, Snap.S16.beats(1.0, 4.0), 1e-9)
        assertEquals(4.0, Snap.Bar.beats(1.0, 4.0), 1e-9)
        assertEquals(0.0, Snap.Off.beats(1.0, 4.0), 1e-9)
        assertEquals(1.0, Snap.Beat.beats(1.0, 4.0), 1e-9)
    }

    @Test fun snapping() {
        assertEquals(4.25, snapTo(4.2, 0.25), 1e-9)
        assertEquals(4.2, snapTo(4.2, 0.0), 1e-9)
        assertEquals(8.0, snapTo(6.5, 4.0), 1e-9)
    }

    @Test fun positionStringAndParseRoundTrip() {
        assertEquals("1.1.1", posString(0.0, 4.0, 1.0))
        assertEquals("2.1.1", posString(4.0, 4.0, 1.0))
        assertEquals("3.2.3", posString(8.0 + 1.0 + 0.5, 4.0, 1.0))
        // 6/8: beat = eighth note
        assertEquals("2.1.1", posString(3.0, 3.0, 0.5))
        assertEquals("1.3.1", posString(1.0, 3.0, 0.5))
        for (b in listOf(0.0, 0.25, 3.75, 17.5, 33.25)) {
            val s = posString(b, 4.0, 1.0)
            assertEquals(b, parsePos(s, 4.0, 1.0)!!, 1e-9)
        }
        assertEquals(16.0, parsePos("5", 4.0, 1.0)!!, 1e-9)
        assertEquals(17.0, parsePos("5.2", 4.0, 1.0)!!, 1e-9)
        assertEquals(17.5, parsePos("5.2.3", 4.0, 1.0)!!, 1e-9)
        assertNull(parsePos("0.1.1", 4.0, 1.0))
        assertNull(parsePos("a.b", 4.0, 1.0))
        assertNull(parsePos("", 4.0, 1.0))
        assertNull(parsePos("1.2.3.4", 4.0, 1.0))
    }

    @Test fun extrapolationFollowsTempoAndLoop() {
        assertEquals(10.0, extrapolate(10.0, false, 120.0, 1.0, false, 0.0, 0.0), 1e-9)
        assertEquals(10.3, extrapolate(10.0, true, 60.0, 0.3, false, 0.0, 0.0), 1e-9)
        assertEquals(10.6, extrapolate(10.0, true, 120.0, 0.3, false, 0.0, 0.0), 1e-9)
        // stalled frames cap extrapolation (0.35 s at 120 bpm = 0.7 beat)
        assertEquals(10.7, extrapolate(10.0, true, 120.0, 5.0, false, 0.0, 0.0), 1e-9)
        // loop 0..4: from 3.8 + 0.4 beat wraps to 0.2
        assertEquals(0.2, extrapolate(3.8, true, 120.0, 0.2, true, 0.0, 4.0), 1e-9)
    }

    @Test fun clockScrubHoldsLocalPosition() {
        val c = PlayheadClock()
        c.setSong(120.0, false, 0.0, 4.0)
        c.onFrame(5.0, true, 1_000_000_000L)
        c.tick(1_500_000_000L)               // +0.5 s @120 = +1 beat (smoothing converges toward 6.0)
        assertTrue(c.shown > 5.0)
        c.scrubTo(20.0, 2_000_000_000L)
        c.onFrame(6.0, true, 2_100_000_000L) // stale server frame must be ignored while scrubbing
        c.tick(2_100_000_000L)
        assertEquals(20.0, c.shown, 1e-9)
        c.endScrub(2_200_000_000L)
        c.onFrame(7.0, true, 2_300_000_000L) // still inside the hold window
        c.tick(2_400_000_000L)
        assertTrue(c.shown >= 20.0 && c.shown < 21.0)
        c.onFrame(25.0, true, 3_000_000_000L) // after the hold the server wins again (jump > 1.5 beats snaps)
        assertEquals(25.0, c.shown, 1e-9)
    }

    @Test fun clipContentMappingWrapsLoop() {
        val c = ArrClip(start = 8.0, end = 24.0, looping = true, startMarker = 1.0, endMarker = 5.0, loopStart = 1.0, loopEnd = 5.0)
        assertEquals(1.0, contentBeat(c, 0.0), 1e-9)
        assertEquals(4.5, contentBeat(c, 3.5), 1e-9)
        assertEquals(1.5, contentBeat(c, 4.5), 1e-9)   // wrapped to loop start
    }

    @Test fun notePlacementTilesLoop() {
        val c = ArrClip(
            start = 0.0, end = 8.0, looping = true, startMarker = 0.0, endMarker = 4.0, loopStart = 0.0, loopEnd = 4.0,
            pn = listOf(listOf(0.0, 1.0, 60.0), listOf(2.0, 1.0, 62.0)),
        )
        val got = ArrayList<Triple<Double, Double, Int>>()
        placeNotes(c, 8.0) { r, d, p -> got.add(Triple(r, d, p)) }
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0), got.map { it.first }.sorted())
        // non looping: clipped at clip length
        val n = ArrClip(start = 0.0, end = 3.0, looping = false, pn = listOf(listOf(0.0, 1.0, 60.0), listOf(2.5, 2.0, 62.0), listOf(5.0, 1.0, 64.0)))
        val g2 = ArrayList<Triple<Double, Double, Int>>()
        placeNotes(n, 3.0) { r, d, p -> g2.add(Triple(r, d, p)) }
        assertEquals(2, g2.size)
        assertEquals(0.5, g2[1].second, 1e-9)
    }

    @Test fun edgeZones() {
        assertEquals(-1, edgeZone(102f, 100f, 400f, 40f))
        assertEquals(1, edgeZone(395f, 100f, 400f, 40f))
        assertEquals(0, edgeZone(250f, 100f, 400f, 40f))
        assertEquals(0, edgeZone(104f, 100f, 112f, 40f))   // tiny clip: no edge zone, body drag
    }
}
