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
import androidx.compose.foundation.lazy.items
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
 */
@Composable
fun ConnectScreen(
    discovered: List<DiscoveredHost>,
    recents: List<HostTarget>,
    onConnect: (HostTarget) -> Unit,
    onForgetRecent: (HostTarget) -> Unit = {},
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var manual by rememberSaveable { mutableStateOf("") }
    val parsed = parseHostPort(manual)

    BoxWithConstraints(modifier.fillMaxSize().background(LiveColors.Bg)) {
        val wide = maxWidth >= 480.dp
        val manualBlock: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveLabel("Manual")
                LiveTextField(
                    manual, { manual = it },
                    placeholder = "host or host:port (default 9010)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    onDone = { parsed?.let(onConnect) },
                )
                LiveTextButton("Connect", { parsed?.let(onConnect) }, Modifier.fillMaxWidth(), on = parsed != null, enabled = parsed != null)
                LiveTextButton(
                    "localhost 10.0.2.2:9010 (mock)",
                    { onConnect(HostTarget("10.0.2.2", 9010)) },
                    Modifier.fillMaxWidth(),
                )
                if (error != null) LiveText(error, style = LiveType.Small, color = LiveColors.Accent, maxLines = 3)
            }
        }
        val listBlock: @Composable (Modifier) -> Unit = { m ->
            LazyColumn(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { LiveLabel("Found on this network") }
                if (discovered.isEmpty()) {
                    item { LiveText("Searching... (start Live with the LiveCompanion control surface)", style = LiveType.Small, color = LiveColors.TextDim, maxLines = 3) }
                }
                items(discovered, key = { "d${it.address}:${it.wsPort}" }) { h ->
                    HostRow(h.name.ifEmpty { h.address }, "${h.address}:${h.wsPort}" + if (h.set.isNotEmpty()) "  -  ${h.set}" else "", null) {
                        onConnect(HostTarget(h.address, h.wsPort))
                    }
                }
                item { LiveLabel("Recent") }
                if (recents.isEmpty()) item { LiveText("None yet", style = LiveType.Small, color = LiveColors.TextDim) }
                items(recents, key = { "r${it.label}" }) { t ->
                    HostRow(t.label, "", { onForgetRecent(t) }) { onConnect(t) }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveText("LiveCompanion", style = LiveType.Title, color = LiveColors.Accent)
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listBlock(Modifier.weight(1f).fillMaxSize())
                    manualBlock(Modifier.weight(1f))
                }
            } else {
                // Compact: the manual block is a fixed-height strip above the scrolling host lists.
                manualBlock(Modifier.fillMaxWidth())
                listBlock(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun HostRow(title: String, subtitle: String, onForget: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).background(LiveColors.Panel, LiveShapes.Small)
            .clickable(onClick = onClick).padding(start = 10.dp),
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
