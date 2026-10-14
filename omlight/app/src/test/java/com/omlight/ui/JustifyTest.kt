package com.omlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JustifyTest {

    private fun cells(vararg aspects: Float) =
        aspects.mapIndexed { i, a -> Justify.Cell("k$i", a) }

    @Test
    fun empty_or_bad_input() {
        assertTrue(Justify.pack(emptyList(), 1000f, 200f).isEmpty())
        assertTrue(Justify.pack(cells(1f), 0f, 200f).isEmpty())
        assertTrue(Justify.pack(cells(1f), 1000f, 0f).isEmpty())
    }

    @Test
    fun landscapes_pack_few_per_row() {
        // 4:3 landscapes at width 1080, target row 270: each row holds
        // ~3 cells (3*(4/3)*270 = 1080).
        val a = 4f / 3f
        val rows = Justify.pack(cells(a, a, a, a, a, a), 1080f, 270f)
        assertEquals(2, rows.size)
        assertEquals(3, rows[0].cells.size)
        assertTrue(rows[0].justified)
        // Justified row fills exactly.
        val w = rows[0].cells.sumOf { it.aspect.toDouble() } * rows[0].height
        assertEquals(1080.0, w, 1.0)
    }

    @Test
    fun portraits_pack_more_per_row() {
        // 3:4 portraits: ~5-6 per row at the same width.
        val rows = Justify.pack(
            cells(0.75f, 0.75f, 0.75f, 0.75f, 0.75f, 0.75f, 0.75f, 0.75f),
            1080f, 270f,
        )
        assertTrue(rows.size >= 2)
        assertTrue(rows[0].cells.size >= 4)
        val w = rows[0].cells.sumOf { it.aspect.toDouble() } * rows[0].height
        assertEquals(1080.0, w, 1.0)
    }

    @Test
    fun short_last_row_stays_untouched() {
        // One landscape alone: ~360px at target height, far below 70%
        // of 1080 -> unjustified at target height.
        val rows = Justify.pack(cells(1.333f), 1080f, 270f)
        assertEquals(1, rows.size)
        assertTrue(!rows[0].justified)
        assertEquals(270f, rows[0].height, 0.01f)
    }

    @Test
    fun spacing_subtracts_from_budget() {
        val rows = Justify.pack(
            cells(1f, 1f, 1f, 1f), 1032f, 250f, spacing = 8f,
        )
        assertEquals(1, rows.size)
        // 4 squares + 3 gaps of 8 = 1032.
        val w = rows[0].cells.sumOf { it.aspect.toDouble() } * rows[0].height + 3 * 8
        assertEquals(1032.0, w, 1.0)
    }

    @Test
    fun wild_aspects_clamp() {
        // Panorama + sliver: clamped to [0.25, 4], no crash, no 0-height.
        val rows = Justify.pack(cells(10f, 0.01f, 1f), 1080f, 270f)
        assertTrue(rows.isNotEmpty())
        rows.forEach { assertTrue(it.height > 0) }
    }
}
