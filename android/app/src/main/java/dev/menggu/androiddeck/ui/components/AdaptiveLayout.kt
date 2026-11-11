package dev.menggu.androiddeck.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** A titled block of a screen; [id] keeps its state when other sections appear or disappear. */
class Section(val id: String, val content: @Composable () -> Unit)

/**
 * A scrolling screen whose sections are split into columns so they fit the screen height.
 * See [SectionColumns]; the screen scrolls only when even the widest split is too tall.
 */
@Composable
fun AdaptiveSections(
    sections: List<Section>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    minColumnWidth: Dp = 300.dp,
    maxColumnWidth: Dp = 560.dp,
    maxColumns: Int = 3,
    gap: Dp = 24.dp,
) {
    BoxWithConstraints(modifier) {
        val available = maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
            SectionColumns(
                sections = sections,
                availableHeight = available,
                minColumnWidth = minColumnWidth,
                maxColumnWidth = maxColumnWidth,
                maxColumns = maxColumns,
                gap = gap,
            )
        }
    }
}

/**
 * Lays [sections] out in the fewest columns whose tallest column fits [availableHeight], using at
 * most as many columns as the width allows (each at least [minColumnWidth]). Sections keep their
 * order (down the first column, then the next) and column heights are balanced. If no split fits,
 * the widest one is used and the result is taller than [availableHeight].
 *
 * Section heights come from intrinsic measurements, so sections must not contain lazy lists or
 * BoxWithConstraints. Must be measured with a bounded width.
 */
@Composable
fun SectionColumns(
    sections: List<Section>,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
    minColumnWidth: Dp = 300.dp,
    maxColumnWidth: Dp = 560.dp,
    maxColumns: Int = 3,
    gap: Dp = 24.dp,
) {
    Layout(
        content = { sections.forEach { s -> key(s.id) { Box { s.content() } } } },
        modifier = modifier,
    ) { measurables, constraints ->
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.minWidth, 0) {}
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val minCol = minColumnWidth.roundToPx()
        val maxCol = maxColumnWidth.roundToPx()
        val limit = availableHeight.roundToPx()
        val widest = ((width + gapPx) / (minCol + gapPx)).coerceIn(1, minOf(maxColumns, n))

        var plan: ColumnPlan? = null
        for (c in 1..widest) {
            val colW = minOf((width - gapPx * (c - 1)) / c, maxCol)
            val heights = measurables.map { it.maxIntrinsicHeight(colW) }
            val candidate = balancedSplit(heights, c, gapPx, colW)
            plan = candidate
            if (candidate.tallest <= limit) break
        }
        val chosen = plan!!
        val placeables = measurables.map { it.measure(Constraints(minWidth = chosen.columnWidth, maxWidth = chosen.columnWidth)) }
        val columns = chosen.starts.indices.map { k ->
            val end = chosen.starts.getOrElse(k + 1) { n }
            chosen.starts[k] until end
        }
        val colHeights = columns.map { r -> r.sumOf { placeables[it].height } + gapPx * (r.count() - 1) }
        val blockW = chosen.columnWidth * columns.size + gapPx * (columns.size - 1)
        val blockH = colHeights.max()
        layout(width, blockH) {
            var x = (width - blockW) / 2
            columns.forEach { r ->
                var y = 0
                r.forEach { i ->
                    placeables[i].placeRelative(x, y)
                    y += placeables[i].height + gapPx
                }
                x += chosen.columnWidth + gapPx
            }
        }
    }
}

internal class ColumnPlan(val columnWidth: Int, val starts: List<Int>, val tallest: Int)

/** Splits [heights] (in order) into [columns] non-empty runs, minimizing the tallest run. */
internal fun balancedSplit(heights: List<Int>, columns: Int, gap: Int, columnWidth: Int): ColumnPlan {
    val n = heights.size
    fun run(from: Int, to: Int): Int = (from until to).sumOf { heights[it] } + gap * (to - from - 1)
    // best[k][i]: tallest column when the first i sections fill k columns; cut[k][i]: start of the k-th.
    val inf = Int.MAX_VALUE
    val best = Array(columns + 1) { IntArray(n + 1) { inf } }
    val cut = Array(columns + 1) { IntArray(n + 1) }
    best[0][0] = 0
    for (k in 1..columns) {
        for (i in k..n) {
            for (j in (k - 1) until i) {
                if (best[k - 1][j] == inf) continue
                val v = maxOf(best[k - 1][j], run(j, i))
                if (v < best[k][i]) {
                    best[k][i] = v
                    cut[k][i] = j
                }
            }
        }
    }
    val starts = IntArray(columns)
    var i = n
    for (k in columns downTo 1) {
        starts[k - 1] = cut[k][i]
        i = cut[k][i]
    }
    return ColumnPlan(columnWidth, starts.toList(), best[columns][n])
}

/**
 * Equal square-ish cells (e.g. gauges) sized like the deck: the largest cell width, up to
 * [maxCellWidth], whose rows all fit [availableHeight]; partial last rows are centered. If nothing
 * fits, the smallest allowed cells are used and the grid is taller than [availableHeight].
 */
@Composable
fun FitGrid(
    count: Int,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 112.dp,
    maxCellWidth: Dp = 220.dp,
    gap: Dp = 12.dp,
    cell: @Composable (Int) -> Unit,
) {
    Layout(
        content = { repeat(count) { i -> Box(propagateMinConstraints = true) { cell(i) } } },
        modifier = modifier,
    ) { measurables, constraints ->
        if (count == 0) return@Layout layout(constraints.minWidth, 0) {}
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val limit = availableHeight.roundToPx()
        val minCell = minCellWidth.roundToPx()
        val maxCell = maxCellWidth.roundToPx()
        val widest = ((width + gapPx) / (minCell + gapPx)).coerceIn(1, count)

        fun rowsHeight(cols: Int, cellW: Int, h: (Measurable, Int) -> Int): Int =
            measurables.chunked(cols).sumOf { row -> row.maxOf { h(it, cellW) } } + gapPx * ((count + cols - 1) / cols - 1)

        var bestCols = widest
        var bestCell = -1
        for (c in 1..widest) {
            val cellW = minOf((width - gapPx * (c - 1)) / c, maxCell)
            val h = rowsHeight(c, cellW) { m, w -> m.maxIntrinsicHeight(w) }
            // Ties (capped cells) go to more columns: fewer, wider rows.
            if (h <= limit && cellW >= bestCell) {
                bestCols = c
                bestCell = cellW
            }
        }
        val cellW = if (bestCell > 0) bestCell else minOf((width - gapPx * (widest - 1)) / widest, maxCell)
        // Cells in a row get the row's height, so cards line up.
        val placeables = measurables.chunked(bestCols).flatMap { row ->
            val rowH = row.maxOf { it.maxIntrinsicHeight(cellW) }
            row.map { it.measure(Constraints(minWidth = cellW, maxWidth = cellW, minHeight = rowH)) }
        }
        val rows = placeables.chunked(bestCols)
        val totalH = rows.sumOf { r -> r.maxOf { it.height } } + gapPx * (rows.size - 1)
        layout(width, totalH) {
            var y = 0
            rows.forEach { r ->
                val rowW = cellW * r.size + gapPx * (r.size - 1)
                var x = (width - rowW) / 2
                val rowH = r.maxOf { it.height }
                r.forEach { pl ->
                    pl.placeRelative(x, y + (rowH - pl.height) / 2)
                    x += cellW + gapPx
                }
                y += rowH + gapPx
            }
        }
    }
}

/** Centers children with a gap that shrinks from [max] to [min] when the row gets narrow. */
fun centeredWithGap(max: Dp, min: Dp): Arrangement.Horizontal = object : Arrangement.Horizontal {
    override val spacing: Dp = min

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        val used = sizes.sum()
        val slots = (sizes.size - 1).coerceAtLeast(1)
        val gap = ((totalSize - used) / slots).coerceIn(min.roundToPx(), max.roundToPx())
        val rowW = used + gap * (sizes.size - 1)
        var x = (totalSize - rowW) / 2
        val order = if (layoutDirection == LayoutDirection.Rtl) sizes.indices.reversed() else sizes.indices
        order.forEach { i ->
            outPositions[i] = x
            x += sizes[i] + gap
        }
    }
}
