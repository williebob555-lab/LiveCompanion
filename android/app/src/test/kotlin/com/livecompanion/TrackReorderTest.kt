package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.livecompanion.net.LiveClient
import com.livecompanion.net.LiveException
import com.livecompanion.ui.screens.TracksScreen
import com.livecompanion.ui.theme.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** Scripted client: move_check answers with [losses]; every op is recorded. */
private class ReorderClient(store: com.livecompanion.state.LiveStore, val losses: List<String>) :
    LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
    val ops = java.util.concurrent.CopyOnWriteArrayList<Pair<String, JsonObject>>()
    override suspend fun request(op: String, args: JsonObject, timeoutMs: Long): JsonElement {
        ops.add(op to args)
        return when (op) {
            "track.move_check" -> buildJsonObject {
                put("ok_to_move", JsonPrimitive(losses.isEmpty()))
                put("losses", JsonArray(losses.map { JsonPrimitive(it) }))
            }
            "track.move" -> if (losses.isNotEmpty() && args["force"]?.jsonPrimitive?.contentOrNull != "true")
                throw LiveException("needs_confirm") else JsonNull
            else -> JsonNull
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w640dp-h360dp-land-xhdpi")
class TrackReorderTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    @After fun tearDown() { scenario?.close() }

    private fun setup(losses: List<String>): ReorderClient {
        val store = fakeStore()
        val c = ReorderClient(store, losses)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { it.setContent { LiveTheme { TracksScreen(store, c, Modifier) } } }
        rule.waitForIdle()
        return c
    }

    @Test fun holdDragShowsLiftedRowAndDropChecksThenMoves() {
        val c = setup(emptyList())
        rule.onNodeWithTag("grip_t0", useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(450)
        rule.onNodeWithTag("grip_t0", useUnmergedTree = true).performTouchInput { moveBy(Offset(0f, 150f)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/tracks_drag_state.png")
        rule.onNodeWithTag("grip_t0", useUnmergedTree = true).performTouchInput { up() }
        rule.waitForIdle()
        val chk = c.ops.firstOrNull { it.first == "track.move_check" }
        assertEquals("t0", chk!!.second["tr"]!!.jsonPrimitive.content)
        val mv = c.ops.first { it.first == "track.move" }.second
        assertTrue(mv["to"]!!.jsonPrimitive.content.toInt() > 0)
    }

    @Test fun lossesShowOneConfirmDialogAndForceOnConfirm() {
        val c = setup(listOf("arrangement automation", "frozen"))
        rule.onNodeWithText("▼").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Move anyway").assertExists()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/tracks_move_confirm.png")
        assertTrue(c.ops.none { it.first == "track.move" })       // nothing sent before the OK
        rule.onNodeWithText("Move anyway").performClick()
        rule.waitForIdle()
        assertEquals("true", c.ops.last { it.first == "track.move" }.second["force"]!!.jsonPrimitive.content)
    }

    @Test fun cancelRevertsAndSendsNothing() {
        val c = setup(listOf("frozen"))
        rule.onNodeWithText("▼").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(c.ops.none { it.first == "track.move" })
    }
}
