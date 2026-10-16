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
 * Subdivision control: Auto (adaptive) + fixed 1/1..1/32 + Triplet toggle + Snap, and the time signature button.
 * [adaptiveLabel] is the step the adaptive grid currently uses (shown on the Auto button).
 */
@Composable
fun GridControls(
    sub: Subdiv, onSub: (Subdiv) -> Unit, snap: Boolean, onSnap: (Boolean) -> Unit, sigLabel: String, onSig: () -> Unit,
    adaptiveLabel: String, showSub: Boolean = true,
) {
    ToolBtn(sigLabel, onSig, minWidth = 38.dp)
    if (showSub) {
        ToolCaption("GRID")
        ToolBtn(if (sub.adaptive) "Auto $adaptiveLabel" else "Auto", { onSub(sub.copy(adaptive = true)) }, on = sub.adaptive, minWidth = 40.dp)
        for (d in Subdiv.DENOMS) {
            ToolBtn("1/$d", { onSub(sub.copy(adaptive = false, denom = d)) }, on = !sub.adaptive && sub.denom == d, minWidth = 32.dp)
        }
        ToolBtn("Trip", { onSub(sub.copy(triplet = !sub.triplet, adaptive = if (sub.adaptive) false else sub.adaptive)) }, on = sub.triplet && !sub.adaptive, minWidth = 34.dp)
    }
    ToolBtn("Snap", { onSnap(!snap) }, on = snap, minWidth = 36.dp)
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

/** Always-visible zoom buttons floating at the bottom-right of an editor. [vertical] adds V-/V+ (piano roll). */
@Composable
fun ZoomPad(modifier: Modifier, onH: (Float) -> Unit, onV: ((Float) -> Unit)?, onFit: () -> Unit) {
    Row(
        modifier.padding(6.dp).background(LiveColors.Bg.copy(alpha = 0.85f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolBtn("H-", { onH(0.7f) }); ToolBtn("H+", { onH(1.43f) })
        if (onV != null) { ToolBtn("V-", { onV(0.8f) }); ToolBtn("V+", { onV(1.25f) }) }
        ToolBtn("Fit", onFit)
    }
}
