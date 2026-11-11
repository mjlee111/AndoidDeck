package dev.menggu.androiddeck.ui.deck

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitGridTest {
    private fun span(n: Int, tile: Dp) = tile * n + GRID_GAP * (n - 1)

    // Deck areas (dp) after the top bar and margins: Fold cover landscape at two densities, cover
    // portrait, inner display. Derived from the screen sizes, not measured on a device.
    private val areas = listOf(727.dp to 409.dp, 633.dp to 350.dp, 392.dp to 591.dp, 860.dp to 934.dp)

    @Test
    fun autoFitKeepsEveryFittingPageOnScreen() {
        for ((w, h) in areas) {
            for (n in 1..24) {
                val f = fitGrid(w, h, n, autoFit = true, fixedTile = 100.dp)
                if (!f.fits) continue
                val rows = (n + f.columns - 1) / f.columns
                assertTrue("width $w n=$n", span(f.columns, f.tile) <= w + 0.5.dp)
                assertTrue("height $h n=$n", span(rows, f.tile) <= h + 0.5.dp)
                assertTrue("tile ${f.tile}", f.tile >= FIT_MIN_TILE - 0.5.dp && f.tile <= FIT_MAX_TILE + 0.5.dp)
                assertTrue(f.columns in 1..FIT_MAX_COLUMNS)
            }
        }
    }

    @Test
    fun defaultDeckFitsTheLandscapeCoverInFiveColumns() {
        val f = fitGrid(727.dp, 409.dp, 13, autoFit = true, fixedTile = 100.dp)
        assertTrue(f.fits)
        assertEquals(5, f.columns)
        assertTrue(f.tile > 120.dp)
    }

    @Test
    fun veryFullPagesFallBackToScrolling() {
        val f = fitGrid(392.dp, 300.dp, 60, autoFit = true, fixedTile = 100.dp)
        assertFalse(f.fits)
        assertTrue(f.tile >= FIT_MIN_TILE - 0.5.dp)
    }

    @Test
    fun fixedSizeKeepsTheSameColumnsOnEveryPage() {
        val small = fitGrid(727.dp, 409.dp, 3, autoFit = false, fixedTile = 100.dp)
        val full = fitGrid(727.dp, 409.dp, 13, autoFit = false, fixedTile = 100.dp)
        assertEquals(small.columns, full.columns)
        assertEquals(small.tile, full.tile)
    }
}
