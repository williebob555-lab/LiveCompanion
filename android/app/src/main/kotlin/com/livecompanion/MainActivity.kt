package com.livecompanion

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.livecompanion.ui.components.ConfirmDialog
import androidx.compose.ui.Modifier
import com.livecompanion.net.ConnStatus
import com.livecompanion.ui.screens.AppActions
import com.livecompanion.ui.screens.ConnectScreen
import com.livecompanion.ui.screens.LocalAppActions
import com.livecompanion.ui.screens.MainShell
import com.livecompanion.ui.theme.LiveTheme

/** Applies the orientation setting: "land" (default), "port" or "auto". */
fun applyOrientation(activity: Activity, mode: String) {
    activity.requestedOrientation = when (mode) {
        "port" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation(this, vm.prefs.orientation)
        enableEdgeToEdge()
        // Immersive fullscreen (swipe from an edge reveals the bars transiently), screen stays on, content may extend
        // into the display cutout on the short edges (landscape: the cutout side shows the read-only info strip).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        setContent {
            LiveTheme {
                val active by vm.active.collectAsState()
                val conn by vm.client.state.collectAsState()
                val actions = remember { AppActions(disconnect = vm::disconnect, prefs = vm.prefs) }
                CompositionLocalProvider(LocalAppActions provides actions) {
                    if (active == null) {
                        val discovered by vm.discovered.collectAsState()
                        val recents by vm.recents.collectAsState()
                        ConnectScreen(
                            discovered = discovered,
                            recents = recents,
                            onConnect = vm::connect,
                            onForgetRecent = vm::forgetRecent,
                            modifier = Modifier.safeDrawingPadding(),
                            error = conn.lastError?.takeIf { conn.status == ConnStatus.IDLE },
                            connecting = conn.target?.takeIf { conn.status == ConnStatus.CONNECTING },
                            onCancel = vm::disconnect,
                        )
                    } else {
                        var confirmBack by remember { mutableStateOf(false) }
                        BackHandler { confirmBack = true }
                        if (confirmBack) {
                            ConfirmDialog("Disconnect", "Disconnect from Live and return to the host list?", "Disconnect",
                                { confirmBack = false; vm.disconnect() }, { confirmBack = false })
                        }
                        MainShell(vm.store, vm.client)
                    }
                }
            }
        }
    }
}
