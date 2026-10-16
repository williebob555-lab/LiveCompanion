package com.livecompanion.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.livecompanion.ui.components.LiveDialog
import com.livecompanion.ui.components.LiveDialogButtons
import com.livecompanion.ui.components.LiveText
import com.livecompanion.ui.components.LiveTextButton
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveType

/** Height of one toolbar row. */
val ToolH = 28.dp

@Composable
fun ToolBtn(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true,
    onColor: Color = LiveColors.Accent, minWidth: Dp = 30.dp,
) = LiveTextButton(label, onClick, modifier, on, onColor, enabled, null, ToolH, minWidth)

/** Dim section caption inside a toolbar. */
@Composable
fun ToolCaption(text: String) = LiveText(text, Modifier.padding(horizontal = 3.dp), style = LiveType.Label)

/** One scrollable toolbar row (never wraps, never overlaps; scrolls sideways if the screen is narrow). */
@Composable
fun ToolRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(ToolH).background(LiveColors.Panel).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/**
 * Subdivision control: signature button, ONE grid picker button ("Auto 1/16 v" / "1/8T v") that opens a small fixed
 * popup grid (Auto, 1/1..1/32, Triplet), and Snap. [adaptiveLabel] is the step the adaptive grid currently uses.
 */
@Composable
fun GridControls(
    sub: Subdiv, onSub: (Subdiv) -> Unit, snap: Boolean, onSnap: (Boolean) -> Unit, sigLabel: String, onSig: () -> Unit,
    adaptiveLabel: String, showSub: Boolean = true,
) {
    var pick by remember { mutableStateOf(false) }
    // One place for signature + grid (the ruler corner no longer repeats them).
    ToolBtn(sigLabel, onSig, minWidth = 38.dp)
    if (showSub) {
        ToolBtn(sub.label(null).let { if (sub.adaptive) "Auto $adaptiveLabel" else it } + " v", { pick = true }, minWidth = 64.dp)
    }
    ToolBtn("Snap", { onSnap(!snap) }, on = snap, minWidth = 36.dp)
    if (pick) GridPickerDialog(sub, onSub) { pick = false }
}

/** Fixed popup grid of subdivisions; picking one applies it and closes. */
@Composable
private fun GridPickerDialog(sub: Subdiv, onSub: (Subdiv) -> Unit, onDismiss: () -> Unit) {
    LiveDialog(title = "Grid", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolBtn("Auto", { onSub(sub.copy(adaptive = true)); onDismiss() }, on = sub.adaptive, minWidth = 56.dp)
            ToolBtn("Triplet", { onSub(sub.copy(triplet = !sub.triplet, adaptive = false)) }, on = sub.triplet && !sub.adaptive, minWidth = 64.dp)
        }
        for (rowDenoms in Subdiv.DENOMS.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in rowDenoms) {
                    ToolBtn("1/$d", { onSub(sub.copy(adaptive = false, denom = d)); onDismiss() }, on = !sub.adaptive && sub.denom == d, minWidth = 56.dp)
                }
            }
        }
        LiveDialogButtons { ToolBtn("Close", onDismiss, minWidth = 56.dp) }
    }
}

/** Time signature editor: numerator typed with +/- steppers, denominator chosen from 1,2,4,8,16. */
@Composable
fun SigDialog(num: Int, den: Int, onDismiss: () -> Unit, onOk: (Int, Int) -> Unit) {
    var n by remember { mutableIntStateOf(num.coerceIn(1, 99)) }
    var d by remember { mutableIntStateOf(den) }
    LiveDialog(title = "Clip time signature", onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LiveText("Beats per bar", Modifier.weight(1f), style = LiveType.Small, color = LiveColors.TextDim)
            ToolBtn("-", { n = (n - 1).coerceAtLeast(1) })
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { LiveText("$n", style = LiveType.Title) }
            ToolBtn("+", { n = (n + 1).coerceAtMost(99) })
        }
        LiveText("Beat unit", style = LiveType.Small, color = LiveColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (x in listOf(1, 2, 4, 8, 16)) ToolBtn("1/$x", { d = x }, on = d == x, minWidth = 40.dp)
        }
        LiveText("Result: $n/$d - ruler and grid follow it.", style = LiveType.Small)
        LiveDialogButtons {
            ToolBtn("Cancel", onDismiss, minWidth = 56.dp)
            ToolBtn("OK", { onOk(n, d); onDismiss() }, on = true, enabled = validSignature(n, d), minWidth = 56.dp)
        }
    }
}

/** Compact icon toggle (24dp-high tool button with a vector icon); [label] is shown beside the icon when given. */
@Composable
fun ToolIcon(
    icon: com.livecompanion.ui.components.LiveIconKind, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    on: Boolean = false, enabled: Boolean = true, label: String? = null, onColor: Color = LiveColors.Accent,
) {
    com.livecompanion.ui.components.LiveButton(
        onClick, modifier.semantics { contentDescription = description }, on, onColor, enabled,
        minHeight = ToolH, minWidth = 30.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp),
    ) { c ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            com.livecompanion.ui.components.LiveIcon(icon, c, size = 14.dp)
            if (label != null) LiveText(label, style = LiveType.Small, color = c)
        }
    }
}

/** Thin vertical divider between toolbar groups. */
@Composable
fun ToolSep() = Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(16.dp).background(LiveColors.Line))

/** Always-visible zoom buttons floating (translucent) at the bottom-right of an editor. [onV] adds V-/V+ (piano roll). */
@Composable
fun ZoomPad(modifier: Modifier, onH: (Float) -> Unit, onV: ((Float) -> Unit)?, onFit: () -> Unit) {
    Row(
        modifier.padding(4.dp).background(LiveColors.Bg.copy(alpha = 0.6f)),
        horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoomBtn("H-") { onH(0.7f) }; ZoomBtn("H+") { onH(1.43f) }
        if (onV != null) { ZoomBtn("V-") { onV(0.8f) }; ZoomBtn("V+") { onV(1.25f) } }
        ZoomBtn("Fit", onFit)
    }
}

@Composable
private fun ZoomBtn(label: String, onClick: () -> Unit) = LiveTextButton(label, onClick, minHeight = 24.dp, minWidth = 26.dp)
