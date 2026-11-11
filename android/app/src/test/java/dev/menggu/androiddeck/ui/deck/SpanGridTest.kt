package dev.menggu.androiddeck.ui.deck

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpanGridTest {
    private fun one(n: Int) = List(n) { Span(1, 1) }

    @Test
    fun singleCellsFillRowsInOrderAndCenterTheLastRow() {
        val p = packSpans(one(7), columns = 3)
        assertEquals(listOf(Cell(0, 0, 1, 1), Cell(0, 1, 1, 1), Cell(0, 2, 1, 1), Cell(1, 0, 1, 1)), p.cells.take(4))
        assertEquals(3, p.rows)
        assertEquals(2, p.lastRowFree)
    }

    @Test
    fun tallItemsLeaveRoomBesideThem() {
        // A 2x2 widget, then three buttons, on three columns.
        val p = packSpans(listOf(Span(2, 2), Span(1, 1), Span(1, 1), Span(1, 1)), columns = 3)
        assertEquals(Cell(0, 0, 2, 2), p.cells[0])
        assertEquals(Cell(0, 2, 1, 1), p.cells[1])
        assertEquals(Cell(1, 2, 1, 1), p.cells[2])
        assertEquals(Cell(2, 0, 1, 1), p.cells[3])
        assertEquals(3, p.rows)
    }

    @Test
    fun wideItemsWrapToTheNextRowAndNarrowToFit() {
        val p = packSpans(listOf(Span(1, 1), Span(1, 1), Span(3, 1), Span(4, 1)), columns = 3)
        assertEquals(Cell(1, 0, 3, 1), p.cells[2])
        assertEquals(Cell(2, 0, 3, 1), p.cells[3])
    }

    @Test
    fun lastRowStaysLeftWhenATallItemReachesIt() {
        val p = packSpans(listOf(Span(1, 2), Span(1, 1), Span(1, 1)), columns = 4)
        assertEquals(2, p.rows)
        assertEquals(0, p.lastRowFree)
    }

    @Test
    fun neverOverlapsAndKeepsOrder() {
        val rnd = Random(11)
        repeat(400) {
            val columns = rnd.nextInt(1, 9)
            val spans = List(rnd.nextInt(1, 30)) { Span(rnd.nextInt(1, 5), rnd.nextInt(1, 5)) }
            val p = packSpans(spans, columns)
            val seen = HashSet<Pair<Int, Int>>()
            var lastIndex = -1
            p.cells.forEach { c ->
                assertTrue(c.col + c.cols <= columns)
                for (r in c.row until c.row + c.rows) for (k in c.col until c.col + c.cols) assertTrue(seen.add(r to k))
                val index = c.row * columns + c.col
                assertTrue("order kept", index > lastIndex)
                lastIndex = index
            }
            assertEquals(p.cells.maxOf { it.row + it.rows }, p.rows)
        }
    }

    @Test
    fun wideWidgetGetsEnoughColumnsOnTheLandscapeCover() {
        val spans = listOf(Span(4, 1)) + List(8) { Span(1, 1) }
        val f = fitGrid(727.dp, 409.dp, spans, autoFit = true, fixedTile = 100.dp)
        assertTrue(f.fits)
        assertTrue("columns ${f.columns}", f.columns >= 4)
        assertEquals(4, f.packing.cells[0].cols)
    }
}

class DeckFitTest {
    @Test
    fun everyPageSharesTheFullestPagesGrid() {
        val small = List(3) { Span(1, 1) }
        val full = List(13) { Span(1, 1) }
        val deck = fitDeck(727.dp, 369.dp, listOf(small + Span(1, 1), full + Span(1, 1)), autoFit = true, fixedTile = 100.dp)
        val a = pageFit(small, deck, 409.dp)
        val b = pageFit(full, deck, 409.dp)
        assertEquals(a.columns, b.columns)
        assertEquals(a.tile, b.tile)
        assertTrue(b.fits)
    }

    @Test
    fun addingTheEditCellDoesNotChangeTheGrid() {
        val page = List(15) { Span(1, 1) }
        val deck = fitDeck(633.dp, 310.dp, listOf(page + Span(1, 1)), autoFit = true, fixedTile = 100.dp)
        val normal = pageFit(page, deck, 350.dp)
        val editing = pageFit(page + Span(1, 1), deck, 310.dp)
        assertEquals(normal.columns, editing.columns)
        assertEquals(normal.tile, editing.tile)
        assertEquals(normal.packing.cells, editing.packing.cells.take(page.size))
    }
}
