package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.screens.BrowserItem
import com.livecompanion.ui.screens.BrowserScreen
import com.livecompanion.ui.screens.DevicesScreen
import com.livecompanion.ui.screens.MixScreen
import com.livecompanion.ui.screens.seedBrowserForTest
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

/** Devices / Browser / Mix screenshot matrix (same sizes as ScreenshotTest). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DevicesBrowserMixScreenshotTest(private val wDp: Int, private val hDp: Int, private val land: Boolean, private val fontScale: Float) {

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
        val w = if (land) hDp else wDp
        val h = if (land) wDp else hDp
        return "build/outputs/roborazzi/${screen}_${w}x${h}_${if (land) "land" else "port"}_f${(fontScale * 10).toInt()}.png"
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

    private fun env(): Pair<LiveStore, LiveClient> {
        val store = fakeStore()
        fun sec(n: String, j: String) = store.handleSection(n, LiveJson.parseToJsonElement(j))
        sec("focus", """{"ref":"t1","name":"2 Bass with a really long name that must ellipsize","color":3452671,"kind":"midi","armable":true,"arm":true,"mute":false,"solo":true,"monitor":0,
            "vol":{"v":0.7,"min":0,"max":1,"str":"-3.2 dB"},"pan":{"v":-0.25,"min":-1,"max":1,"str":"25L"},
            "sends":[{"name":"A","v":0.4,"min":0,"max":1,"str":"-12 dB"},{"name":"B","v":0.0,"min":0,"max":1,"str":"-inf dB"}],
            "in_type":"Ext. In","in_types":["Ext. In","Resampling","No Input"],"in_ch":"1/2","in_chs":["1","2","1/2","3/4"],
            "out_type":"Master","out_types":["Master","Ext. Out","Sends Only"]}""")
        sec("tracks", """{"tracks":[{"ref":"t0","name":"1 Drums","color":16724787,"kind":"audio"},{"ref":"t1","name":"2 Bass","color":3452671,"kind":"midi","armable":true}],
            "returns":[{"ref":"r0","name":"A Reverb","color":8421504,"kind":"return"},{"ref":"r1","name":"B Delay with a long name here","color":8421504,"kind":"return","mute":true}],
            "master":{"ref":"m","name":"Master","color":8421504,"kind":"master"}}""")
        sec("devices", """{"tr":"t1","devices":[
          {"path":"0","name":"Operator","cls":"Operator","on":true,"type":"instrument"},
          {"path":"1","name":"Instrument Rack with a long name","cls":"InstrumentGroupDevice","on":true,"type":"rack",
            "macros":[{"i":0,"name":"Cutoff","v":64,"min":0,"max":127,"str":"64"},{"i":1,"name":"Res","v":20,"min":0,"max":127,"str":"20"},{"i":2,"name":"Drive","v":100,"min":0,"max":127,"str":"100"},{"i":3,"name":"Attack","v":0,"min":0,"max":127,"str":"0"},{"i":4,"name":"Release","v":50,"min":0,"max":127,"str":"50"}],
            "var":{"count":4,"sel":1},
            "chains":[{"i":0,"name":"Saw Pad","devices":[{"path":"1.c0.0","name":"Wavetable","cls":"InstrumentVector","on":true,"type":"instrument"},{"path":"1.c0.1","name":"Auto Filter","cls":"AutoFilter","on":false,"type":"audio_fx"}]},
                      {"i":1,"name":"Sub","devices":[]}]},
          {"path":"2","name":"EQ Eight","cls":"Eq8","on":false,"type":"audio_fx"},
          {"path":"3","name":"Limiter","cls":"Limiter","on":true,"type":"audio_fx"}]}""")
        sec("params", """{"tr":"t1","path":"0","name":"Operator","params":[
          {"i":0,"name":"Device On","v":1,"min":0,"max":1,"str":"On","quant":true,"items":["Off","On"]},
          {"i":1,"name":"Algorithm","v":3,"min":0,"max":10,"str":"4","quant":true,"items":["1","2","3","4","5","6","7","8","9","10","11"]},
          {"i":2,"name":"Filter Freq","v":0.6,"min":0,"max":1,"str":"1.2 kHz"},
          {"i":3,"name":"Filter Res","v":0.2,"min":0,"max":1,"str":"20 %"},
          {"i":4,"name":"Osc A Wave","v":1,"min":0,"max":3,"str":"Saw","quant":true,"items":["Sin","Saw","Sqr","Tri"]},
          {"i":5,"name":"Transpose with a very long parameter name","v":0.5,"min":0,"max":1,"str":"0 st"},
          {"i":6,"name":"Mode","v":0,"min":0,"max":2,"str":"A","quant":true,"items":["A","B","C"]}]}""")
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        return store to client
    }

    @Test fun devices() {
        val (s, c) = env()
        launch { DevicesScreen(s, c, Modifier.fillMaxSize()) }
        rule.onRoot().captureRoboImage(name("devices"))
    }

    @Test fun deviceParams() {
        val (s, c) = env()
        launch { DevicesScreen(s, c, Modifier.fillMaxSize()) }
        rule.onAllNodesWithText("Params")[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(name("device_params"))
    }

    @Test fun browser() {
        val (s, c) = env()
        val roots = listOf("Sounds", "Drums", "Instruments", "Audio Effects", "MIDI Effects", "Max for Live", "Plug-ins", "Clips", "Samples", "Packs", "User Library")
            .map { BrowserItem(it, folder = true) }
        val items = listOf(
            BrowserItem("Bass", folder = true), BrowserItem("Brass", folder = true),
            BrowserItem("Acid Bass.adv", loadable = true), BrowserItem("A Very Long Preset Name For Wobble Bass Deluxe Edition.adg", loadable = true),
            BrowserItem("Sub Bass.adv", loadable = true), BrowserItem("Pad", folder = true, loadable = true),
        )
        seedBrowserForTest(s, roots, listOf("Sounds", "Bass"), items)
        launch { BrowserScreen(s, c, Modifier.fillMaxSize()) }
        rule.onRoot().captureRoboImage(name("browser"))
    }

    @Test fun mix() {
        val (s, c) = env()
        launch { MixScreen(s, c, Modifier.fillMaxSize()) }
        rule.onRoot().captureRoboImage(name("mix"))
    }
}
