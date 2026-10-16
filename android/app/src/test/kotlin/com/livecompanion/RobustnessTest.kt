package com.livecompanion

import com.livecompanion.data.hostPortError
import com.livecompanion.data.parseHostPort
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.HostTarget
import com.livecompanion.net.LiveClient
import com.livecompanion.state.ClipState
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.state.sanitizeJson
import com.livecompanion.ui.clip.clampMarker
import com.livecompanion.ui.clip.markerError
import com.livecompanion.ui.clip.numberError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ValidationTest {
    @Test fun hostPortMessages() {
        assertNull(hostPortError(""))
        assertNull(hostPortError("10.0.2.2"))
        assertNull(hostPortError("studio-pc:9010"))
        assertNull(hostPortError("[::1]:9011"))
        for (bad in listOf(":", "host:abc", "10.0.2.2:99999", "10.0.2.2:-5", "10.0.2.2:0", "a b:1", "!!!", "!!!:9010", "host:", "[::1", "[]:5")) {
            assertNotNull("expected an error for '$bad'", hostPortError(bad))
            assertNull("parse must reject '$bad'", parseHostPort(bad))
        }
        assertEquals(HostTarget("10.0.2.2", 9010), parseHostPort("10.0.2.2"))
    }

    @Test fun numbers() {
        assertNull(numberError("120", 20.0, 999.0))
        assertNull(numberError("120,5", 20.0, 999.0))
        for (bad in listOf("", "abc", "-5", "99999", "NaN", "Infinity")) assertNotNull(bad, numberError(bad, 20.0, 999.0))
        assertNotNull(numberError("1.5", -48.0, 48.0, int = true))
        assertNull(numberError("-48", -48.0, 48.0, int = true))
    }

    private val clip = ClipState(startMarker = 0.0, endMarker = 16.0, loopStart = 0.0, loopEnd = 16.0)

    @Test fun markers() {
        assertNull(markerError("start_marker", 4.0, clip))
        assertNotNull(markerError("start_marker", 16.0, clip))   // start >= end
        assertNotNull(markerError("start_marker", 40002.0, clip))
        assertNotNull(markerError("start_marker", -5.0, clip))
        assertNotNull(markerError("end_marker", 0.0, clip))      // end <= start
        assertNotNull(markerError("loop_start", 17.0, clip))
        assertNotNull(markerError("loop_end", 0.0, clip))
        assertNotNull(markerError("loop_end", Double.NaN, clip))
        assertNotNull(markerError("loop_end", 1e12, clip))
        assertEquals(15.95, clampMarker("start_marker", 500.0, clip)!!, 1e-9)
        assertEquals(0.05, clampMarker("end_marker", -3.0, clip)!!, 1e-9)
    }
}

class SanitizeTest {
    @Test fun nanInfinityHugeAndLongStrings() {
        val raw = LiveJson.parseToJsonElement(
            """{"tempo":NaN,"pos":Infinity,"neg":-Infinity,"big":1e30,"i":3,"name":"${"X".repeat(100_000)}","ok":"fine","t":true,"n":null}""",
        )
        val d = sanitizeJson(raw).toString()
        assertFalse(d.contains("NaN")); assertFalse(d.contains("Infinity")); assertFalse(d.contains("e30"))
        assertTrue(d.length < 1000)
        assertTrue(d.contains("\"t\":true")); assertTrue(d.contains("\"i\":3")); assertTrue(d.contains("\"n\":null"))
    }

    @Test fun storeSurvivesHostileSections() {
        val s = LiveStore()
        fun feed(name: String, json: String) = s.handleSection(name, sanitizeJson(LiveJson.parseToJsonElement(json)))
        feed("session", """{"tempo":NaN,"pos":Infinity,"sig":[4,4]}""")
        feed("tracks", """{"tracks":[{"ref":"t0","name":"${"N".repeat(100_000)}","color":1e30,"kind":"audio"},{"ref":"t1","kind":12345}],"scenes":"nope"}""")
        feed("slots", """{"slots":[{"i":1e400,"status":"???"}]}""")
        feed("clip", """{"loc":null,"notes":[{"p":1e20,"t":-Infinity,"d":"x"}]}""")
        feed("from_the_future", """{"x":[1,2]}""")
        s.handleRt(sanitizeJson(LiveJson.parseToJsonElement("""{"pos":NaN,"meters":"no"}""")))
        assertTrue(s.session.value.tempo.isFinite())
        assertTrue(s.session.value.pos.isFinite())
        feed("session", """{"tempo":99}""")
        assertEquals(99f, s.session.value.tempo)
        // a name this long was cut, not laid out
        s.tracks.value.tracks.firstOrNull()?.let { assertTrue(it.name.length <= 300) }
    }
}

/** Q4-4: minimal / hostile servers, driven through the real client over a real socket (MockWebServer). */
class MinimalServerTest {
    private fun newClient(scope: CoroutineScope, store: LiveStore, helloMs: Long = 2000, silenceMs: Long = 1000) = LiveClient(
        store, scope,
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(500, TimeUnit.MILLISECONDS).build(),
        helloTimeoutMs = helloMs, pingMs = 200, silenceMs = silenceMs,
    )

    /** hello + session only; ignores every client message (no JSON pong). */
    private fun minimalServer(extra: (WebSocket) -> Unit = {}): MockWebServer {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"t":"hello","proto":1,"live":"12.4.1","host":"min"}""")
                webSocket.send("""{"t":"sec","s":"session","d":{"tempo":101.5}}""")
                extra(webSocket)
            }
        }))
        server.start()
        return server
    }

    @Test fun helloAndOneSectionIsConnectedAndStaysConnected() = runBlocking {
        val server = minimalServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LiveStore()
        val client = newClient(scope, store)
        try {
            client.connect(server.hostName, server.port)
            withTimeout(5000) { client.state.first { it.status == ConnStatus.CONNECTED } }
            withTimeout(2000) { store.session.first { it.tempo == 101.5f } }
            // Silence limit is 1 s, JSON pings are never answered: a minimal server must still be kept (hello = connected).
            delay(3000)
            assertEquals(ConnStatus.CONNECTED, client.state.value.status)
            assertTrue(store.tracks.value.tracks.isEmpty()) // missing sections = empty states
            assertEquals(1, server.requestCount)           // never reconnected
        } finally {
            client.disconnect(); scope.cancel(); server.shutdown()
        }
    }

    @Test fun malformedValuesDoNotCrashOrDisconnect() = runBlocking {
        val big = "X".repeat(100_000)
        val server = minimalServer { ws ->
            ws.send("""{"t":"sec","s":"session","d":{"tempo":NaN,"pos":Infinity,"sig":[4,4]}}""")
            ws.send("""{"t":"sec","s":"tracks","d":{"tracks":[{"ref":"t0","name":"$big","color":1e30,"kind":"audio"}]}}""")
            ws.send("""{"t":"sec","s":"tracks","d":{"tracks":"wrong type","scenes":"nope"}}""") // dropped, previous kept
            ws.send("""{"t":"sec","s":"unknown_section","d":{"x":1}}""")
            ws.send("this is not json")
            ws.send("""{"t":"sec","s":"clip","d":{"notes":[{"p":1e20,"t":-Infinity}]}}""")
            ws.send("""{"t":"rt","d":{"pos":NaN,"meters":7}}""")
            ws.send("""{"t":"sec","s":"session","d":{"tempo":77}}""")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LiveStore()
        val client = newClient(scope, store)
        try {
            client.connect(server.hostName, server.port)
            withTimeout(5000) { store.session.first { it.tempo == 77f } }
            delay(1500)
            assertEquals(ConnStatus.CONNECTED, client.state.value.status)
            assertEquals(1, server.requestCount)
            assertEquals("t0", store.tracks.value.tracks.single().ref)
            assertTrue(store.tracks.value.tracks.single().name.length <= 300)
        } finally {
            client.disconnect(); scope.cancel(); server.shutdown()
        }
    }

    @Test fun unreachablePortFailsOnceAndStaysIdle() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free port, nothing listening
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = newClient(scope, LiveStore())
        try {
            client.connect("127.0.0.1", port)
            val st = withTimeout(6000) { client.state.first { it.status == ConnStatus.IDLE } }
            assertNotNull(st.lastError)
            assertTrue(st.lastError!!.startsWith("Could not connect to 127.0.0.1:$port"))
            delay(1200)
            assertEquals(ConnStatus.IDLE, client.state.value.status) // no endless retry before a first hello
        } finally {
            client.disconnect(); scope.cancel()
        }
    }

    @Test fun serverWithoutHelloTimesOut() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = newClient(scope, LiveStore(), helloMs = 800)
        try {
            client.connect(server.hostName, server.port)
            val st = withTimeout(6000) { client.state.first { it.status == ConnStatus.IDLE } }
            assertTrue(st.lastError!!.contains("No hello"))
        } finally {
            client.disconnect(); scope.cancel(); server.shutdown()
        }
    }

    @Test fun plainHttpServerIsRejected() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hi"))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = newClient(scope, LiveStore())
        try {
            client.connect(server.hostName, server.port)
            val st = withTimeout(6000) { client.state.first { it.status == ConnStatus.IDLE } }
            assertNotNull(st.lastError)
        } finally {
            client.disconnect(); scope.cancel(); server.shutdown()
        }
    }
}
