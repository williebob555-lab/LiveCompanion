package com.livecompanion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.livecompanion.net.DiscoveredHost
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.HostTarget
import com.livecompanion.net.LiveClient
import com.livecompanion.ui.screens.ConnectScreen
import com.livecompanion.ui.screens.MainShell
import com.livecompanion.ui.screens.Tab
import com.livecompanion.ui.theme.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot matrix (docs/UI.md hard layout rules): 5 sizes x portrait/landscape x fontScale 1.0/1.3,
 * for Connect and Shell+Session. Images land in build/outputs/roborazzi when run via
 * `gradlew recordRoborazziDebug`; a plain `testDebugUnitTest` only checks that composition works.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest(private val wDp: Int, private val hDp: Int, private val land: Boolean, private val fontScale: Float) {

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

    @Test fun connect() {
        launch {
            ConnectScreen(
                discovered = listOf(
                    DiscoveredHost("192.168.1.20", "STUDIO-PC", 9010, 9012, "My Song Project"),
                    DiscoveredHost("192.168.1.31", "A-very-long-hostname-for-a-laptop-in-the-wine-prefix", 9010),
                ),
                recents = listOf(HostTarget("10.0.2.2", 9010), HostTarget("192.168.1.20", 9010)),
                onConnect = {},
            )
        }
        rule.onRoot().captureRoboImage(name("connect"))
    }

    @Test fun shellSession() {
        val store = fakeStore()
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        launch { MainShell(store, client, Modifier.fillMaxSize(), initialTab = Tab.Session, statusOverride = ConnStatus.CONNECTED) }
        rule.onRoot().captureRoboImage(name("shell_session"))
    }

    @Test fun shellTracks() {
        // Tracks tab; landscape variants simulate a left display cutout so the read-only info strip renders.
        val store = fakeStore()
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        launch {
            MainShell(store, client, Modifier.fillMaxSize(), initialTab = Tab.Tracks, statusOverride = ConnStatus.CONNECTED,
                cutoutOverride = 44.dp to 0.dp)
        }
        rule.onRoot().captureRoboImage(name("shell_tracks"))
    }

    @Test fun shellArrangeDefault() {
        val store = fakeStore()
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        launch { MainShell(store, client, Modifier.fillMaxSize(), statusOverride = ConnStatus.CONNECTED, cutoutOverride = 0.dp to 44.dp) }
        rule.onRoot().captureRoboImage(name("shell_arrange"))
    }

    @Test fun shellDisconnected() {
        // Idle client = not connected: top bar replaced in place by the status strip, body dimmed.
        val store = fakeStore()
        val client = LiveClient(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        launch { MainShell(store, client, Modifier.fillMaxSize(), initialTab = Tab.Session) }
        rule.onRoot().captureRoboImage(name("shell_disconnected"))
    }

    @Test fun connectConnecting() {
        launch {
            ConnectScreen(
                discovered = emptyList(), recents = listOf(HostTarget("10.0.2.2", 9010)), onConnect = {},
                connecting = HostTarget("10.0.2.2", 9099), initialManual = "10.0.2.2:99999",
            )
        }
        rule.onRoot().captureRoboImage(name("connect_connecting"))
    }

    @Test fun connectError() {
        launch {
            ConnectScreen(
                discovered = emptyList(), recents = emptyList(), onConnect = {},
                error = "Could not connect to 10.0.2.2:9099: connection refused (is Live running with the LiveCompanion control surface?)",
                initialManual = "host:abc",
            )
        }
        rule.onRoot().captureRoboImage(name("connect_error"))
    }
}
