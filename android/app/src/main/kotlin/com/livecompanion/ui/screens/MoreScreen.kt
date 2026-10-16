package com.livecompanion.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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

/** "More" tab = settings: host info, follow-Live-selection, update channel + in-app updates. */
@Composable
fun MoreScreen(store: LiveStore, client: LiveClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val actions = LocalAppActions.current
    val conn by client.state.collectAsState()
    val hello by store.hello.collectAsState()
    val session by store.session.collectAsState()
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

    Column(
        modifier.fillMaxSize().background(LiveColors.Bg).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("Host") {
            Info("Address", conn.target?.label ?: "-")
            Info("Status", conn.status.name.lowercase())
            Info("Ableton Live", hello?.live?.ifEmpty { null } ?: "-")
            Info("Host type", hello?.host?.ifEmpty { null } ?: "-")
            LiveTextButton("Disconnect", actions.disconnect, Modifier.fillMaxWidth())
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

        Section("App updates") {
            Info("Installed", installed)
            LiveLabel("Channel")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                LiveTextButton("Stable", { pickChannel(false) }, Modifier.weight(1f), on = !test)
                LiveTextButton("Test", { pickChannel(true) }, Modifier.weight(1f), on = test)
            }
            LiveText(
                if (installedIsTest) "This is a test build, so it always follows the Test channel."
                else if (test) "Test builds (pre-releases) are offered as well." else "Only stable releases are offered.",
                style = LiveType.Small, color = LiveColors.TextDim, maxLines = 2,
            )
            LiveTextButton(
                "Check for updates",
                {
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
                },
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
                            { scope.launch { downloadAndInstall(context, u.asset) { ui = it } } },
                            Modifier.fillMaxWidth(), on = true,
                        )
                    } else {
                        LiveText("This release has no APK yet.", style = LiveType.Small, color = LiveColors.TextDim)
                    }
                    LiveTextButton("Open release page", { AppUpdates.openInBrowser(context, u.release.pageUrl) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private suspend fun downloadAndInstall(context: Context, asset: UpdateCheck.Asset, set: (UpdateUi) -> Unit) {
    if (!AppUpdates.canInstall(context)) {
        // Takes the person to the "install unknown apps" switch for this app; they press the button again after.
        set(UpdateUi.Failed("Allow installs from LiveCompanion in the settings screen that just opened, then try again."))
        runCatching { context.startActivity(AppUpdates.unknownSourcesIntent(context)) }
        return
    }
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
