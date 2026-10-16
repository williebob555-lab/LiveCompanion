package com.livecompanion

import com.livecompanion.ui.clip.formatBbt
import com.livecompanion.ui.clip.parseBbt
import com.livecompanion.ui.screens.formatPosition
import com.livecompanion.ui.track.Bbt
import com.livecompanion.ui.track.beatUnit
import com.livecompanion.ui.track.beatsPerBar
import com.livecompanion.ui.track.posString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BbtTests {
    private fun p(t: String, n: Int, d: Int, maxBar: Int = 1000) = Bbt.parse(t, beatsPerBar(n, d), beatUnit(d), maxBar)

    @Test fun oneFormatterEverywhere() {
        for ((n, d) in listOf(4 to 4, 3 to 8, 6 to 8, 7 to 4, 5 to 16, 2 to 2)) {
            var x = 0.0
            while (x < 20.0) {
                val a = posString(x, beatsPerBar(n, d), beatUnit(d))
                assertEquals(a, formatPosition(x, n, d))
                assertEquals(a, formatBbt(x, n, d))
                x += 0.0625
            }
        }
    }

    @Test fun threeEightSigAndEndLabel() {
        // 8-beat clip end in 3/8: bar length 1.5 -> bar 6, 0.5 in = beat 2, sixteenth 1.
        assertEquals("6.2.1", formatBbt(8.0, 3, 8))
        assertEquals("1.1.1", formatBbt(0.0, 3, 8))
        assertEquals("1.1.2", formatBbt(0.25, 3, 8))
        assertEquals("1.2.1", formatBbt(0.5, 3, 8))
        assertEquals("2.1.1", formatBbt(1.5, 3, 8))
    }

    @Test fun roundTrips() {
        for ((n, d) in listOf(4 to 4, 3 to 8, 7 to 8, 5 to 4)) {
            val bpb = beatsPerBar(n, d); val u = beatUnit(d)
            var x = 0.0
            while (x < 30.0) {
                assertEquals(x, p(posString(x, bpb, u), n, d).beats!!, 1e-9)
                x += 0.25
            }
        }
    }

    @Test fun transportAndLocateAgreeOnSixteenthBoundary() {
        // Position just under/at a sixteenth boundary must not differ between readouts (was 1.4.3 vs 1.4.4).
        val pos = 3.5 - 1e-10 // 3.5 within float noise
        assertEquals(posString(pos, 4.0, 1.0), formatPosition(pos, 4, 4))
        assertEquals("1.4.3", posString(3.5, 4.0, 1.0))
    }

    @Test fun rejectsInsteadOfWrapping() {
        assertNull(p("2.5.1", 4, 4).beats)
        assertNull(p("2.1.17", 4, 4).beats)
        assertNull(p("2.4.5", 4, 4).beats)
        assertNull(p("1.4.1", 3, 4).beats) // beat 4 in 3/4
        assertNull(p("1.1.3", 3, 8).beats) // only 2 sixteenths per eighth beat
        assertNotNull(p("1.1.2", 3, 8).beats)
        assertNotNull(p("2.4.4", 4, 4).beats)
        assertNull(p("1.1.1", 4, 4).error)
    }

    @Test fun clampsLowAndHigh() {
        assertNotNull(p("0.0.0", 4, 4).error)
        assertNotNull(p("0.1.1", 4, 4).error)
        assertNotNull(p("abc", 4, 4).error)
        assertNotNull(p("-1.1.1", 4, 4).error)
        assertNotNull(p("", 4, 4).error)
        assertNotNull(p("9999.1.1", 4, 4, maxBar = 1100).error)
        assertEquals(0.0, p("1.1.1", 4, 4).beats!!, 0.0)
        assertEquals(16.0, p("5", 4, 4).beats!!, 0.0)
        assertEquals(17.0, p("5.2", 4, 4).beats!!, 0.0)
    }

    @Test fun clipPropsAcceptsDecimalBeats() {
        assertEquals(3.25, parseBbt("3.25", 4, 4)!!, 1e-9)
        assertNull(parseBbt("2.5.1", 4, 4))
    }
}
