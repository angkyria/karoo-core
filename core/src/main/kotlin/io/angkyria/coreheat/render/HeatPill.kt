package io.angkyria.coreheat.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The HUD's pill: a rounded lozenge centred in the header row between CORE and SKIN, showing the
 * thermometer, one square per band with everything up to the current one lit, and the value.
 *
 * Follows the zone pill of karoo-bignum's HUD (github.com/smartycoder/karoo-bignum, Apache-2.0),
 * which this HUD is modelled on. Squares rather than a filled bar because a band is a thing to
 * count at a glance on a moving bike; a fill has to be judged against a remembered colour.
 */
object HeatPill {

    /**
     * How tall the value's INK is, as a share of the pill's height -- the digits themselves, not
     * the text size that produces them, so the value comes out the same height whatever the face
     * measures. bignum's figure, tuned on a Karoo 3.
     */
    private const val TEXT_INK_FRACTION = 0.66f

    /**
     * The icon's box, as a share of the pill's height. Larger than the text, as in a header: the
     * vector carries its own margin inside its viewport, so its ink is smaller than this.
     */
    private const val ICON_HEIGHT_FRACTION = 0.74f

    /** A square's side, as a share of the pill's height. */
    private const val SEGMENT_FRACTION = 0.38f

    /** Gap between two squares, as a share of the pill's height. */
    private const val SEGMENT_GAP_FRACTION = 0.09f

    /** Gap between the pill's three groups -- icon, squares, value -- as a share of its height. */
    private const val GROUP_GAP_FRACTION = 0.17f

    /** The pill's ground, drawn over an opaque copy of the card. */
    private const val TRACK_ON_LIGHT = 0x1A000000
    private const val TRACK_ON_DARK = 0x24FFFFFF

    /** A square that is not lit, which has to read against the pill's ground, not the card's. */
    private const val EMPTY_SEGMENT_ON_LIGHT = 0x24000000
    private const val EMPTY_SEGMENT_ON_DARK = 0x33FFFFFF

    /**
     * How wide [render] will draw, so the HUD can decide whether it fits before asking for it.
     * [segments] of zero is a pill without squares: the solid one, or a metric with no bands.
     */
    fun width(context: Context, heightPx: Int, text: String, segments: Int, hasIcon: Boolean): Int {
        if (heightPx <= 0) return 0
        val h = heightPx.toFloat()
        val pad = FieldRenderer.edgePadding(context).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = FieldRenderer.typefaceFor(context) }
        valueTextSize(paint, h)
        var w = 2 * pad + paint.measureText(text)
        if (hasIcon) w += h * ICON_HEIGHT_FRACTION + h * GROUP_GAP_FRACTION
        if (segments > 0) {
            w += segments * h * SEGMENT_FRACTION +
                (segments - 1) * h * SEGMENT_GAP_FRACTION +
                h * GROUP_GAP_FRACTION
        }
        return ceil(w).toInt()
    }

    /**
     * Sets the text size that makes the digits' ink [TEXT_INK_FRACTION] of [h]. Corrected three
     * times rather than once: ink bounds come back in whole pixels and hinting steps them, so a
     * single ratio lands close and not on.
     */
    private fun valueTextSize(paint: Paint, h: Float) {
        val target = h * TEXT_INK_FRACTION
        val ink = Rect()
        var size = target
        repeat(3) {
            paint.textSize = size
            paint.getTextBounds("0", 0, 1, ink)
            // A face that measures nothing would make this a division by zero.
            if (ink.height() <= 0) return
            size *= target / ink.height()
        }
        paint.textSize = size
    }

    /**
     * Draws the pill: rounded ground, [icon], [segments] squares of which [lit] are filled, and
     * [text].
     *
     * [solid] drops the squares and takes [color] as the pill's ground instead -- what the squares
     * degrade into on a tile too narrow for them, where the colour still names the band. A null
     * [color] is a value with no colour of its own (training load, heat colours off, nothing to
     * show): lit squares take the plain ink, and a solid pill keeps the neutral ground.
     */
    fun render(
        context: Context,
        heightPx: Int,
        lit: Int,
        segments: Int,
        solid: Boolean,
        color: Int?,
        text: String,
        icon: Drawable?,
        isNight: Boolean,
        /**
         * The bitmap's height: the whole header row, so the pill arrives already placed in it and
         * the layout needs no margin -- which RemoteViews cannot set before API 31 anyway.
         */
        rowHeightPx: Int = heightPx,
        /** Where in that row the pill's middle sits: the labels' centre line. */
        centreY: Float = rowHeightPx / 2f,
    ): Bitmap? {
        if (heightPx <= 0 || rowHeightPx < heightPx) return null
        // One square count, used to measure and to draw: a solid pill measured with squares would
        // be a lozenge padded out with empty space.
        val squares = if (solid) 0 else segments
        val widthPx = width(context, heightPx, text, squares, icon != null)
        if (widthPx <= 0) return null

        val bitmap = Bitmap.createBitmap(widthPx, rowHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        // Whole pixels, and kept inside the row.
        val y = (centreY - h / 2f).roundToInt().coerceIn(0, rowHeightPx - heightPx).toFloat()
        val pad = FieldRenderer.edgePadding(context).toFloat()
        val plainInk = Theme.textColor(isNight)
        val filled = solid && color != null

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // An OPAQUE ground first, then the pill's own colour over it. The track is only 10-14%
        // ink, and the pill sits over the divider and, under a zone fill, over both halves'
        // colour: without this it is a window onto all of them.
        val ground = RectF(0f, y, w, y + h)
        paint.color = Theme.cardColor(isNight)
        canvas.drawRoundRect(ground, h / 2f, h / 2f, paint)
        paint.color = if (filled) color!! else if (isNight) TRACK_ON_DARK else TRACK_ON_LIGHT
        canvas.drawRoundRect(ground, h / 2f, h / 2f, paint)

        // On a filled pill the ground IS the band colour, so the ink is whatever reads on it --
        // the same call that picks a zone-filled field's number colour, so the two cannot disagree.
        val ink = if (filled) ZoneColors.onColor(color!!) else plainInk
        var x = pad

        icon?.mutate()?.apply {
            // A square box built by adding the size to a rounded origin: truncating four floats on
            // their own came out a pixel off square in bignum, and stretched the vector.
            val size = (h * ICON_HEIGHT_FRACTION).roundToInt()
            val left = x.roundToInt()
            val top = (y + (h - size) / 2f).roundToInt()
            setTint(ink)
            setBounds(left, top, left + size, top + size)
            draw(canvas)
            x += size + h * GROUP_GAP_FRACTION
        }

        if (squares > 0) {
            val side = h * SEGMENT_FRACTION
            val gap = h * SEGMENT_GAP_FRACTION
            val top = y + (h - side) / 2f
            // Rounded enough to belong to the pill, square enough to still be counted.
            val r = side * 0.25f
            val empty = if (isNight) EMPTY_SEGMENT_ON_DARK else EMPTY_SEGMENT_ON_LIGHT
            for (i in 0 until squares) {
                // Every lit square in the CURRENT band's colour, not its own band's: the pill
                // answers "which band am I in", and a rainbow of the ones below competes with it.
                paint.color = if (i < lit) color ?: plainInk else empty
                val left = x + i * (side + gap)
                canvas.drawRoundRect(RectF(left, top, left + side, top + side), r, r, paint)
            }
            x += squares * side + (squares - 1) * gap + h * GROUP_GAP_FRACTION
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FieldRenderer.typefaceFor(context)
            isSubpixelText = true
            this.color = ink
        }
        valueTextSize(textPaint, h)
        val digits = Rect()
        textPaint.getTextBounds("0", 0, 1, digits)
        canvas.drawText(text, x, y + FieldRenderer.baselineFor(heightPx, digits.height(), digits.top), textPaint)
        return bitmap
    }
}
