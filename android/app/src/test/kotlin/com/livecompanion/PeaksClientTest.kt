package com.livecompanion

import com.livecompanion.net.Peaks
import com.livecompanion.net.PeaksClient
import com.livecompanion.net.PeaksResult
import com.livecompanion.net.peaksVer
import com.livecompanion.state.RecEnvBuf
import com.livecompanion.state.RecEnvFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeaksClientTest {
    private val loc = buildJsonObject { put("w", "arr"); put("tr", "t2"); put("i", 0) }
    private val real = """{"sr":44100,"ch":1,"frames":88200,"n":2,"min":[[-0.5,-1.0]],"max":[[0.5,1.0]],"src":"asd"}"""
    private val approx = """{"sr":1000,"ch":1,"frames":2000,"n":2,"min":[[-0.2,-0.3]],"max":[[0.2,0.3]],"approx":true}"""

    @Test fun failuresAndApproxAreNeverCached() = runBlocking {
        var calls = 0
        val answers = listOf<() -> String>({ throw RuntimeException("audio file locked by Live") }, { approx }, { real })
        val c = PeaksClient(liveFetch = { _, _ -> answers[calls++]() })
        assertTrue(c.get("", "/a.wav", 2, loc) is PeaksResult.Err)
        assertNull(c.cached("/a.wav", 2))
        val r2 = c.get("", "/a.wav", 2, loc)
        assertTrue(r2 is PeaksResult.Ok && (r2 as PeaksResult.Ok).peaks.approx)
        assertNull(c.cached("/a.wav", 2))
        val r3 = c.get("", "/a.wav", 2, loc)
        assertTrue(r3 is PeaksResult.Ok && !(r3 as PeaksResult.Ok).peaks.approx)
        assertNotNull(c.cached("/a.wav", 2))
        c.get("", "/a.wav", 2, loc)
        assertEquals(3, calls)                               // the real one is served from cache
    }

    @Test fun awaitKeepsRetryingUntilTheRealWaveform() = runBlocking {
        var calls = 0
        val c = PeaksClient(liveFetch = { _, _ ->
            when (calls++) { 0 -> throw RuntimeException("locked"); 1 -> approx; 2 -> throw RuntimeException("locked"); else -> real }
        })
        val seen = ArrayList<String>()
        c.await("", "/b.wav", 2, loc, "x", retryMs = { 0L }) { r ->
            seen += when (r) { is PeaksResult.Err -> "err"; is PeaksResult.Ok -> if (r.peaks.approx) "approx" else "real" }
        }
        assertEquals(listOf("err", "approx", "err", "real"), seen)
    }

    @Test fun refetchesWhenClipLengthChanges() = runBlocking {
        var calls = 0
        val c = PeaksClient(liveFetch = { _, _ -> calls++; real })
        c.get("", "/c.wav", 2, loc, peaksVer(8.0))
        c.get("", "/c.wav", 2, loc, peaksVer(8.0))
        assertEquals(1, calls)
        c.get("", "/c.wav", 2, loc, peaksVer(16.0))          // same file path, longer recording -> new key
        assertEquals(2, calls)
    }

    @Test fun putIgnoresApprox() {
        val c = PeaksClient()
        c.put("/d.wav", 2, Peaks(1000, 1, 10, 1, floatArrayOf(-1f), floatArrayOf(1f), approx = true))
        assertNull(c.cached("/d.wav", 2))
    }

    @Test fun envelopeIsInterpolatedNotStaircase() {
        val b = RecEnvBuf()
        b.accept(RecEnvFrame("arr", 0.0625, 0, 0, listOf(0, 100)))
        assertEquals(0f, b.smoothAt(0.0), 1e-6f)
        assertEquals(0.5f, b.smoothAt(0.03125), 1e-4f)       // halfway between the two points
        assertEquals(0.25f, b.smoothAt(0.015625), 1e-4f)
        assertEquals(-1f, b.smoothAt(1.0), 1e-6f)
        assertFalse(b.smoothAt(0.02f.toDouble()) == b.at(0.02))
    }
}
