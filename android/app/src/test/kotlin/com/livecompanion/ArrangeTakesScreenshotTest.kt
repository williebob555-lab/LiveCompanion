package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.screens.ArrangeScreen
import com.livecompanion.ui.screens.TakesScreen
import com.livecompanion.ui.theme.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Arrange + Takes screens across the docs/UI.md size/orientation/font matrix. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArrangeTakesScreenshotTest(private val wDp: Int, private val hDp: Int, private val land: Boolean, private val fontScale: Float) {

    companion object {
        private val sizes = listOf(320 to 568, 360 to 800, 412 to 915, 600 to 960, 840 to 1280)

        @JvmStatic
        @Parameters(name = "{0}x{1} land={2} font={3}")
        fun params(): List<Array<Any>> = buildList {
            for ((w, h) in sizes) for (land in listOf(false, true)) for (f in listOf(1.0f, 1.3f)) add(arrayOf(w, h, land, f))
        }
    }

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After fun tearDown() { scenario?.close() }

    private fun name(screen: String): String {
        val orient = if (land) "land" else "port"
        val w = if (land) hDp else wDp
        val h = if (land) wDp else hDp
        return "build/outputs/roborazzi/${screen}_${w}x${h}_${orient}_f${(fontScale * 10).toInt()}.png"
    }

    private fun launch(content: @androidx.compose.runtime.Composable () -> Unit) {
        val w = if (land) hDp else wDp
        val h = if (land) wDp else hDp
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if (land) "land" else "port"}-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { it.setContent { LiveTheme { content() } } }
        rule.waitForIdle()
    }

    private fun store(supported: Boolean): LiveStore {
        val s = fakeStore()
        fun sec(n: String, j: String) = s.handleSection(n, LiveJson.parseToJsonElement(j))
        sec("session", """{"tempo":120,"sig":[4,4],"playing":true,"pos":37.5,"rec":false,"overdub":true,"loop":true,
            "loop_start":16,"loop_len":16,"punch_in":true,"sel_track":"t1",
            "cues":[{"t":0,"n":"Intro"},{"t":32,"n":"Drop"}]}""")
        sec("focus", """{"ref":"t1","name":"1 Bass","kind":"midi","armable":true,"arm":true}""")
        sec("arr", """{"tr":"t1","clips":[
            {"i":0,"name":"Intro bass","color":3452671,"start":0,"end":16,"audio":false,"looping":true,"start_marker":0,"end_marker":4,"loop_start":0,"loop_end":4,
              "pn":[[0,0.5,36],[0.5,0.5,36],[1,0.5,43],[2,1,39],[3,0.5,41],[3.5,0.25,48]]},
            {"i":1,"name":"Verse with a very long name","color":16724787,"start":16,"end":32,"audio":true,"muted":true,"file_path":"/x/verse.wav","warping":true,"warp_first":0,"warp_last":16,"end_marker":16},
            {"i":2,"name":"Fill","color":16766720,"start":32,"end":36,"pn":[[0,1,60],[1,1,64],[2,1,67]],"end_marker":4},
            {"i":3,"name":"Drop","color":3452671,"start":40,"end":72,"audio":true,"file_path":"/x/verse.wav","warping":true,"warp_first":0,"warp_last":32,"end_marker":32}]}""")
        com.livecompanion.net.PeaksClient.shared.put("/x/verse.wav", 1024, wavePeaks())
        if (supported) {
            sec("takes", """{"tr":"t1","supported":true,"lanes":[
                {"i":0,"name":"Take lane 1","clips":[{"i":0,"name":"Take 1","color":3452671,"start":0,"end":16},{"i":1,"name":"Take 2","color":3452671,"start":16,"end":32}]},
                {"i":1,"name":"Lane 2 with a long name","clips":[{"i":0,"name":"Take 3 long name here","color":16724787,"start":4,"end":28}]},
                {"i":2,"name":"","clips":[]}]}""")
        } else {
            sec("takes", """{"tr":"t1","supported":false,"lanes":[]}""")
        }
        return s
    }

    private fun wavePeaks(): com.livecompanion.net.Peaks {
        val n = 1024
        val hi = FloatArray(n) { i -> (0.15f + 0.8f * kotlin.math.abs(kotlin.math.sin(i * 0.05f)) * (0.5f + 0.5f * kotlin.math.sin(i * 0.31f).let { it * it })).coerceAtMost(1f) }
        val lo = FloatArray(n) { i -> -hi[i] * 0.9f }
        return com.livecompanion.net.Peaks(44100, 2, 44100L * 16, n, lo, hi)
    }

    private fun client(s: LiveStore) = LiveClient(s, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun arrange() {
        val s = store(true)
        val c = client(s)
        s.handleRt(LiveJson.parseToJsonElement("""{"pos":37.5,"playing":false}"""))
        launch { ArrangeScreen(s, c, Modifier.fillMaxSize()) }
        rule.onRoot().captureRoboImage(name("arrange"))
    }

    @Test fun arrangeSelectedWithTakes() {
        val s = store(true)
        s.handleRt(LiveJson.parseToJsonElement("""{"pos":20,"playing":false}"""))
        val c = client(s)
        launch { ArrangeScreen(s, c, Modifier.fillMaxSize(), initialSel = 0, initialShowTakes = true) }
        rule.onRoot().captureRoboImage(name("arrange_sel_takes"))
    }

    @Test fun arrangeRecording() {
        val s = store(true)
        s.handleSection("session", LiveJson.parseToJsonElement("""{"tempo":120,"sig":[4,4],"playing":true,"pos":40,"rec":true,"loop":false,"loop_start":0,"loop_len":16}"""))
        s.handleRt(LiveJson.parseToJsonElement("""{"pos":40,"playing":true}"""))
        val c = client(s)
        launch { ArrangeScreen(s, c, Modifier.fillMaxSize()) }
        rule.waitForIdle()
        s.handleRt(LiveJson.parseToJsonElement("""{"pos":45,"playing":true}"""))
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(name("arrange_rec"))
    }

    @Test fun takesSupported() {
        val s = store(true)
        val c = client(s)
        val n = "2 Bass with a really long name that must ellipsize"
        RuntimeEnvironment.getApplication().getSharedPreferences("take_ratings", 0).edit()
            .putInt("$n|0|0.000", 2).putInt("$n|0|16.000", 3).putInt("$n|1|4.000", 1).apply()
        launch { TakesScreen(s, c, Modifier.fillMaxSize()) }
        rule.onRoot().captureRoboImage(name("takes"))
    }

    @Test fun takesUnsupported() {
        val s = store(false)
        val c = client(s)
        launch { TakesScreen(s, c, Modifier.fillMaxSize()) }
        rule.onRoot().captureRoboImage(name("takes_slots"))
    }
}
