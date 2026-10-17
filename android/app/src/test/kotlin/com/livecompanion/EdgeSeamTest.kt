package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.LiveClient
import com.livecompanion.ui.screens.Corners
import com.livecompanion.ui.screens.MainShell
import com.livecompanion.ui.screens.Tab
import com.livecompanion.ui.theme.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner rule (2026-10-16): corner / cutout / status-bar inset areas must NOT be a different colour - every surface
 * extends seamlessly to the screen edge and only its content is inset. This renders the shell with simulated rounded
 * corners, a top inset (portrait) or a side cutout (landscape, both sides) and samples a 6px band along the screen
 * edges: wherever a chrome surface (top bar, tab row, rail, info strip) sits at the edge, the band must be that
 * surface's colour, never the app background. 1-4px hairline separators between bars are tolerated.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class EdgeSeamTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    @After fun tearDown() { scenario?.close() }

    private val BG = 0xFF1A1A1A.toInt()
    private val BAND = 6

    private fun render(land: Boolean, cut: Pair<Dp, Dp>?, topIns: Dp, tag: String): android.graphics.Bitmap {
        val store = fakeStore()
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val (w, h) = if (land) 915 to 412 else 412 to 915
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if (land) "land" else "port"}-xhdpi")
        RuntimeEnvironment.setFontScale(1.0f)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val c = 40.dp
        scenario!!.onActivity {
            it.setContent {
                LiveTheme {
                    MainShell(store, client, Modifier.fillMaxSize(), initialTab = Tab.Session, statusOverride = ConnStatus.CONNECTED,
                        cutoutOverride = cut, cornersOverride = Corners(c, c, c, c), topInsetOverride = topIns, bottomInsetOverride = 0.dp)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/edge_seam_$tag.png")
        return rule.onRoot().captureToImage().asAndroidBitmap()
    }

    /** Fails when the BAND px next to the edge is app background along [range] (runs of <= 4px are separators). */
    private fun assertNoBg(bmp: android.graphics.Bitmap, edge: String, range: IntRange, what: String) {
        val bad = mutableListOf<String>()
        for (off in 0 until BAND) {
            var run = 0
            var first = -1
            for (p in range) {
                val x: Int; val y: Int
                when (edge) {
                    "top" -> { x = p; y = off }
                    "bottom" -> { x = p; y = bmp.height - 1 - off }
                    "left" -> { x = off; y = p }
                    else -> { x = bmp.width - 1 - off; y = p }
                }
                if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
                if (bmp.getPixel(x, y) == BG) { if (run == 0) first = p; run++ } else {
                    if (run > 4) bad += "$edge off=$off [$first..${p - 1}]"
                    run = 0
                }
            }
            if (run > 4) bad += "$edge off=$off [$first..${range.last}]"
        }
        assertTrue("$what: app-background colour in the $edge edge band: ${bad.take(4)}", bad.isEmpty())
    }

    private fun dp(v: Int) = v * 2   // xhdpi

    @Test fun portrait() {
        val b = render(false, null, 40.dp, "port")
        assertNoBg(b, "top", 0 until b.width, "portrait")
        // top inset + bars + tab row run to both side edges
        assertNoBg(b, "left", 0 until dp(40 + 80), "portrait")
        assertNoBg(b, "right", 0 until dp(40 + 80), "portrait")
    }

    @Test fun landscapeCutoutLeft() {
        val b = render(true, 44.dp to 0.dp, 0.dp, "land_left")
        assertNoBg(b, "top", 0 until dp(44 + 52), "land-left")     // (top-bar buttons legitimately touch y=0 elsewhere)
        assertNoBg(b, "left", 0 until b.height, "land-left")      // strip + rail
        assertNoBg(b, "right", 0 until dp(36), "land-left")       // top bar to the right edge
        assertNoBg(b, "bottom", 0 until dp(44 + 52), "land-left")
    }

    @Test fun landscapeCutoutRight() {
        val b = render(true, 0.dp to 44.dp, 0.dp, "land_right")
        assertNoBg(b, "top", 0 until dp(52), "land-right")
        assertNoBg(b, "top", (b.width - dp(44)) until b.width, "land-right")
        assertNoBg(b, "left", 0 until b.height, "land-right")     // rail
        assertNoBg(b, "right", 0 until b.height, "land-right")    // strip
        assertNoBg(b, "bottom", 0 until dp(52), "land-right")
        assertNoBg(b, "bottom", (b.width - dp(44)) until b.width, "land-right")
    }
}
