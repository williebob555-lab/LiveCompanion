package com.livecompanion.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import com.livecompanion.ui.theme.LiveColors
import com.livecompanion.ui.theme.LiveShapes
import com.livecompanion.ui.theme.LiveType

/** Single-line text with ellipsis (docs/UI.md: names never wrap into neighbors). */
@Composable
fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LiveType.Body,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color.takeIf { it != Color.Unspecified } ?: style.color,
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Section/field label: 10sp caps dim. */
@Composable
fun LiveLabel(text: String, modifier: Modifier = Modifier) =
    LiveText(text.uppercase(), modifier, LiveType.Label)

/**
 * Live-style toggle/push button: flat 3dp rounded rectangle, lit with [onColor] when [on].
 * Min touch target 32dp (dense). [content] receives the right foreground color.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: Color = LiveColors.Accent,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    minHeight: Dp = 32.dp,
    minWidth: Dp = 32.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp),
    offColor: Color = LiveColors.PanelHi,
    content: @Composable BoxScope.(contentColor: Color) -> Unit,
) {
    val bg = if (on) onColor else offColor
    val fg = if (on) onColor.let { onColor(it) } else LiveColors.Text
    Box(
        modifier
            .heightIn(min = minHeight)
            .widthIn(min = minWidth)
            .clip(LiveShapes.Small)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) { content(if (enabled) fg else fg.copy(alpha = 0.5f)) }
}

@Composable
fun LiveTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: Color = LiveColors.Accent,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    minHeight: Dp = 32.dp,
    minWidth: Dp = 32.dp,
) = LiveButton(onClick, modifier, on, onColor, enabled, onLongClick, minHeight, minWidth) { c ->
    // Button labels never wrap or lose letters ("Fit" must not become "Fi/t"): the button grows instead.
    LiveText(label, style = LiveType.Small, color = c, textAlign = TextAlign.Center, softWrap = false)
}

@Composable
fun LiveIconButton(
    icon: LiveIconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onColor: Color = LiveColors.Accent,
    enabled: Boolean = true,
    iconColor: Color? = null,
    minHeight: Dp = 32.dp,
    minWidth: Dp = 32.dp,
) = LiveButton(onClick, modifier, on, onColor, enabled, null, minHeight, minWidth) { c ->
    LiveIcon(icon, iconColor ?: c)
}

/** Horizontal hairline. */
@Composable
fun LiveDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LiveColors.Line))
}

enum class LiveIconKind { Play, Stop, Rec, SessionRec, Overdub, Loop, Metronome, Undo, Redo, Prev, Next, Down, Plus, Close, Draw, Select, Dup, Trash, SelAll }

/** Vector-drawn icons (no font glyph dependence). */
@Composable
fun LiveIcon(kind: LiveIconKind, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) { drawIcon(kind, color) }
}

private fun DrawScope.drawIcon(kind: LiveIconKind, c: Color) {
    val w = size.width
    val h = size.height
    val sw = (w * 0.1f).coerceAtLeast(1.5f)
    fun p(x: Float, y: Float) = Offset(x * w, y * h)
    when (kind) {
        LiveIconKind.Play -> drawPath(Path().apply { moveTo(.25f * w, .15f * h); lineTo(.85f * w, .5f * h); lineTo(.25f * w, .85f * h); close() }, c)
        LiveIconKind.Stop -> drawRect(c, p(.2f, .2f), Size(.6f * w, .6f * h))
        LiveIconKind.Rec -> drawCircle(c, .32f * w, p(.5f, .5f))
        LiveIconKind.SessionRec -> {
            drawCircle(c, .38f * w, p(.5f, .5f), style = Stroke(sw))
            drawCircle(c, .17f * w, p(.5f, .5f))
        }
        LiveIconKind.Overdub -> {
            drawLine(c, p(.5f, .2f), p(.5f, .8f), sw, StrokeCap.Butt)
            drawLine(c, p(.2f, .5f), p(.8f, .5f), sw, StrokeCap.Butt)
            drawCircle(c, .42f * w, p(.5f, .5f), style = Stroke(sw * 0.8f))
        }
        LiveIconKind.Loop -> {
            drawRoundRect(c, p(.12f, .25f), Size(.76f * w, .5f * h), androidx.compose.ui.geometry.CornerRadius(.15f * w), style = Stroke(sw))
            drawPath(Path().apply { moveTo(.62f * w, .1f * h); lineTo(.85f * w, .25f * h); lineTo(.62f * w, .4f * h); close() }, c)
        }
        LiveIconKind.Metronome -> {
            drawPath(Path().apply { moveTo(.35f * w, .12f * h); lineTo(.65f * w, .12f * h); lineTo(.85f * w, .88f * h); lineTo(.15f * w, .88f * h); close() }, c, style = Stroke(sw))
            drawLine(c, p(.5f, .75f), p(.7f, .28f), sw, StrokeCap.Round)
        }
        LiveIconKind.Undo, LiveIconKind.Redo -> {
            val flip = kind == LiveIconKind.Redo
            fun x(v: Float) = if (flip) 1f - v else v
            drawPath(Path().apply {
                moveTo(x(.1f) * w, .4f * h); lineTo(x(.4f) * w, .15f * h); lineTo(x(.4f) * w, .65f * h); close()
            }, c)
            drawPath(Path().apply {
                moveTo(x(.35f) * w, .4f * h)
                cubicTo(x(.9f) * w, .3f * h, x(.9f) * w, .85f * h, x(.4f) * w, .85f * h)
            }, c, style = Stroke(sw))
        }
        LiveIconKind.Prev -> {
            drawPath(Path().apply { moveTo(.75f * w, .15f * h); lineTo(.25f * w, .5f * h); lineTo(.75f * w, .85f * h); close() }, c)
        }
        LiveIconKind.Next -> {
            drawPath(Path().apply { moveTo(.25f * w, .15f * h); lineTo(.75f * w, .5f * h); lineTo(.25f * w, .85f * h); close() }, c)
        }
        LiveIconKind.Down -> drawPath(Path().apply { moveTo(.2f * w, .35f * h); lineTo(.5f * w, .68f * h); lineTo(.8f * w, .35f * h) }, c, style = Stroke(sw, cap = StrokeCap.Round))
        LiveIconKind.Plus -> {
            drawLine(c, p(.5f, .2f), p(.5f, .8f), sw, StrokeCap.Butt)
            drawLine(c, p(.2f, .5f), p(.8f, .5f), sw, StrokeCap.Butt)
        }
        LiveIconKind.Close -> {
            drawLine(c, p(.25f, .25f), p(.75f, .75f), sw, StrokeCap.Round)
            drawLine(c, p(.75f, .25f), p(.25f, .75f), sw, StrokeCap.Round)
        }
        LiveIconKind.Draw -> {
            drawLine(c, p(.3f, .7f), p(.78f, .22f), sw * 1.7f, StrokeCap.Round)
            drawPath(Path().apply { moveTo(.14f * w, .88f * h); lineTo(.2f * w, .62f * h); lineTo(.4f * w, .8f * h); close() }, c)
        }
        LiveIconKind.Select -> {
            drawPath(Path().apply {
                moveTo(.26f * w, .1f * h); lineTo(.26f * w, .86f * h); lineTo(.44f * w, .68f * h); lineTo(.58f * w, .94f * h)
                lineTo(.7f * w, .88f * h); lineTo(.56f * w, .62f * h); lineTo(.8f * w, .62f * h); close()
            }, c)
        }
        LiveIconKind.Dup -> {
            drawRoundRect(c, p(.12f, .12f), Size(.5f * w, .5f * h), androidx.compose.ui.geometry.CornerRadius(.06f * w), style = Stroke(sw))
            drawRoundRect(c, p(.38f, .38f), Size(.5f * w, .5f * h), androidx.compose.ui.geometry.CornerRadius(.06f * w), style = Stroke(sw))
        }
        LiveIconKind.Trash -> {
            drawLine(c, p(.18f, .26f), p(.82f, .26f), sw, StrokeCap.Round)
            drawPath(Path().apply { moveTo(.4f * w, .26f * h); lineTo(.4f * w, .14f * h); lineTo(.6f * w, .14f * h); lineTo(.6f * w, .26f * h) }, c, style = Stroke(sw))
            drawPath(Path().apply { moveTo(.27f * w, .32f * h); lineTo(.33f * w, .9f * h); lineTo(.67f * w, .9f * h); lineTo(.73f * w, .32f * h) }, c, style = Stroke(sw))
        }
        LiveIconKind.SelAll -> {
            drawRect(c, p(.14f, .14f), Size(.72f * w, .72f * h), style = Stroke(sw, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * .12f, w * .09f))))
        }
    }
}

/** Single-line flat text field. */
@Composable
fun LiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onDone: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LiveType.Body.copy(color = LiveColors.Text),
        cursorBrush = SolidColor(LiveColors.Accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() }),
        modifier = if (autoFocus) modifier.focusRequester(focus) else modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .fillMaxWidth()
                    .background(LiveColors.Bg, LiveShapes.Small)
                    .border(1.dp, LiveColors.Line, LiveShapes.Small)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) LiveText(placeholder, style = LiveType.Body, color = LiveColors.TextDim)
                inner()
            }
        },
    )
}

/**
 * Plain centered dialog: flat panel, fade only. [content] scrolls if taller than the screen.
 * Use [LiveDialogButtons] for the action row.
 */
@Composable
fun LiveDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        Column(
            modifier
                .imePadding()
                .padding(horizontal = 16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(max = maxH)
                .wrapContentHeight()
                .background(LiveColors.Panel, LiveShapes.Medium)
                .border(1.dp, LiveColors.Line, LiveShapes.Medium)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveText(title, style = LiveType.Title)
            if (buttons == null) {
                content()
            } else {
                // Body scrolls when the dialog is taller than the screen (phone landscape); [buttons] stay pinned below.
                // (Do not put a LazyColumn in [content] here: it is measured with unbounded height.)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { content() }
                LiveDialogButtons(buttons)
            }
        }
    }
}

@Composable
fun LiveDialogButtons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) { content() }
}

/** Scrolling column for dialog bodies. */
@Composable
fun LiveScrollColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** Small colored square used as a track/clip color chip. */
@Composable
fun ColorChip(color: Int, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(modifier.size(size).background(liveColor(color), LiveShapes.Small))
}

/** Draws a 1dp border + panel background; helper for strips. */
fun Modifier.panel(color: Color = LiveColors.Panel): Modifier = this.background(color)

fun Modifier.bottomLine(color: Color = LiveColors.Line): Modifier = this.drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}
