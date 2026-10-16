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
import com.livecompanion.net.ConnStatus
import com.livecompanion.ui.screens.BrowserItem
import com.livecompanion.ui.screens.MainShell
import com.livecompanion.ui.screens.Tab
import com.livecompanion.ui.screens.TrackPickerDialog
import com.livecompanion.ui.screens.seedBrowserForTest
import com.livecompanion.ui.theme.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tablet (1280x800dp) tab layouts + the small dedicated track-rename dialog on a small phone in landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TabletScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After fun tearDown() { scenario?.close() }

    private fun launch(w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-land-xhdpi")
        RuntimeEnvironment.setFontScale(1.0f)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { it.setContent { LiveTheme { content() } } }
        rule.waitForIdle()
    }

    private fun shot(tab: Tab, name: String) {
        val store = fakeStore()
        fun sec(n: String, j: String) = store.handleSection(n, com.livecompanion.state.LiveJson.parseToJsonElement(j))
        sec("focus", """{"ref":"t1","name":"2 Bass","color":3452671,"kind":"midi","armable":true,"arm":true,"mute":false,"solo":false,"monitor":0,
            "vol":{"v":0.7,"min":0,"max":1,"str":"-3.2 dB"},"pan":{"v":-0.25,"min":-1,"max":1,"str":"25L"},
            "sends":[{"name":"A","v":0.4,"min":0,"max":1,"str":"-12 dB"},{"name":"B","v":0.0,"min":0,"max":1,"str":"-inf dB"}],
            "in_type":"Ext. In","in_types":["Ext. In","Resampling"],"in_ch":"1/2","in_chs":["1","2","1/2"],"out_type":"Master","out_types":["Master","Sends Only"]}""")
        sec("tracks", """{"tracks":[{"ref":"t1","name":"2 Bass","color":3452671,"kind":"midi","armable":true}],
            "returns":[{"ref":"r0","name":"A Reverb","color":8421504,"kind":"return","vol":{"v":0.6,"min":0,"max":1,"str":"-6 dB"}},{"ref":"r1","name":"B Delay","color":8421504,"kind":"return","vol":{"v":0.8,"min":0,"max":1,"str":"-2 dB"}}],
            "master":{"ref":"m","name":"Master","color":8421504,"kind":"master","vol":{"v":0.85,"min":0,"max":1,"str":"0 dB"}}}""")
        sec("devices", """{"tr":"t1","devices":[
          {"path":"0","name":"Operator","cls":"Operator","on":true,"type":"instrument","pp":[
            {"i":1,"name":"Algorithm","v":3,"min":0,"max":10,"str":"4"},{"i":2,"name":"Filter Freq","v":0.6,"min":0,"max":1,"str":"1.2 kHz"},
            {"i":3,"name":"Filter Res","v":0.2,"min":0,"max":1,"str":"20 %"},{"i":4,"name":"Osc A Wave","v":1,"min":0,"max":3,"str":"Saw"},
            {"i":5,"name":"Transpose","v":0.5,"min":0,"max":1,"str":"0 st"},{"i":6,"name":"Attack","v":0.1,"min":0,"max":1,"str":"2 ms"},
            {"i":7,"name":"Decay","v":0.3,"min":0,"max":1,"str":"20 ms"},{"i":8,"name":"Sustain","v":0.7,"min":0,"max":1,"str":"0 dB"},
            {"i":9,"name":"Release","v":0.2,"min":0,"max":1,"str":"5 ms"},{"i":10,"name":"Volume","v":0.8,"min":0,"max":1,"str":"-3 dB"}]},
          {"path":"1","name":"EQ Eight","cls":"Eq8","on":true,"type":"audio_fx","pp":[{"i":1,"name":"Freq","v":0.3,"min":0,"max":1,"str":"200 Hz"},{"i":2,"name":"Gain","v":0.5,"min":0,"max":1,"str":"0 dB"}]}]}""")
        if (tab == Tab.Browser) {
            val roots = listOf("Sounds", "Drums", "Instruments", "Audio Effects").map { BrowserItem(it, folder = true) }
            seedBrowserForTest(
                store, roots, listOf("Sounds"),
                listOf(BrowserItem("Bass", folder = true), BrowserItem("Brass", folder = true), BrowserItem("Pads", folder = true),
                    BrowserItem("Acid Bass.adv", loadable = true), BrowserItem("Sub Bass.adv", loadable = true), BrowserItem("Wobble.adv", loadable = true)),
            )
        }
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        launch(1280, 800) { MainShell(store, client, Modifier.fillMaxSize(), initialTab = tab, statusOverride = ConnStatus.CONNECTED) }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/tablet_$name.png")
    }

    @Test fun tabletSession() = shot(Tab.Session, "session")
    @Test fun tabletMix() = shot(Tab.Mix, "mix")
    @Test fun tabletDevices() = shot(Tab.Devices, "devices")
    @Test fun tabletBrowser() = shot(Tab.Browser, "browser")
    @Test fun tabletClip() = shot(Tab.Clip, "clip")

    @Test fun pickerRenameDialogSmallLandscape() {
        val store = fakeStore()
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        launch(640, 360) { TrackPickerDialog(store, client, {}) }
        rule.onAllNodesWithText("Edit", substring = true)[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Rename")[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/picker_rename_dialog_640x360.png")
    }
}
