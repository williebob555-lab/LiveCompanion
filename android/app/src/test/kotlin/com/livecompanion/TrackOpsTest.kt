package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ActivityScenario
import com.livecompanion.net.LiveClient
import com.livecompanion.ui.screens.TracksScreen
import com.livecompanion.ui.theme.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fake client that records every op the UI sends, to verify the exact wire ops (docs/PROTOCOL.md). */
class CapturingClient(store: com.livecompanion.state.LiveStore) :
    LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
    val ops = java.util.concurrent.CopyOnWriteArrayList<Pair<String, JsonObject>>()
    override suspend fun request(op: String, args: JsonObject, timeoutMs: Long): JsonElement {
        ops.add(op to args); return JsonNull
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h900dp-port-xhdpi")
class TrackOpsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    @After fun tearDown() { scenario?.close() }

    private fun setup(): CapturingClient {
        val store = fakeStore()
        val c = CapturingClient(store)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { it.setContent { LiveTheme { TracksScreen(store, c, Modifier) } } }
        rule.waitForIdle()
        return c
    }

    private fun CapturingClient.find(op: String) = ops.firstOrNull { it.first == op }?.second

    @Test fun addButtonsSendTrackCreate() {
        val c = setup()
        rule.onNodeWithText("+ Audio").performClick()
        rule.onNodeWithText("+ MIDI").performClick()
        rule.onNodeWithText("+ Return").performClick()
        val creates = c.ops.filter { it.first == "track.create" }.map { it.second["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("audio", "midi", "return"), creates)
    }

    @Test fun renameSendsTrackSetName() {
        val c = setup()
        rule.onNodeWithText("1 Drums").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Kick")
        rule.onNodeWithText("OK").performClick()
        val a = c.find("track.set")!!
        assertEquals("t0", a["tr"]!!.jsonPrimitive.content)
        assertEquals("name", a["k"]!!.jsonPrimitive.content)
        assertEquals("Kick", a["v"]!!.jsonPrimitive.content)
    }

    @Test fun duplicateAndDeleteSendOps() {
        val c = setup()
        rule.onAllNodesWithText("Edit ▾").onFirst().performClick()
        rule.onNodeWithText("Duplicate").performClick()
        assertEquals("t0", c.find("track.duplicate")!!["tr"]!!.jsonPrimitive.content)
        rule.onAllNodesWithText("Edit ▾").onFirst().performClick()
        rule.onNodeWithText("Delete").performClick()
        assertTrue(c.find("track.delete") == null) // needs the confirmation first
        rule.onAllNodesWithText("Delete").get(1).performClick()
        assertEquals("t0", c.find("track.delete")!!["tr"]!!.jsonPrimitive.content)
    }

    @Test fun muteToggleSendsTrackSet() {
        val c = setup()
        rule.onAllNodesWithText("M").onFirst().performClick()
        val a = c.find("track.set")!!
        assertEquals("mute", a["k"]!!.jsonPrimitive.contentOrNull)
    }
}
