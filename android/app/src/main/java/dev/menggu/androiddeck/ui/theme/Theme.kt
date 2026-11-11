package dev.menggu.androiddeck.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

@Immutable
data class DeckPalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHigher: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val ok: Color,
    val warn: Color,
    val cpu: Color,
    val mem: Color,
    val gpu: Color,
    val up: Color,
    val down: Color,
    val disk: Color,
)

val DarkPalette = DeckPalette(
    isDark = true,
    bg = Color(0xFF0B0C0F),
    surface = Color(0xFF16181D),
    surfaceHigh = Color(0xFF1F2229),
    surfaceHigher = Color(0xFF2A2E37),
    outline = Color(0xFF2C303A),
    text = Color(0xFFF3F4F6),
    textDim = Color(0xFF9197A4),
    textFaint = Color(0xFF7D8391),
    accent = Color(0xFFC6F36B),
    onAccent = Color(0xFF111A04),
    danger = Color(0xFFFF6B6B),
    ok = Color(0xFF5BE49B),
    warn = Color(0xFFFFC857),
    cpu = Color(0xFFC6F36B),
    mem = Color(0xFF7AA2FF),
    gpu = Color(0xFFB794FF),
    up = Color(0xFFFF9F6B),
    down = Color(0xFF4DD8C0),
    disk = Color(0xFFFFC857),
)

val LightPalette = DeckPalette(
    isDark = false,
    bg = Color(0xFFF3F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEBEDF1),
    surfaceHigher = Color(0xFFDFE2E8),
    outline = Color(0xFFDADDE3),
    text = Color(0xFF0F1115),
    textDim = Color(0xFF5F6675),
    textFaint = Color(0xFF6F7582),
    accent = Color(0xFFB4EB4D),
    onAccent = Color(0xFF111A04),
    danger = Color(0xFFE5484D),
    ok = Color(0xFF16A34A),
    warn = Color(0xFFD99A00),
    cpu = Color(0xFF65A30D),
    mem = Color(0xFF3B6FF5),
    gpu = Color(0xFF8B5CF6),
    up = Color(0xFFF97316),
    down = Color(0xFF0EA5A0),
    disk = Color(0xFFD99A00),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** A highlight color offered in Settings; [light] is the value used with the light theme. */
@Immutable
data class AccentPreset(val id: String, val label: String, val dark: Color, val light: Color)

const val DEFAULT_ACCENT = "lime"

val AccentPresets = listOf(
    AccentPreset(DEFAULT_ACCENT, "Lime", DarkPalette.accent, LightPalette.accent),
    AccentPreset("sky", "Sky", Color(0xFF7CC4FF), Color(0xFF4DA8F5)),
    AccentPreset("violet", "Violet", Color(0xFFB794FF), Color(0xFF9D7BF2)),
    AccentPreset("coral", "Coral", Color(0xFFFF8F7A), Color(0xFFF7735C)),
    AccentPreset("amber", "Amber", Color(0xFFFFC857), Color(0xFFF5B83D)),
    AccentPreset("mint", "Mint", Color(0xFF5BE4B5), Color(0xFF3CCF9E)),
    AccentPreset("pink", "Pink", Color(0xFFFF8AD8), Color(0xFFF06CC6)),
    AccentPreset("mono", "Mono", Color(0xFFECEEF2), Color(0xFF1F2229)),
)

/** Highlight color for a stored setting (preset id or custom "#RRGGBB"); unknown values fall back to lime. */
fun accentColor(setting: String, dark: Boolean): Color {
    AccentPresets.firstOrNull { it.id == setting }?.let { return if (dark) it.dark else it.light }
    return parseAccentHex(setting) ?: if (dark) DarkPalette.accent else LightPalette.accent
}

/** "#RRGGBB" (or "RRGGBB") to an opaque color; null if malformed. */
fun parseAccentHex(text: String): Color? {
    val s = text.trim().removePrefix("#")
    if (s.length != 6) return null
    val v = s.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** WCAG contrast ratio between two colors. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * The highlight for a filled bar on [track]: darkened (light theme) or lightened (dark theme) just
 * enough to stand out from the track by 3:1, so the level is readable with any highlight color.
 */
fun DeckPalette.accentOn(track: Color): Color {
    var c = accent
    val toward = if (isDark) Color.White else Color.Black
    var i = 0
    while (contrast(c, track) < 3f && i < 24) {
        c = androidx.compose.ui.graphics.lerp(c, toward, 0.06f)
        i++
    }
    return c
}

/** Near-black or white, whichever has the higher contrast on [bg]. */
fun onColorFor(bg: Color): Color {
    val l = bg.luminance()
    val ink = Color(0xFF0E1013)
    val onWhite = 1.05f / (l + 0.05f)
    val onInk = (l + 0.05f) / (ink.luminance() + 0.05f)
    return if (onInk >= onWhite) ink else Color.White
}

private fun DeckPalette.withAccent(setting: String): DeckPalette {
    if (setting == DEFAULT_ACCENT) return this
    val c = accentColor(setting, isDark)
    return copy(accent = c, onAccent = onColorFor(c))
}

private fun DeckPalette.toScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surfaceHigh,
        onPrimaryContainer = text,
        secondary = textDim,
        onSecondary = bg,
        secondaryContainer = surfaceHigh,
        onSecondaryContainer = text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = textDim,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigher,
        outline = outline,
        outlineVariant = outline,
        error = danger,
        inverseSurface = text,
        inverseOnSurface = bg,
    )
}

private val DeckTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-3).sp, fontSize = 88.sp, lineHeight = 92.sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp),
    )
}

@Composable
fun DeckTheme(themeMode: Int, accent: String = DEFAULT_ACCENT, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    val palette = remember(dark, accent) { (if (dark) DarkPalette else LightPalette).withAccent(accent) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.decorView.setBackgroundColor(android.graphics.Color.argb(
                255,
                (palette.bg.red * 255).toInt(),
                (palette.bg.green * 255).toInt(),
                (palette.bg.blue * 255).toInt(),
            ))
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.toScheme(), typography = DeckTypography, content = content)
    }
}
