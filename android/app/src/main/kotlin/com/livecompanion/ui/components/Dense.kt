package com.livecompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/** Dense (32dp) button for Ableton-like control rows. */
@Composable
fun DenseButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: androidx.compose.ui.graphics.Color = LiveColors.Accent,
    enabled: Boolean = true,
) = LiveTextButton(label, onClick, modifier, on, onColor, enabled, minHeight = 32.dp, minWidth = 32.dp)

/** Dense single-line text field (30dp). */
@Composable
fun DenseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LiveType.Body.copy(color = LiveColors.Text),
        cursorBrush = SolidColor(LiveColors.Accent),
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.heightIn(min = 32.dp).fillMaxWidth()
                    .background(LiveColors.Bg, LiveShapes.Small)
                    .border(1.dp, LiveColors.Line, LiveShapes.Small)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) LiveText(placeholder, color = LiveColors.TextDim)
                inner()
            }
        },
    )
}

/** Dropdown-style selector: a 32dp field showing the current value; tapping opens a plain dialog list. */
@Composable
fun DenseSelect(
    title: String,
    value: String,
    items: List<String>,
    onSelect: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.heightIn(min = 32.dp)
            .clip(LiveShapes.Small)
            .background(LiveColors.PanelHi)
            .clickable(enabled = enabled && items.isNotEmpty()) { open = true }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LiveText(value.ifEmpty { "-" }, Modifier.weight(1f), LiveType.Small)
        LiveIcon(LiveIconKind.Down, LiveColors.TextDim, size = 12.dp)
    }
    if (open) {
        LiveDialog(title, { open = false }) {
            LiveScrollColumn(Modifier.weight(1f, fill = false)) {
                items.forEachIndexed { i, s ->
                    val sel = s == value
                    Row(
                        Modifier.fillMaxWidth().height(34.dp)
                            .background(if (sel) LiveColors.Accent else LiveColors.PanelHi, LiveShapes.Small)
                            .clickable { open = false; onSelect(i, s) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { LiveText(s, style = LiveType.Body, color = if (sel) androidx.compose.ui.graphics.Color.Black else LiveColors.Text) }
                }
            }
            LiveDialogButtons { DenseButton("Cancel", { open = false }) }
        }
    }
}

/** Confirm dialog (title, message, confirm label). */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    LiveDialog(title, onDismiss) {
        LiveText(message, color = LiveColors.TextDim, maxLines = 3)
        LiveDialogButtons {
            DenseButton("Cancel", onDismiss)
            DenseButton(confirm, { onConfirm(); onDismiss() }, on = true, onColor = LiveColors.Rec)
        }
    }
}
