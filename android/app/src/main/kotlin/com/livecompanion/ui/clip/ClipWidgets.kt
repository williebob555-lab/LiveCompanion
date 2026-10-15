package com.livecompanion.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.components.LiveTextField
import com.livecompanion.ui.components.liveColor
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/** Dense control height used across the clip editor (owner wants Ableton-like density). */
val DenseH = 30.dp

/** Small flat button, 30dp tall. */
@Composable
fun Btn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true,
        onColor: Color = LiveColors.Accent, minWidth: androidx.compose.ui.unit.Dp = 30.dp) =
    LiveTextButton(label, onClick, modifier, on, onColor, enabled, null, DenseH, minWidth)

/** Label + current value; tap opens a list dialog. */
@Composable
fun ChoiceField(label: String, items: List<String>, index: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    FieldBox(label, items.getOrElse(index) { "-" }, modifier.clickable(enabled = enabled) { open = true }, enabled)
    if (open) {
        LiveDialog(title = label, onDismiss = { open = false }) {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(items) { i, s ->
                    Box(
                        Modifier.fillMaxWidth().height(DenseH).background(if (i == index) LiveColors.Accent else LiveColors.PanelHi)
                            .clickable { open = false; onPick(i) }.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { LiveText(s, color = if (i == index) Color.Black else LiveColors.Text) }
                    Box(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** The shared look of a label/value cell. */
@Composable
fun FieldBox(label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.height(DenseH).clip(LiveShapes.Small).background(LiveColors.PanelHi).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LiveText(label.uppercase(), style = LiveType.Label, modifier = Modifier.weight(1f, fill = false))
        LiveText(value, style = LiveType.Small, color = if (enabled) LiveColors.Text else LiveColors.TextDim,
            modifier = Modifier.weight(1f, fill = true), textAlign = TextAlign.End)
    }
}

/**
 * Numeric field: horizontal drag changes it ([onDrag] gets px since the last event), tap opens a typing dialog.
 */
@Composable
fun NumField(
    label: String, display: String, modifier: Modifier = Modifier,
    typedInit: String = display, enabled: Boolean = true,
    onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}, onTyped: (String) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    val dragS = rememberUpdatedState(onDrag)
    val endS = rememberUpdatedState(onDragEnd)
    FieldBox(
        label, display,
        modifier
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { typing = true }
            }
            .pointerInput(enabled) {
                if (enabled) detectDragGestures(onDragEnd = { endS.value() }, onDragCancel = { endS.value() }) { c, d ->
                    c.consume(); dragS.value(d.x)
                }
            },
        enabled,
    )
    if (typing) TypeDialog(label, typedInit, onDismiss = { typing = false }, onOk = { typing = false; onTyped(it) })
}

@Composable
fun TypeDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    LiveDialog(title = title, onDismiss = onDismiss) {
        LiveTextField(text, { text = it }, Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), onDone = { onOk(text) })
        LiveDialogButtons {
            LiveTextButton("Cancel", onDismiss, minHeight = DenseH)
            LiveTextButton("OK", { onOk(text) }, on = true, minHeight = DenseH)
        }
    }
}

