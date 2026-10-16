package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.livecompanion.net.LiveClient
import com.livecompanion.net.Peaks
import com.livecompanion.net.PeaksClient
import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.screens.MainShell
import com.livecompanion.ui.screens.Tab
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Clip tab (MIDI piano roll, audio waveform, picker) across the same size/orientation/font matrix as ScreenshotTest. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ClipScreenshotTest(private val wDp: Int, private val hDp: Int, private val land: Boolean, private val fontScale: Float) {

    companion object {
        private val sizes = listOf(320 to 568, 360 to 800, 412 to 915, 600 to 960, 840 to 1280)

        @JvmStatic
        @Parameters(name = "{0}x{1} land={2} font={3}")
        fun params(): List<Array<Any>> = buildList {
            for ((w, h) in sizes) for (land in listOf(false, true)) for (f in listOf(1.0f, 1.3f)) add(arrayOf(w, h, land, f))
        }

        const val AUDIO_PATH = "C:/Samples/loop.wav"
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

    private fun shell(store: LiveStore, shot: String, corners: com.livecompanion.ui.screens.Corners? = null) {
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val w = if (land) hDp else wDp
        val h = if (land) wDp else hDp
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if (land) "land" else "port"}-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { it.setContent { androidx.compose.runtime.CompositionLocalProvider(com.livecompanion.ui.clip.LocalClipAnimate provides false) { LiveTheme { MainShell(store, client, Modifier.fillMaxSize(), initialTab = Tab.Clip, statusOverride = com.livecompanion.net.ConnStatus.CONNECTED, cornersOverride = corners) } } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(name(shot))
    }

    private fun midiStore(rec: Boolean = false, sig: String = "[4,4]", dense: Boolean = false): LiveStore {
        val s = fakeStore()
        val notes = buildString {
            var id = 1
            for (bar in 0 until 4) for (i in 0 until 16) {
                if (i % 3 == 2) continue
                val p = 48 + ((i * 5 + bar * 7) % 24)
                if (id > 1) append(',')
                append("""{"id":${id++},"p":$p,"t":${bar * 4 + i * 0.25},"d":0.25,"v":${60 + (i * 7) % 60},"prob":${if (i % 5 == 0) 0.5 else 1.0}}""")
            }
            append(""",{"id":900,"p":36,"t":0,"d":4,"v":110},{"id":901,"p":84,"t":8,"d":2.5,"v":90}""")
            if (dense) for (k in 0 until 2000) append(""",{"id":${1000 + k},"p":${30 + (k * 7) % 60},"t":${(k % 512) * 0.125},"d":0.125,"v":${40 + k % 80}}""")
        }
        s.handleSection("clip", LiveJson.parseToJsonElement("""{"loc":{"w":"slot","tr":"t1","i":0},"name":"Bass A","color":3452671,"audio":false,"len":16,
            "looping":true,"loop_start":0,"loop_end":16,"start_marker":0,"end_marker":16,"signature":$sig,"launch_mode":0,"launch_quant":0,
            "legato":false,"muted":false,"rec":$rec,"notes":[$notes]}"""))
        s.handleSection("session", LiveJson.parseToJsonElement("""{"tempo":122.5,"sig":[4,4],"playing":true,"pos":37.5,"overdub":true,"loop":true,"metronome":true,"can_undo":true,"sel_track":"t1","sel_scene":2,"scale":{"root":0,"name":"Minor","intervals":[0,2,3,5,7,8,10]}}"""))
        s.handleRt(LiveJson.parseToJsonElement("""{"pos":3,"playing":true,"clip_pos":${if (rec) 9.5 else 5.5}}"""))
        return s
    }

    private fun audioStore(rec: Boolean = false): LiveStore {
        val n = 4096
        val mx = FloatArray(n) { i -> abs(sin(i * 2 * PI / 128)).toFloat() * (0.3f + 0.7f * ((i / 256) % 2)) }
        val pk = Peaks(44100, 2, 44100L * 8, n, FloatArray(n) { -mx[it] * 0.9f }, mx)
        var b = 256
        while (b <= 8192) { PeaksClient.shared.put(AUDIO_PATH, b, Peaks(pk.sr, 2, pk.frames, n, pk.min, pk.max)); b *= 2 }
        val s = fakeStore()
        s.handleSection("clip", LiveJson.parseToJsonElement("""{"loc":{"w":"slot","tr":"t0","i":1},"name":"Drum Loop","color":16724787,"audio":true,"len":16,
            "looping":true,"loop_start":0,"loop_end":8,"start_marker":0,"end_marker":8,"signature":[4,4],"launch_mode":0,"launch_quant":0,"rec":$rec,
            "gain":0.4,"gain_str":"-6.0 dB","pitch_coarse":0,"pitch_fine":0.0,"warping":true,"warp_mode":"Beats","warp_modes":["Beats","Tones","Texture","Re-Pitch","Complex","Complex Pro"],
            "file_path":"C:/Samples/loop.wav","sample_rate":44100.0,"sample_len":352800.0,
            "warp_markers":[{"beat":0,"sample_time":0},{"beat":4,"sample_time":2},{"beat":8,"sample_time":4.5}]}"""))
        s.handleRt(LiveJson.parseToJsonElement("""{"pos":3,"playing":true,"clip_pos":${if (rec) 6.5 else 2.5}}"""))
        return s
    }

    @Test fun midi() = shell(midiStore(), "clip_midi")
    @Test fun audio() = shell(audioStore(), "clip_audio")
    @Test fun midiRecording() = shell(midiStore(rec = true), "clip_midi_rec")
    @Test fun audioRecording() = shell(audioStore(rec = true), "clip_audio_rec")
    @Test fun midiDense68() = shell(midiStore(sig = "[6,8]", dense = true), "clip_midi_dense_68")
    @Test fun picker() = shell(fakeStore(), "clip_picker")
    // Rounded display corners (48dp): the bottom handle row and the velocity lane keep clear of the arcs.
    @Test fun midiCorners() = shell(midiStore(), "clip_midi_corners", com.livecompanion.ui.screens.Corners(48.dp, 48.dp, 48.dp, 48.dp))
    @Test fun audioCorners() = shell(audioStore(), "clip_audio_corners", com.livecompanion.ui.screens.Corners(48.dp, 48.dp, 48.dp, 48.dp))
}
