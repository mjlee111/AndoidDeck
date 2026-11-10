package dev.menggu.androiddeck.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.ui.theme.LocalPalette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

// ---- basic building blocks ----------------------------------------------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = LocalPalette.current.textDim,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun DeckCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(p.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun StatusDot(state: ConnState?, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val p = LocalPalette.current
    val color = when (state) {
        ConnState.Connected -> p.ok
        ConnState.Connecting -> p.warn
        is ConnState.Offline -> p.danger
        ConnState.Unauthorized -> p.danger
        else -> p.textFaint
    }
    Box(modifier.size(size).clip(CircleShape).background(color))
}

fun connLabel(state: ConnState?): String = when (state) {
    ConnState.Connected -> "Connected"
    ConnState.Connecting -> "Connecting…"
    is ConnState.Offline -> "Offline"
    ConnState.Unauthorized -> "Pair again"
    else -> "Idle"
}

fun osIcon(os: String): ImageVector = when (os) {
    "windows" -> Icons.Rounded.DesktopWindows
    "macos" -> Icons.Rounded.LaptopMac
    else -> Icons.Rounded.Computer
}

fun osLabel(os: String): String = when (os) {
    "windows" -> "Windows"
    "macos" -> "macOS"
    else -> "Linux"
}

@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color? = null, bg: Color? = null) {
    val p = LocalPalette.current
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(bg ?: p.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: p.text, modifier = Modifier.size(size * 0.5f))
    }
}

/** Pill-shaped segmented control. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<T, ImageVector> = emptyMap(),
) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) p.surface else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons[value]?.let {
                    Icon(it, null, tint = if (sel) p.text else p.textDim, modifier = Modifier.size(18.dp))
                    if (label.isNotEmpty()) Box(Modifier.size(6.dp))
                }
                if (label.isNotEmpty()) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) p.text else p.textDim, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    bg: Color? = null,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) p.accent else bg ?: p.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) p.onAccent else p.textDim, modifier = Modifier.size(16.dp))
            Box(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) p.onAccent else p.text, maxLines = 1)
    }
}

@Composable
fun DeckTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    minLines: Int = 1,
) {
    val p = LocalPalette.current
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = p.text,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = style,
        cursorBrush = SolidColor(p.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surfaceHigh)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = p.textFaint))
                inner()
            }
        },
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(CircleShape)
            .background(if (enabled) p.accent else p.surfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) p.onAccent else p.textFaint, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) p.onAccent else p.textFaint)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(CircleShape)
            .background(p.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color ?: p.text, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color ?: p.text)
    }
}

// ---- charts -------------------------------------------------------------------------------------

@Composable
fun RingGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 10.dp,
    content: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = stroke.toPx()
            val d = size.minDimension - sw
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            val arc = Size(d, d)
            drawArc(p.surfaceHigh, 135f, 270f, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            if (anim > 0.001f) {
                drawArc(color, 135f, 270f * anim, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Area sparkline. [maxValue] null = auto scale. */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float? = 100f,
    capacity: Int = 60,
    fill: Boolean = true,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val top = maxValue ?: max(values.max() * 1.15f, 1f)
        val step = size.width / (capacity - 1)
        val startX = size.width - step * (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = size.height - (v / top).coerceIn(0f, 1f) * size.height
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(startX, size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        }
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun BarMeter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val p = LocalPalette.current
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(p.surfaceHigh)) {
        Box(Modifier.fillMaxWidth(anim).height(height).clip(CircleShape).background(color))
    }
}

// ---- formatting ---------------------------------------------------------------------------------

fun fmtBytes(bytes: Double): String {
    if (bytes < 1024) return "${bytes.toInt()} B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    val exp = (ln(bytes) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val v = bytes / 1024.0.pow(exp)
    return if (v < 10) String.format(Locale.US, "%.1f %s", v, units[exp - 1])
    else String.format(Locale.US, "%.0f %s", v, units[exp - 1])
}

fun fmtRate(bytesPerSec: Double): String = fmtBytes(bytesPerSec) + "/s"

fun fmtUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun fmtPercent(v: Double?): String = if (v == null) "–" else "${v.toInt()}%"

fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching {
        val s = hex.removePrefix("#")
        val v = s.toLong(16)
        when (s.length) {
            6 -> Color(0xFF000000 or v)
            8 -> Color(v)
            else -> null
        }
    }.getOrNull()
}

fun contentColorFor(bg: Color): Color = if (bg.luminance() > 0.45f) Color(0xFF0E1013) else Color.White

fun approxEqual(a: Float, b: Float) = abs(a - b) < 0.001f

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)
