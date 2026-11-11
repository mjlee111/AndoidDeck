package dev.menggu.androiddeck.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.menggu.androiddeck.ui.components.ACCENT_COLOR
import dev.menggu.androiddeck.ui.components.buttonColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun presetsAndFallback() {
        assertEquals(DarkPalette.accent, accentColor(DEFAULT_ACCENT, dark = true))
        assertEquals(LightPalette.accent, accentColor(DEFAULT_ACCENT, dark = false))
        assertEquals(Color(0xFF7CC4FF), accentColor("sky", dark = true))
        assertEquals(DarkPalette.accent, accentColor("no-such-preset", dark = true))
    }

    @Test
    fun customHexRoundTrips() {
        assertEquals(Color(0xFFF5A66E), accentColor("#F5A66E", dark = true))
        assertEquals("#F5A66E", Color(0xFFF5A66E).toHex())
        assertEquals(Color(0xFF123ABC), parseAccentHex("123abc"))
        assertNull(parseAccentHex("#12345"))
        assertNull(parseAccentHex("#GGGGGG"))
    }

    @Test
    fun textOnEveryHighlightIsReadable() {
        val colors = AccentPresets.flatMap { listOf(it.dark, it.light) } + listOf(DarkPalette.danger, LightPalette.danger)
        for (c in colors) {
            val ratio = contrast(onColorFor(c), c)
            assertTrue("${c.toHex()} gives $ratio:1", ratio >= 4.5f)
        }
    }

    @Test
    fun originalLimeButtonsFollowTheHighlight() {
        val accent = Color(0xFF7CC4FF)
        assertEquals(accent, buttonColor(ACCENT_COLOR, accent))
        assertEquals(accent, buttonColor("#c6f36b", accent))
        assertEquals(Color(0xFFFF6B6B), buttonColor("#FF6B6B", accent))
        assertNull(buttonColor(null, accent))
    }
}
