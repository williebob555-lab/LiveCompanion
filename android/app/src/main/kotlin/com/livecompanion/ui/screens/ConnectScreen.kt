package com.livecompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import com.livecompanion.data.hostPortError
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.livecompanion.data.parseHostPort
import com.livecompanion.net.DiscoveredHost
import com.livecompanion.net.HostTarget
import com.livecompanion.ui.components.LiveIconButton
import com.livecompanion.ui.components.LiveIconKind
import com.livecompanion.ui.components.LiveLabel
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveTextField
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/**
 * Connect screen: discovered hosts (UDP beacon), manual host:port, recents, and the emulator
 * mock shortcut. Stateless wrt. networking so it can be screenshot-tested with fake data.
 *
 * While [connecting] is non-null the lists are dimmed/disabled and a "Connecting to X... Cancel" strip is shown;
 * [error] (last failed attempt) is shown in the same strip. Scrolls (and shrinks via the IME insets) so the
 * Connect button is always reachable with the keyboard open.
 */
@Composable
fun ConnectScreen(
    discovered: List<DiscoveredHost>,
    recents: List<HostTarget>,
    onConnect: (HostTarget) -> Unit,
    onForgetRecent: (HostTarget) -> Unit = {},
    modifier: Modifier = Modifier,
    error: String? = null,
    connecting: HostTarget? = null,
    onCancel: () -> Unit = {},
    initialManual: String = "",
) {
    var manual by rememberSaveable { mutableStateOf(initialManual) }
    val parsed = parseHostPort(manual)
    val inputError = hostPortError(manual)
    val busy = connecting != null
    val go: (HostTarget) -> Unit = { if (!busy) onConnect(it) }

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg).imePadding()) {
        val wide = maxWidth >= 480.dp
        val status: @Composable () -> Unit = {
            if (connecting != null) {
                Row(
                    Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveText("Connecting to ${connecting.label}...", Modifier.weight(1f), LiveType.Small, LiveColors.Accent, maxLines = 2)
                    LiveTextButton("Cancel", onCancel, minWidth = 72.dp)
                }
            } else if (error != null) {
                LiveText(error, Modifier.fillMaxWidth().background(LiveColors.Panel, LiveShapes.Small).padding(8.dp), LiveType.Small, LiveColors.Rec, maxLines = 4)
            }
        }
        val manualBlock: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveLabel("Manual")
                LiveTextField(
                    manual, { manual = it },
                    placeholder = "host or host:port (default 9010)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    onDone = { parsed?.let(go) },
                )
                if (inputError != null) LiveText(inputError, style = LiveType.Small, color = LiveColors.Rec, maxLines = 2)
                LiveTextButton("Connect", { parsed?.let(go) }, Modifier.fillMaxWidth(), on = parsed != null && !busy, enabled = parsed != null && !busy)
                LiveTextButton(
                    "localhost 10.0.2.2:9010 (mock)",
                    { go(HostTarget("10.0.2.2", 9010)) },
                    Modifier.fillMaxWidth(), enabled = !busy,
                )
                status()
            }
        }
        val listItems: LazyListScope.() -> Unit = {
            item { LiveLabel("Found on this network") }
            if (discovered.isEmpty()) {
                item { LiveText("Searching... (start Live with the LiveCompanion control surface)", style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3) }
            }
            items(discovered, key = { "d${it.address}:${it.wsPort}" }) { h ->
                HostRow(h.name.ifEmpty { h.address }, "${h.address}:${h.wsPort}" + if (h.set.isNotEmpty()) "  -  ${h.set}" else "", null, !busy) {
                    go(HostTarget(h.address, h.wsPort))
                }
            }
            item { LiveLabel("Recent") }
            if (recents.isEmpty()) item { LiveText("None yet", style = LiveType.Small, color = LiveColors.TextDim) }
            items(recents, key = { "r${it.label}" }) { t ->
                HostRow(t.label, "", { onForgetRecent(t) }, !busy) { go(t) }
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveText("LiveCompanion", style = LiveType.Title, color = LiveColors.Accent)
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LazyColumn(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp), content = listItems)
                    manualBlock(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
            } else {
                // Compact: one scrolling list with the manual block on top, so the keyboard never hides Connect.
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item { manualBlock(Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                    listItems()
                }
            }
        }
    }
}

@Composable
private fun HostRow(title: String, subtitle: String, onForget: (() -> Unit)?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.5f).background(LiveColors.Panel, LiveShapes.Small)
            .clickable(enabled = enabled, onClick = onClick).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            LiveText(title, style = LiveType.Body)
            if (subtitle.isNotEmpty()) LiveText(subtitle, style = LiveType.Small, color = LiveColors.TextDim)
        }
        if (onForget != null) LiveIconButton(LiveIconKind.Close, onForget, iconColor = LiveColors.TextDim)
    }
}
