package com.livecompanion

import com.livecompanion.net.Peaks
import com.livecompanion.ui.clip.*
import org.junit.Assert.*
import org.junit.Test

class ClipGridTests {
    private val bb44 = 4.0
    private fun levels(from: Double, to: Double, step: Double, den: Int = 4, num: Int = 4) =
        gridLines(from, to, step, beatLenOf(den), barBeats(num, den)).map { it.t to it.level }

    @Test fun subdivisionSteps() {
        assertEquals(0.25, Subdiv(false, 16, false).fixedBeats, 1e-9)
        assertEquals(1.0 / 6, Subdiv(false, 16, true).fixedBeats, 1e-9)
        assertEquals(4.0, Subdiv(false, 1, false).fixedBeats, 1e-9)
        assertEquals(0.125, Subdiv(false, 32, false).fixedBeats, 1e-9)
        assertEquals("1/16T", Subdiv(false, 16, true).label())
        assertEquals("Auto 1/8", Subdiv(true).label(0.5))
        assertEquals("1/4", stepLabel(1.0)); assertEquals("1/8T", stepLabel(1.0 / 3)); assertEquals("2 bars", stepLabel(8.0))
    }

    @Test fun adaptiveFollowsZoom() {
        // zoomed far in: 1/32; mid: beats; far out: whole bars and beyond
        assertEquals(0.125, adaptiveStep(400f, 1.0, 4.0), 1e-9)
        assertEquals(0.25, adaptiveStep(60f, 1.0, 4.0), 1e-9)
        assertEquals(1.0, adaptiveStep(14f, 1.0, 4.0), 1e-9)
        assertEquals(4.0, adaptiveStep(4f, 1.0, 4.0), 1e-9)
        assertEquals(16.0, adaptiveStep(1f, 1.0, 4.0), 1e-9)
        // monotone: more zoom never gives a coarser step
        var prev = Double.MAX_VALUE
        for (ppb in listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 300f, 1000f)) {
            val s = adaptiveStep(ppb, 1.0, 4.0); assertTrue(s <= prev); prev = s
        }
        assertEquals(Subdiv(false, 8).fixedBeats, gridStepFor(Subdiv(false, 8), 3f, 1.0, 4.0), 1e-9) // fixed ignores zoom
    }

    @Test fun linesLevels44() {
        val l = levels(0.0, 4.0, 0.5)
        assertEquals(listOf(GridLevel.Bar, GridLevel.Sub, GridLevel.Beat, GridLevel.Sub, GridLevel.Beat, GridLevel.Sub, GridLevel.Beat, GridLevel.Sub, GridLevel.Bar), l.map { it.second })
        val gl = gridLines(0.0, 8.0, 1.0, 1.0, 4.0)
        assertEquals(1L, gl.first { it.t == 4.0 }.bar); assertEquals(1L, gl.first { it.t == 5.0 }.bar); assertEquals(0L, gl.first { it.t == 3.0 }.bar) // 0-based bar index
    }

    @Test fun signatureRuler() {
        // 6/8: beat = eighth (0.5), bar = 3 beats
        assertEquals(3.0, barBeats(6, 8), 1e-9)
        val l = levels(0.0, 3.0, 0.5, den = 8, num = 6)
        assertEquals(GridLevel.Bar, l.first().second); assertEquals(GridLevel.Beat, l[1].second); assertEquals(GridLevel.Bar, l.last().second)
        assertEquals(7, l.size)
        // 7/8 bar = 3.5 quarter beats; second bar starts at 3.5
        val l78 = gridLines(0.0, 7.0, 0.5, 0.5, 3.5)
        assertEquals(GridLevel.Bar, l78.first { it.t == 3.5 }.level); assertEquals(1L, l78.first { it.t == 3.5 }.bar)
        // 3/4
        assertEquals(3.0, barBeats(3, 4), 1e-9)
        // adaptive in 6/8 steps through the eighth beat then the bar
        assertEquals(0.5, adaptiveStep(30f, 0.5, 3.0), 1e-9)
        assertEquals(3.0, adaptiveStep(5f, 0.5, 3.0), 1e-9)
        assertEquals(6.0, adaptiveStep(3f, 0.5, 3.0), 1e-9)
    }

    @Test fun tripletLines() {
        val step = Subdiv(false, 8, true).fixedBeats // 1/8T = 1/3 beat
        val l = gridLines(0.0, 2.0, step, 1.0, 4.0)
        assertEquals(7, l.size)
        assertEquals(GridLevel.Bar, l[0].level); assertEquals(GridLevel.Sub, l[1].level); assertEquals(GridLevel.Beat, l[3].level); assertEquals(GridLevel.Beat, l[6].level)
    }

    @Test fun snappingAndLabels() {
        assertEquals(0.5, snapValue(0.4, 0.25), 1e-9)
        assertEquals(1.0 / 3, snapValue(0.3, 1.0 / 3), 1e-9)
        assertEquals(0.4, snapValue(0.4, 0.0), 1e-9)
        assertEquals(0.0, snapStepFor(Subdiv(), false, 50f, 1.0, 4.0), 0.0)
        val g = gridLines(0.0, 4.0, 0.25, 1.0, 4.0)
        assertEquals("1", rulerLabel(g[0], 100f))
        assertEquals("1.2", rulerLabel(g.first { it.t == 1.0 }, 100f))
        assertEquals("1.2.3", rulerLabel(g.first { it.t == 1.5 }, 100f))
        assertNull(rulerLabel(g.first { it.t == 1.5 }, 10f))
        assertEquals(4, labelStride(3f, 4.0)); assertEquals(1, labelStride(100f, 4.0))
    }

    @Test fun secondsAxis() {
        assertEquals(1.0, niceSecStep(60f), 1e-9)
        assertEquals(0.1, niceSecStep(600f), 1e-9)
        val l = secondsLines(0.0, 3.0, 100f)
        assertEquals(GridLevel.Bar, l[0].level)
        assertEquals("1.5s", fmtSeconds(1.5)); assertEquals("1:05", fmtSeconds(65.0)); assertEquals("250ms", fmtSeconds(0.25))
    }

    @Test fun signatureValidationAndScale() {
        assertTrue(validSignature(7, 8)); assertFalse(validSignature(0, 4)); assertFalse(validSignature(4, 3)); assertFalse(validSignature(100, 4))
        val minor = listOf(0, 2, 3, 5, 7, 8, 10)
        assertTrue(inScale(60, 0, minor)); assertFalse(inScale(61, 0, minor)); assertTrue(inScale(63 + 12, 0, minor))
        assertTrue(inScale(69, 9, listOf(0, 2, 4, 5, 7, 9, 11))) // A root, A major
        assertFalse(inScale(60, 0, emptyList()))
    }

    @Test fun gainAndWaveformWindow() {
        assertEquals(0.5012f, gainLinear("-6.0 dB", 0.2f), 1e-3f)
        assertEquals(1f, gainLinear("0.0 dB", 0.4f), 1e-4f)
        assertEquals(0.5f, gainLinear("", 0.2f), 1e-4f)
        val p = Peaks(100, 1, 400, 4, floatArrayOf(-0.1f, -0.4f, -0.8f, -0.2f), floatArrayOf(0.1f, 0.5f, 0.9f, 0.2f))
        val w = peakWindow(p, 4.0, 1.0, 2.0, 1f)!!
        assertEquals(-0.4f, w.first, 1e-6f); assertEquals(0.5f, w.second, 1e-6f)
        val boosted = peakWindow(p, 4.0, 2.0, 3.0, 2f)!!
        assertEquals(-1f, boosted.first, 1e-6f); assertEquals(1f, boosted.second, 1e-6f) // gain-scaled and clamped
        assertNull(peakWindow(p, 4.0, 5.0, 6.0, 1f))
    }

    @Test fun playheadInterpolation() {
        // 120 bpm = 2 beats/s; 50 ms later the head is 0.1 beat further
        assertEquals(1.1, predictPos(1.0, 0.05, 2.0, false, 0.0, 4.0), 1e-9)
        // wraps inside the loop
        assertEquals(0.15, predictPos(3.95, 0.1, 2.0, true, 0.0, 4.0), 1e-9)
        // never runs more than maxAhead past the sample (stalled frames)
        assertEquals(1.5, predictPos(1.0, 5.0, 2.0, false, 0.0, 4.0, maxAheadSec = 0.25), 1e-9)
        // not looping: stops at the end limit
        assertEquals(4.0, predictPos(3.99, 0.2, 2.0, false, 0.0, 4.0, endLimit = 4.0), 1e-9)
        // recording grows without a limit
        assertEquals(10.5, predictPos(10.0, 0.25, 2.0, false, 0.0, 4.0), 1e-9)
    }
}
