package com.livecompanion

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation(this, vm.prefs.orientation)
        enableEdgeToEdge()
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
                        )
                    } else {
                        BackHandler { vm.disconnect() }
                        MainShell(vm.store, vm.client, Modifier.safeDrawingPadding())
                    }
                }
            }
        }
    }
}
