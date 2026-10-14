package com.omlight.ui

/**
 * Justified-grid row packing (Flickr-style).
 *
 * Cells keep their aspect ratio; each row stretches to exactly the
 * available width. Row height floats: rows of wide (landscape) photos
 * come out short with few cells, rows of tall (portrait) photos come
 * out tall with more cells — no fixed column count anywhere.
 *
 * Algorithm: greedily append cells; when the row's width at
 * [targetHeight] reaches the container [width], the row is full: it
 * justifies to exactly [width] and a new row starts. The final row is
 * left unjustified at [targetHeight] unless it fills >= 70% of the
 * width. Row height floats around the target: landscape rows come out
 * near-target with few cells, portrait rows near-target with more.
 *
 * Pure + unit-tested: no Compose, no Android.
 */
object Justify {

    data class Cell(val key: String, val aspect: Float)

    data class Row(val cells: List<Cell>, val height: Float, val justified: Boolean)

    /**
     * Pack [cells] into rows for a container [width] (px, padding removed).
     *
     * @param targetHeight preferred row height (px). Rows justify around it.
     * @param spacing px between cells (subtracted from the width budget).
     */
    fun pack(
        cells: List<Cell>,
        width: Float,
        targetHeight: Float,
        spacing: Float = 0f,
    ): List<Row> {
        if (cells.isEmpty() || width <= 0 || targetHeight <= 0) return emptyList()
        val rows = ArrayList<Row>()
        var row = ArrayList<Cell>()
        var sumAspect = 0f

        fun flushRow(justify: Boolean) {
            if (row.isEmpty()) return
            val gaps = spacing * (row.size - 1).coerceAtLeast(0)
            val h = if (justify) {
                (width - gaps) / sumAspect.coerceAtLeast(0.01f)
            } else {
                targetHeight
            }
            rows.add(Row(row.toList(), h, justify))
            row = ArrayList()
            sumAspect = 0f
        }

        for (c in cells) {
            val a = c.aspect.coerceIn(0.25f, 4f)
            row.add(c.copy(aspect = a))
            sumAspect += a
            // Row full when it reaches the container width at target height.
            val gaps = spacing * (row.size - 1).coerceAtLeast(0)
            if (sumAspect * targetHeight + gaps >= width) {
                flushRow(justify = true)
            }
        }
        // Last row: justify only if it fills reasonably (>= 70% of width
        // at target height); else left-align at target height.
        if (row.isNotEmpty()) {
            val gaps = spacing * (row.size - 1).coerceAtLeast(0)
            val fillW = sumAspect * targetHeight + gaps
            flushRow(justify = fillW >= width * 0.7f)
        }
        return rows
    }
}
