package com.livecompanion.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.livecompanion.net.LiveClient
import com.livecompanion.state.LiveStore
import com.livecompanion.ui.components.LiveDivider
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType
import com.livecompanion.update.AppUpdates
import com.livecompanion.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val v: String) : UpdateUi
    data class NoStable(val testAvailable: Boolean) : UpdateUi
    data object NoReleases : UpdateUi
    data class Available(val release: UpdateCheck.Release, val asset: UpdateCheck.Asset?) : UpdateUi
    data class Downloading(val progress: Float) : UpdateUi
    data class Failed(val msg: String) : UpdateUi
}

/** "More" tab = settings: host info, follow-Live-selection, plus the connection-free [SettingsContent]. */
@Composable
fun MoreScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val actions = LocalAppActions.current
    val conn by client.state.collectAsState()
    val hello by store.hello.collectAsState()
    val session by store.session.collectAsState()
    val demo = client is com.livecompanion.net.DemoClient
    SettingsContent(modifier) {
        Section("Host") {
            Info("Address", conn.target?.label ?: "-")
            Info("Status", conn.status.name.lowercase())
            Info("Ableton Live", if (demo) "Demo (built-in set)" else hello?.live?.ifEmpty { null } ?: "-")
            Info("Host type", hello?.host?.ifEmpty { null } ?: "-")
            LiveTextButton(if (demo) "Leave demo" else "Disconnect", actions.disconnect, Modifier.fillMaxWidth())
        }

        Section("Selection") {
            LiveTextButton(
                if (session.follow) "Follow Live's selected track: ON" else "Follow Live's selected track: OFF",
                {
                    val v = !session.follow
                    actions.prefs?.followLive = v
                    client.followPref = v
                    client.op("focus.follow", "on" to v)
                    store.updateSession { it.copy(follow = v) }
                },
                Modifier.fillMaxWidth(), on = session.follow,
            )
        }

    }
}

/** Full-screen settings for the Connect screen (no Live connection needed): updates, orientation, help. */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(LiveColors.Bg)) {
        Row(
            Modifier.fillMaxWidth().background(LiveColors.Panel).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveTextButton("< Back", onBack, minHeight = 36.dp, minWidth = 64.dp)
            LiveText("Settings & updates", Modifier.weight(1f), LiveType.Body, LiveColors.Accent)
        }
        SettingsContent(Modifier.weight(1f))
    }
}

/**
 * App settings that need no Live connection: updater (internet only), orientation, help. [connected] is inserted
 * after the update section (MoreScreen puts the Host / Follow sections there).
 */
@Composable
fun SettingsContent(modifier: Modifier = Modifier, connected: (@Composable ColumnScope.() -> Unit)? = null) {
    val context = LocalContext.current
    val actions = LocalAppActions.current
    val scope = rememberCoroutineScope()
    val installedIsTest = remember { com.livecompanion.update.Version.parse(AppUpdates.installedVersion(context))?.preRelease != null }
    var test by remember { mutableStateOf(installedIsTest || (actions.prefs?.testChannel ?: false)) }
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    val installed = remember { AppUpdates.installedLabel(context) }
    // Switching channel invalidates an old result ("Up to date" under the other channel would be stale).
    fun pickChannel(testOn: Boolean) {
        test = testOn; actions.prefs?.testChannel = testOn
        if (ui !is UpdateUi.Checking && ui !is UpdateUi.Downloading) ui = UpdateUi.Idle
    }

    val scrollState = rememberScrollState()
    fun startDownload(asset: UpdateCheck.Asset) {
        scope.launch { downloadAndInstall(context, asset) { ui = it } }
    }
    fun startCheck() {
        ui = UpdateUi.Checking
        scope.launch {
            val channel = if (test) UpdateCheck.Channel.TEST else UpdateCheck.Channel.STABLE
            ui = when (val r = withContext(Dispatchers.IO) { AppUpdates.check(context, channel) }) {
                is UpdateCheck.Result.Available -> UpdateUi.Available(r.release, r.download)
                is UpdateCheck.Result.AvailableWithoutDownload -> UpdateUi.Available(r.release, null)
                is UpdateCheck.Result.UpToDate -> UpdateUi.UpToDate(r.installed.toString())
                is UpdateCheck.Result.NoStable -> UpdateUi.NoStable(r.testAvailable)
                is UpdateCheck.Result.NoReleases -> UpdateUi.NoReleases
                is UpdateCheck.Result.Failed -> UpdateUi.Failed(r.message)
            }
        }
    }
    // The update section is the first thing on the page, but a result must never be off-screen.
    LaunchedEffect(ui) { if (ui !is UpdateUi.Idle) scrollState.animateScrollTo(0) }
    // Back from the "Install unknown apps" screen: continue the pending download automatically.
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val pa = pendingInstallAsset
                if (pa != null && AppUpdates.canInstall(context) && ui !is UpdateUi.Downloading) {
                    pendingInstallAsset = null
                    startDownload(pa)
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(obs)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(obs) }
    }

    Column(
        modifier.fillMaxSize().background(LiveColors.Bg).verticalScroll(scrollState).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("App updates") {
            Info("Installed", installed)
            LiveTextButton(
                "Check for updates", { startCheck() },
                Modifier.fillMaxWidth(), enabled = ui !is UpdateUi.Checking && ui !is UpdateUi.Downloading,
            )
            when (val u = ui) {
                UpdateUi.Idle -> Unit
                UpdateUi.Checking -> LiveText("Checking...", style = LiveType.Small, color = LiveColors.TextDim)
                is UpdateUi.UpToDate -> LiveText("Up to date (${u.v}).", style = LiveType.Small, color = LiveColors.Play)
                is UpdateUi.NoStable -> {
                    LiveText("No stable release yet.", style = LiveType.Small, color = LiveColors.Text)
                    if (u.testAvailable) LiveText("Test builds exist - switch the channel to Test to get them.", style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3)
                }
                UpdateUi.NoReleases -> LiveText("No releases have been published yet.", style = LiveType.Small, color = LiveColors.TextDim)
                is UpdateUi.Failed -> LiveText(u.msg, style = LiveType.Small, color = LiveColors.Rec, maxLines = 3)
                is UpdateUi.Downloading -> {
                    LiveText(
                        if (u.progress >= 0) "Downloading ${(u.progress * 100).toInt()}%" else "Downloading...",
                        style = LiveType.Small,
                    )
                    Box(Modifier.fillMaxWidth().height(6.dp).background(LiveColors.PanelHi, LiveShapes.Small)) {
                        Box(Modifier.fillMaxWidth(u.progress.coerceIn(0f, 1f)).height(6.dp).background(LiveColors.Accent, LiveShapes.Small))
                    }
                }
                is UpdateUi.Available -> {
                    LiveText("Version ${u.release.version} available", style = LiveType.Body, color = LiveColors.Accent)
                    if (u.release.notes.isNotBlank()) LiveText(u.release.notes, style = LiveType.Small, color = LiveColors.TextDim, maxLines = 6)
                    if (u.asset != null) {
                        LiveTextButton(
                            "Download and install",
                            { startDownload(u.asset) },
                            Modifier.fillMaxWidth(), on = true,
                        )
                    } else {
                        LiveText("This release has no APK yet.", style = LiveType.Small, color = LiveColors.TextDim)
                    }
                    LiveTextButton("Open release page", { AppUpdates.openInBrowser(context, u.release.pageUrl) }, Modifier.fillMaxWidth())
                }
            }
            LiveLabel("Channel")
            if (installedIsTest) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LiveTextButton("Stable", {}, Modifier.weight(1f), enabled = false)
                    LiveTextButton("Test (automatic for test builds)", {}, Modifier.weight(2f), on = true)
                }
                LiveText(
                    "This is a test build, so it always follows the Test channel. Stable is only selectable on a stable build.",
                    style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LiveTextButton("Stable", { pickChannel(false) }, Modifier.weight(1f), on = !test)
                    LiveTextButton("Test", { pickChannel(true) }, Modifier.weight(1f), on = test)
                }
                LiveText(
                    if (test) "Test builds (pre-releases) are offered as well." else "Only stable releases are offered.",
                    style = LiveType.Small, color = LiveColors.TextDim, maxLines = 2,
                )
            }
        }

        if (connected != null) connected()

        Section("Screen") {
            LiveLabel("Orientation")
            var orient by remember { mutableStateOf(actions.prefs?.orientation ?: "land") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("land" to "Landscape", "port" to "Portrait", "auto" to "Auto").forEach { (k, label) ->
                    LiveTextButton(label, {
                        orient = k
                        actions.prefs?.orientation = k
                        (context as? android.app.Activity)?.let { com.livecompanion.applyOrientation(it, k) }
                    }, Modifier.weight(1f), on = orient == k)
                }
            }
        }

        Section("Help") {
            LiveLabel("Arrange")
            LiveText(ARRANGE_HELP, style = LiveType.Small, color = LiveColors.TextDim, maxLines = 4)
        }

        if (remember { com.livecompanion.net.SimDelay.available(context) }) Section("Developer") {
            var sim by remember { mutableStateOf(actions.prefs?.simulateDelay ?: false) }
            LiveText(
                "Simulate a slow server: every request reaches Live ${com.livecompanion.net.SimDelay.DEFAULT_MS} ms late. " +
                    "Drags, faders and edits must still follow the finger and never snap back.",
                style = LiveType.Small, color = LiveColors.TextDim, maxLines = 4,
            )
            LiveTextButton(if (sim) "Simulate ${com.livecompanion.net.SimDelay.DEFAULT_MS} ms server delay: ON" else "Simulate ${com.livecompanion.net.SimDelay.DEFAULT_MS} ms server delay: off", {
                sim = !sim
                actions.prefs?.simulateDelay = sim
                com.livecompanion.net.SimDelay.ms = if (sim) com.livecompanion.net.SimDelay.DEFAULT_MS else 0L
            }, Modifier.fillMaxWidth(), on = sim)
        }
    }
}

/** Asset waiting for the "install unknown apps" permission; resumed on ON_RESUME once it is granted. */
private var pendingInstallAsset: UpdateCheck.Asset? = null

private suspend fun downloadAndInstall(context: Context, asset: UpdateCheck.Asset, set: (UpdateUi) -> Unit) {
    if (!AppUpdates.canInstall(context)) {
        // Takes the person to the "install unknown apps" switch for this app; they press the button again after.
        pendingInstallAsset = asset
        set(UpdateUi.Failed("Allow installs from LiveCompanion in the settings screen that just opened - the download continues when you come back."))
        runCatching { context.startActivity(AppUpdates.unknownSourcesIntent(context)) }
        return
    }
    pendingInstallAsset = null
    set(UpdateUi.Downloading(0f))
    try {
        val apk = withContext(Dispatchers.IO) {
            AppUpdates.download(context, asset, onProgress = { set(UpdateUi.Downloading(it)) })
        }
        AppUpdates.install(context, apk)
        set(UpdateUi.Idle)
    } catch (e: Exception) {
        set(UpdateUi.Failed(e.message ?: "The download failed."))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LiveLabel(title)
        LiveDivider()
        content()
    }
}

@Composable
private fun Info(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(k, Modifier.weight(0.4f), LiveType.Small, LiveColors.TextDim)
        LiveText(v, Modifier.weight(0.6f), LiveType.Body)
    }
}
