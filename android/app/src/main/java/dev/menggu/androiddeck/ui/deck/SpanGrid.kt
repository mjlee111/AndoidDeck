package dev.menggu.androiddeck.ui.deck

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal val GRID_GAP = 12.dp
internal val FIT_MIN_TILE = 72.dp
internal val FIT_MAX_TILE = 168.dp
internal const val FIT_MAX_COLUMNS = 8

/** An item's size in grid cells. */
internal data class Span(val cols: Int, val rows: Int)

/** Where an item sits, in cells (after narrowing to the grid width). */
internal data class Cell(val row: Int, val col: Int, val cols: Int, val rows: Int)

/**
 * [lastRowFree]: empty cells at the end of the last row when that row holds only one-row items
 * starting at column 0, so it can be centered; 0 otherwise.
 */
internal class Packing(val cells: List<Cell>, val rows: Int, val lastRowFree: Int)

/**
 * Places items in order, left to right and top to bottom. Each item goes to the first free spot at
 * or after the end of the previous one, so a tall item leaves room beside it for the next items and
 * the order is never shuffled. Items wider than the grid are narrowed to fit.
 */
internal fun packSpans(spans: List<Span>, columns: Int): Packing {
    val c = columns.coerceAtLeast(1)
    val taken = ArrayList<BooleanArray>()
    fun busy(r: Int, col: Int) = r < taken.size && taken[r][col]
    fun free(r: Int, col: Int, w: Int, h: Int) = (r until r + h).all { rr -> (col until col + w).none { busy(rr, it) } }
    val cells = ArrayList<Cell>(spans.size)
    var cursor = 0
    for (s in spans) {
        val w = s.cols.coerceIn(1, c)
        val h = s.rows.coerceAtLeast(1)
        var pos = cursor
        while (true) {
            val r = pos / c
            val col = pos % c
            if (col + w <= c && free(r, col, w, h)) {
                while (taken.size < r + h) taken.add(BooleanArray(c))
                for (rr in r until r + h) for (cc in col until col + w) taken[rr][cc] = true
                cells.add(Cell(r, col, w, h))
                cursor = pos + w
                break
            }
            pos++
        }
    }
    val rows = cells.maxOfOrNull { it.row + it.rows } ?: 0
    val last = rows - 1
    val inLast = cells.filter { it.row <= last && it.row + it.rows - 1 >= last }.sortedBy { it.col }
    val centerable = inLast.isNotEmpty() && inLast.all { it.row == last && it.rows == 1 } &&
        inLast.first().col == 0 && inLast.zipWithNext().all { (a, b) -> b.col == a.col + a.cols }
    val free = if (centerable) c - inLast.sumOf { it.cols } else 0
    return Packing(cells, rows, free)
}

/** Grid geometry: [fits] means every item is visible without scrolling. */
internal data class GridFit(
    val columns: Int,
    val rows: Int,
    val tile: Dp,
    val width: Dp,
    val height: Dp,
    val fits: Boolean,
    val packing: Packing,
)

internal fun fitGrid(width: Dp, height: Dp, cells: Int, autoFit: Boolean, fixedTile: Dp): GridFit =
    fitGrid(width, height, List(cells.coerceAtLeast(1)) { Span(1, 1) }, autoFit, fixedTile)

internal fun fitGrid(width: Dp, height: Dp, spans: List<Span>, autoFit: Boolean, fixedTile: Dp): GridFit =
    pageFit(spans, fitDeck(width, height, listOf(spans), autoFit, fixedTile), height)

/** One grid for a whole deck: every page uses the same columns and cell size. */
internal data class DeckFit(val columns: Int, val tile: Dp)

private fun span(count: Int, tile: Dp) = tile * count + GRID_GAP * (count - 1)

/**
 * Auto-fit picks the column count that gives the largest square cells while the fullest page stays
 * on screen (stream-deck style); grids wide enough for the widest item are preferred. Every page
 * then uses that grid, so buttons keep their size and place across pages. When even
 * [FIT_MIN_TILE] cells do not fit, or a fixed size is chosen, it falls back to the largest cells
 * that fill the width (pages scroll).
 */
internal fun fitDeck(width: Dp, height: Dp, pages: List<List<Span>>, autoFit: Boolean, fixedTile: Dp): DeckFit {
    val all = pages.map { it.ifEmpty { listOf(Span(1, 1)) } }.ifEmpty { listOf(listOf(Span(1, 1))) }
    val widest = all.maxOf { page -> page.maxOf { it.cols } }.coerceIn(1, FIT_MAX_COLUMNS)
    val maxUseful = all.maxOf { page -> page.sumOf { it.cols } }.coerceIn(widest, FIT_MAX_COLUMNS)
    if (autoFit) {
        val areaAspect = width / height
        // Lower is better: mismatch between the block's and the screen's proportions.
        fun shapeCost(c: Int, rows: Int, tile: Dp) = abs(ln((span(c, tile) / span(rows, tile)) / areaAspect))
        for (range in listOf(widest..maxUseful, 1 until widest)) {
            var best: Triple<Int, Int, Dp>? = null
            for (c in range) {
                val rows = all.maxOf { packSpans(it, c).rows }
                val tile = minOf((width - GRID_GAP * (c - 1)) / c, (height - GRID_GAP * (rows - 1)) / rows, FIT_MAX_TILE)
                if (tile < FIT_MIN_TILE) continue
                val cur = best
                // Biggest cells win; when capped or nearly equal, prefer the screen-shaped block.
                if (cur == null || tile > cur.third + 0.5.dp ||
                    (tile > cur.third - 0.5.dp && shapeCost(c, rows, tile) < shapeCost(cur.first, cur.second, cur.third))
                ) {
                    best = Triple(c, rows, tile)
                }
            }
            best?.let { return DeckFit(it.first, it.third) }
        }
    }
    val minTile = if (autoFit) FIT_MIN_TILE else fixedTile
    val c = ((width + GRID_GAP) / (minTile + GRID_GAP)).toInt().coerceIn(1, FIT_MAX_COLUMNS)
    return DeckFit(c, (width - GRID_GAP * (c - 1)) / c)
}

/** One page on the deck's grid; [GridFit.fits] says whether it needs to scroll. */
internal fun pageFit(spans: List<Span>, deck: DeckFit, height: Dp): GridFit {
    val packing = packSpans(spans.ifEmpty { listOf(Span(1, 1)) }, deck.columns)
    val h = span(packing.rows, deck.tile)
    return GridFit(deck.columns, packing.rows, deck.tile, span(deck.columns, deck.tile), h, fits = h <= height + 0.5.dp, packing)
}

/**
 * Places its children (one per [GridFit.packing] cell, same order) on the cell grid. A last row of
 * one-row items is centered.
 */
@Composable
internal fun SpanGrid(fit: GridFit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, _ ->
        val tile = fit.tile.toPx()
        val gap = GRID_GAP.toPx()
        val cells = fit.packing.cells
        fun extent(n: Int) = (tile * n + gap * (n - 1)).roundToInt()
        val placeables = measurables.mapIndexed { i, m ->
            val cell = cells.getOrNull(i) ?: Cell(0, 0, 1, 1)
            m.measure(Constraints.fixed(extent(cell.cols), extent(cell.rows)))
        }
        val shift = fit.packing.lastRowFree * (tile + gap) / 2f
        layout(extent(fit.columns), extent(fit.rows.coerceAtLeast(1))) {
            placeables.forEachIndexed { i, pl ->
                val cell = cells.getOrNull(i) ?: return@forEachIndexed
                val dx = if (cell.row == fit.rows - 1 && cell.rows == 1) shift else 0f
                pl.place((cell.col * (tile + gap) + dx).roundToInt(), (cell.row * (tile + gap)).roundToInt())
            }
        }
    }
}
