package com.livecompanion

import com.livecompanion.data.parseHostPort
import com.livecompanion.net.Beacon
import com.livecompanion.net.HostTarget
import com.livecompanion.net.LiveClient
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.jsonArgs
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.state.SlotStatus
import com.livecompanion.ui.components.onColor
import com.livecompanion.ui.screens.formatPosition
import com.livecompanion.update.UpdateCheck
import com.livecompanion.update.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.CopyOnWriteArrayList

class ModelsTest {
    @Test fun decodesFakeStateAndIgnoresUnknownKeys() {
        val s = fakeStore()
        assertEquals(122.5f, s.session.value.tempo)
        assertEquals(6, s.slots.value.slots.size)
        assertEquals(SlotStatus.PLAYING, s.slots.value.slots[1].status)
        assertEquals("r0", s.tracks.value.returns[0].ref)
        assertEquals(0.8f, s.rt.value.meters.focusR)
        assertEquals(3.0, s.rt.value.slotPos["1"]!!, 0.0)
        s.handleSection("future", LiveJson.parseToJsonElement("""{"x":1}"""))
        s.handleSection("session", LiveJson.parseToJsonElement("""{"tempo":90,"brand_new":true}"""))
        assertEquals(90f, s.session.value.tempo)
        assertEquals(listOf(4, 4), s.session.value.sig) // default for missing field
    }

    @Test fun deviceAndClipSections() {
        val s = LiveStore()
        s.handleSection("devices", LiveJson.parseToJsonElement(
            """{"tr":"t0","devices":[{"path":"0","name":"Rack","type":"rack","on":false,"chains":[{"i":0,"name":"c","devices":[{"path":"0.c0.0","name":"EQ"}]}],"macros":[{"i":0,"name":"M1","v":1.0}],"var":{"count":2,"sel":0}}]}"""))
        val d = s.devices.value.devices[0]
        assertFalse(d.on); assertEquals("EQ", d.chains[0].devices[0].name); assertEquals(2, d.variations!!.count)
        s.handleSection("clip", LiveJson.parseToJsonElement(
            """{"loc":{"w":"slot","tr":"t0","i":3},"name":"c","audio":false,"notes":[{"id":1,"p":60,"t":0.0,"d":1.0,"v":100,"m":false,"prob":1,"vd":0,"rv":64}]}"""))
        assertEquals("slot", s.clip.value.loc!!["w"]!!.jsonPrimitive.content)
        assertEquals(1, s.clip.value.notes.size)
    }

    @Test fun optimisticHelpers() {
        val s = fakeStore()
        s.patchTrack("t1") { it.copy(mute = true) }
        assertTrue(s.tracks.value.tracks[1].mute)
        s.focusPending("t2")
        assertEquals("3 Keys", s.focus.value.name)
        assertEquals("t2", s.slots.value.tr)
        assertTrue(s.slots.value.slots.isEmpty())
    }
}

class MiscTest {
    @Test fun hostParsing() {
        assertEquals(HostTarget("10.0.0.5", 9010), parseHostPort("10.0.0.5"))
        assertEquals(HostTarget("pc", 1234), parseHostPort(" pc:1234 "))
        assertNull(parseHostPort("pc:abc"))
        assertNull(parseHostPort(""))
        assertEquals(HostTarget("::1", 9011), parseHostPort("[::1]:9011"))
    }

    @Test fun beacon() {
        val h = Beacon.parse("1.2.3.4", """{"svc":"livecompanion","proto":1,"name":"PC","ws":9010,"helper":9012,"set":"Song"}""")!!
        assertEquals("PC", h.name); assertEquals("Song", h.set)
        assertNull(Beacon.parse("1.2.3.4", """{"svc":"other"}"""))
        assertNull(Beacon.parse("1.2.3.4", "garbage"))
    }

    @Test fun position() {
        assertEquals("1.1.1", formatPosition(0.0, 4, 4))
        assertEquals("2.1.1", formatPosition(4.0, 4, 4))
        assertEquals("1.3.3", formatPosition(2.5, 4, 4))
        assertEquals("2.1.1", formatPosition(3.0, 3, 4))
    }

    @Test fun contrast() {
        assertEquals(androidx.compose.ui.graphics.Color.Black, onColor(androidx.compose.ui.graphics.Color.White))
        assertEquals(androidx.compose.ui.graphics.Color.White, onColor(androidx.compose.ui.graphics.Color(0xFF202020)))
    }

    @Test fun versions() {
        assertTrue(Version.parse("1.2.0")!! > Version.parse("1.1.9")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("1.0.0-test.4")!!)
        assertEquals(Version.parse("1.2"), Version.parse("v1.2.0").let { Version(listOf(1, 2), null) })
        assertEquals("1.0.0-test.7", Version.findIn("LiveCompanion test build 1.0.0-test.7")!!.toString())
    }

    @Test fun releaseParsing() {
        val body = """[
          {"tag_name":"v0.2.0","name":"0.2.0","prerelease":false,"draft":false,"assets":[{"name":"LiveCompanion-0.2.0.apk","browser_download_url":"http://x/a.apk","size":10}]},
          {"tag_name":"test","name":"Test build 0.3.0-test.1","prerelease":true,"assets":[{"name":"LiveCompanion-0.3.0-test.1.apk","browser_download_url":"http://x/t.apk","size":5}]},
          {"tag_name":"weird","name":"nope"}]"""
        val stable = UpdateCheck.parseReleases(body, UpdateCheck.Channel.STABLE)
        assertEquals(1, stable.size)
        val all = UpdateCheck.parseReleases(body, UpdateCheck.Channel.TEST)
        assertEquals("0.3.0-test.1", UpdateCheck.newestOf(all)!!.version.toString())
        assertNotNull(UpdateCheck.pickAsset(stable[0]))
    }

    @Test fun jsonArgsBuilds() {
        val a = jsonArgs("tr" to "t0", "i" to 3, "on" to true, "v" to 0.5f, "x" to null)
        assertEquals("t0", a["tr"]!!.jsonPrimitive.content)
        assertEquals("3", a["i"]!!.jsonPrimitive.content)
    }
}

/** Real WebSocket round-trip against a scripted MockWebServer. */
class LiveClientTest {
    @Test fun requestResponseAndResync() = runBlocking {
        val server = MockWebServer()
        val received = CopyOnWriteArrayList<String>()
        val connects = java.util.concurrent.atomic.AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connects.incrementAndGet()
                webSocket.send("""{"t":"hello","proto":1,"live":"12.4.1","host":"mock"}""")
                webSocket.send("""{"t":"sec","s":"focus","d":{"ref":"t1","name":"Bass"}}""")
                webSocket.send("""{"t":"sec","s":"session","d":{"tempo":99}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val o = LiveJson.parseToJsonElement(text).jsonObject
                when (o["t"]!!.jsonPrimitive.content) {
                    "ping" -> webSocket.send("""{"t":"pong"}""")
                    "req" -> {
                        val id = o["id"]!!.jsonPrimitive.content
                        if (o["op"]!!.jsonPrimitive.content == "bad") webSocket.send("""{"t":"res","id":$id,"ok":false,"err":"unsupported: bad"}""")
                        else webSocket.send("""{"t":"res","id":$id,"ok":true,"d":{"echo":true}}""")
                        if (o["op"]!!.jsonPrimitive.content == "drop") webSocket.close(1000, "bye")
                    }
                }
            }
        }))
        // second connection after a drop
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connects.incrementAndGet()
                webSocket.send("""{"t":"hello","proto":1,"live":"12.4.1","host":"mock"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { received += text }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LiveStore()
        val client = LiveClient(store, scope)
        try {
            client.connect(server.hostName, server.port)
            withTimeout(5000) { client.state.first { it.status == ConnStatus.CONNECTED } }
            withTimeout(2000) { store.session.first { it.tempo == 99f } }
            val d = client.request("sys.info")
            assertTrue(d.jsonObject["echo"]!!.jsonPrimitive.content.toBoolean())
            try { client.request("bad"); throw AssertionError("expected failure") } catch (e: com.livecompanion.net.LiveException) {
                assertEquals("unsupported: bad", e.message)
            }
            // server closes -> client reconnects and restores focus
            client.op("drop")
            withTimeout(8000) { while (connects.get() < 2) delay(50) }
            withTimeout(5000) { while (received.none { it.contains("focus.set") }) delay(50) }
            assertTrue(received.any { it.contains("\"focus.set\"") && it.contains("t1") })
        } finally {
            client.disconnect()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            server.shutdown()
        }
    }
}
