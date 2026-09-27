package io.angkyria.coreheat.fields

import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [halfConfig] is the arithmetic each half's number is sized from: a pixel off and every number
 * in the tile is scaled against a budget that does not match the space it lands in.
 */
class HudLayoutTest {

    private fun config(width: Int, preview: Boolean = false) = ViewConfig(
        gridSize = 2 to 3,
        viewSize = width to 100,
        textSize = 40,
        alignment = ViewConfig.Alignment.RIGHT,
        boundariesEnabled = true,
        preview = preview,
    )

    private fun halves(tile: Int, divider: Int) =
        halfConfig(config(tile), left = true, dividerPx = divider).viewSize.first to
            halfConfig(config(tile), left = false, dividerPx = divider).viewSize.first

    @Test fun `even tile width splits with nothing lost to the divider`() {
        val (left, right) = halves(478, 1)
        assertEquals(478, left + right + 1)
    }

    @Test fun `odd tile width splits with nothing lost either`() {
        // The case a second `usable / 2` would get wrong: the odd pixel lands in one half.
        val (left, right) = halves(479, 1)
        assertEquals(479, left + right + 1)
    }

    @Test fun `any divider width still adds back up`() {
        for (divider in listOf(0, 1, 2, 7)) {
            for (tile in listOf(238, 239)) {
                val (left, right) = halves(tile, divider)
                assertEquals(tile, left + right + divider)
            }
        }
    }

    @Test fun `height and preview carry through to both halves`() {
        val parent = config(478, preview = true)
        for (left in listOf(true, false)) {
            val half = halfConfig(parent, left, dividerPx = 1)
            assertEquals(100, half.viewSize.second)
            assertEquals(true, half.preview)
        }
    }

    @Test fun `both halves are centred whatever the page's alignment`() {
        for (alignment in ViewConfig.Alignment.entries) {
            val parent = config(478).copy(alignment = alignment)
            assertEquals(ViewConfig.Alignment.CENTER, halfConfig(parent, left = true, dividerPx = 1).alignment)
            assertEquals(ViewConfig.Alignment.CENTER, halfConfig(parent, left = false, dividerPx = 1).alignment)
        }
    }

    // ── pillLayout ─────────────────────────────────────────────────────────
    // Widths as the Karoo 2 measures them: halves of a 478px tile, labels with their padding.

    private fun layout(
        tile: Int,
        squaresPill: Int,
        solidPill: Int,
        leftLabel: Int = 88,
        rightLabel: Int = 79,
    ): PillLayout {
        val divider = 1
        val left = (tile - divider) / 2
        return pillLayout(
            leftHalf = left,
            rightHalf = tile - divider - left,
            dividerPx = divider,
            leftLabel = leftLabel,
            rightLabel = rightLabel,
            squaresPill = squaresPill,
            solidPill = solidPill,
            edgePx = 9,
        )
    }

    @Test fun `a wide tile keeps both labels and the squares`() {
        assertEquals(PillLayout(labels = true, style = PillStyle.SQUARES), layout(478, squaresPill = 140, solidPill = 90))
    }

    @Test fun `the squares go before the labels do`() {
        assertEquals(PillLayout(labels = true, style = PillStyle.SOLID), layout(478, squaresPill = 180, solidPill = 90))
    }

    @Test fun `a half-width tile gives the whole row to the pill`() {
        assertEquals(PillLayout(labels = false, style = PillStyle.SQUARES), layout(238, squaresPill = 140, solidPill = 90))
        assertEquals(PillLayout(labels = false, style = PillStyle.SOLID), layout(160, squaresPill = 150, solidPill = 90))
    }

    @Test fun `a tile too narrow for any pill keeps its labels`() {
        assertEquals(PillLayout(labels = true, style = null), layout(100, squaresPill = 150, solidPill = 90))
    }

    @Test fun `the clearance is measured against the wider label on either side`() {
        // 239 + 1 - 88 = 152 on the left, 238 + 1 - 79 = 160 on the right: the left decides.
        assertEquals(PillStyle.SQUARES, layout(478, squaresPill = 152, solidPill = 90).style)
        assertEquals(PillStyle.SOLID, layout(478, squaresPill = 153, solidPill = 90).style)
    }
}
