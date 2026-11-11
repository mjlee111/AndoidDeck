package dev.menggu.androiddeck.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BalancedSplitTest {
    private fun tallest(heights: List<Int>, starts: List<Int>, gap: Int): Int =
        starts.indices.maxOf { k ->
            val end = starts.getOrElse(k + 1) { heights.size }
            (starts[k] until end).sumOf { heights[it] } + gap * (end - starts[k] - 1)
        }

    @Test
    fun oneColumnStacksEverything() {
        val plan = balancedSplit(listOf(100, 50, 30), columns = 1, gap = 10, columnWidth = 300)
        assertEquals(listOf(0), plan.starts)
        assertEquals(200, plan.tallest)
    }

    @Test
    fun everyColumnGetsAtLeastOneSection() {
        val plan = balancedSplit(listOf(10, 10, 10), columns = 3, gap = 5, columnWidth = 100)
        assertEquals(listOf(0, 1, 2), plan.starts)
        assertEquals(10, plan.tallest)
    }

    @Test
    fun audioSectionsSplitWhereTheTallestColumnIsLowest() {
        // Measured section heights (px) from the Audio screen before the compact redesign.
        val plan = balancedSplit(listOf(844, 258, 590, 163), columns = 2, gap = 73, columnWidth = 900)
        assertEquals(listOf(0, 1), plan.starts)
        assertEquals(1157, plan.tallest)
    }

    @Test
    fun matchesBruteForceAndKeepsOrder() {
        val rnd = Random(7)
        repeat(300) {
            val n = rnd.nextInt(1, 7)
            val heights = List(n) { rnd.nextInt(20, 400) }
            val cols = rnd.nextInt(1, n + 1)
            val gap = rnd.nextInt(0, 30)
            val plan = balancedSplit(heights, cols, gap, columnWidth = 300)
            assertEquals(cols, plan.starts.size)
            assertEquals(0, plan.starts.first())
            assertTrue(plan.starts.zipWithNext().all { (a, b) -> a < b })
            assertEquals(tallest(heights, plan.starts, gap), plan.tallest)
            // Brute force over all ordered splits into `cols` non-empty runs.
            var best = Int.MAX_VALUE
            fun search(from: Int, left: Int, acc: List<Int>) {
                if (left == 0) {
                    if (from == n) best = minOf(best, tallest(heights, acc, gap))
                    return
                }
                for (next in from until n) search(next + 1, left - 1, acc + from)
            }
            search(0, cols, emptyList())
            assertEquals(best, plan.tallest)
        }
    }
}
