package com.livecompanion

import com.livecompanion.net.parsePeaks
import com.livecompanion.state.LiveJson
import com.livecompanion.state.RecEnvBuf
import com.livecompanion.state.RecEnvFrame
import com.livecompanion.state.RtFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecEnvTest {
    @Test fun rtFrameParsesRecEnv() {
        val f = LiveJson.decodeFromString(RtFrame.serializer(), """{"pos":4.0,"rec_env":{"k":"s0","step":0.25,"f":16,"i":16,"v":[10,50,100]}}""")
        assertEquals("s0", f.recEnv!!.k)
        assertEquals(listOf(10, 50, 100), f.recEnv!!.v)
        assertEquals(null, LiveJson.decodeFromString(RtFrame.serializer(), """{"pos":1.0}""").recEnv)
    }

    @Test fun bufMergesOverlappingFramesAndResetsOnNewRecording() {
        val b = RecEnvBuf()
        b.accept(RecEnvFrame("s0", 0.25, 16, 16, listOf(10, 20, 30)))
        b.accept(RecEnvFrame("s0", 0.25, 16, 18, listOf(30, 40, 50)))          // overlaps index 18
        assertEquals(0.1f, b.at(4.0), 1e-6f)                                    // index 16
        assertEquals(0.4f, b.at(4.5 + 0.25), 1e-6f)                             // index 19
        assertEquals(0.5f, b.at(5.0), 1e-6f)                                    // index 20
        assertEquals(-1f, b.at(5.25), 1e-6f)                                    // not recorded yet
        assertEquals(-1f, b.at(3.5), 1e-6f)                                     // before the start
        b.accept(RecEnvFrame("s0", 0.25, 64, 64, listOf(70)))                   // a new take in the same slot
        assertEquals(-1f, b.at(4.0), 1e-6f)
        assertEquals(0.7f, b.at(16.0), 1e-6f)
        b.accept(null)
        assertEquals(0.7f, b.at(16.0), 1e-6f)
    }

    @Test fun gapBetweenFramesIsFilled() {
        val b = RecEnvBuf()
        b.accept(RecEnvFrame("arr", 0.25, 0, 0, listOf(20)))
        b.accept(RecEnvFrame("arr", 0.25, 0, 4, listOf(60)))
        assertTrue(b.at(0.5) >= 0f)
    }

    @Test fun approxPeaksFlagParsedAndExactIsNot() {
        val a = parsePeaks("""{"sr":1000,"ch":1,"frames":4000,"n":2,"min":[[-0.5,-0.2]],"max":[[0.5,0.2]],"approx":true}""")
        assertTrue(a.approx)
        assertFalse(parsePeaks("""{"sr":44100,"ch":1,"frames":88200,"n":1,"min":[[-0.5]],"max":[[0.5]]}""").approx)
    }
}
