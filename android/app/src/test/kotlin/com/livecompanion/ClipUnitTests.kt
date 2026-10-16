package com.livecompanion

import com.livecompanion.net.parsePeaks
import com.livecompanion.net.peaksUrl
import com.livecompanion.state.Note
import com.livecompanion.state.WarpMarker
import com.livecompanion.ui.clip.*
import org.junit.Assert.*
import org.junit.Test

class ClipUnitTests {
    @Test fun snapping() {
        assertEquals(0.5, snapNearest(0.4, 0.25, true), 1e-9)
        assertEquals(0.25, snapFloor(0.4, 0.25, true), 1e-9)
        assertEquals(0.4, snapNearest(0.4, 0.25, false), 1e-9)
    }

    @Test fun hitTesting() {
        val notes = listOf(Note(id = 1, p = 60, t = 1.0, d = 1.0), Note(id = 2, p = 62, t = 1.0, d = 0.01))
        assertEquals(0, hitNote(notes, 1.5, 60, 0.1))
        assertEquals(-1, hitNote(notes, 1.5, 61, 0.1))
        assertEquals(1, hitNote(notes, 1.05, 62, 0.1)) // tiny note widened to touch size
        assertEquals(-1, hitNote(notes, 2.5, 60, 0.1))
        assertTrue(inResizeZone(notes[0], 1.95, 0.2))
        assertFalse(inResizeZone(notes[0], 1.2, 0.2))
        assertEquals(setOf(1), notesInBox(notes, 0.0, 3.0, 59, 60))
        assertEquals(setOf(1, 2), notesInBox(notes, 3.0, 0.0, 70, 50))
    }

    @Test fun moveResize() {
        assertEquals(0.25, moveDelta(1.0, 0.3, 1.0, 0.25, true), 1e-9)
        assertEquals(-1.0, moveDelta(1.0, -5.0, 1.0, 0.25, true), 1e-9) // clamp at 0
        assertEquals(0.5, resizeDelta(2.0, 0.6, 1.0, 0.25, true, 0.25), 1e-9)
        assertEquals(-0.75, resizeDelta(2.0, -5.0, 1.0, 0.25, true, 0.25), 1e-9) // min length
        assertEquals(-60, clampPitchDelta(-100, 60, 70))
    }

    @Test fun bbt() {
        assertEquals("1.1.1", formatBbt(0.0, 4, 4))
        assertEquals("2.2.3", formatBbt(4.0 + 1.0 + 0.5, 4, 4))
        assertEquals(5.5, parseBbt("2.2.3", 4, 4)!!, 1e-9)
        assertEquals(3.25, parseBbt("3.25", 4, 4)!!, 1e-9)
        assertNull(parseBbt("x", 4, 4))
        assertEquals("C3", pitchName(60))
    }

    @Test fun peaksJson() {
        val p = parsePeaks("""{"sr":44100,"ch":2,"frames":88200,"n":3,"min":[[-0.5,-0.1,0],[-0.2,-0.9,0]],"max":[[0.5,0.1,0],[0.7,0.3,0.1]]}""")
        assertEquals(3, p.n); assertEquals(2.0, p.seconds, 1e-9)
        assertEquals(-0.5f, p.min[0]); assertEquals(-0.9f, p.min[1]); assertEquals(0.7f, p.max[0]); assertEquals(0.1f, p.max[2])
        assertThrows(Exception::class.java) { parsePeaks("""{"ok":false,"err":"nope"}""") }
        assertThrows(Exception::class.java) { parsePeaks("garbage") }
        assertEquals("C:/a b/x.wav", peaksUrl("10.0.0.2", "C:/a b/x.wav", 512)!!.queryParameter("path"))
    }

    @Test fun audioMap() {
        val m = AudioMap(true, listOf(WarpMarker(0.0, 0.0), WarpMarker(4.0, 2.0), WarpMarker(8.0, 6.0)), 10.0, 8.0)
        assertEquals(1.0, m.secOf(2.0), 1e-9)
        assertEquals(4.0, m.secOf(6.0), 1e-9)
        assertEquals(6.0, m.axisOf(m.secOf(6.0)), 1e-9)
        assertEquals(8.0, m.secOf(10.0), 1e-9) // extrapolated past the last marker (slope 1 s/beat)
        assertEquals(3.0, AudioMap(false, emptyList(), 10.0, 8.0).secOf(3.0), 1e-9)
        assertEquals(256, peakBuckets(100, 10.0, 10.0))
        assertEquals(2048, peakBuckets(400, 40.0, 4.0).coerceAtMost(2048))
    }
}
