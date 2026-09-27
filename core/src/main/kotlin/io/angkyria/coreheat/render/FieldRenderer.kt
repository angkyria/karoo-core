package io.angkyria.coreheat.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.ceil
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import io.angkyria.coreheat.BuildConfig
import io.angkyria.coreheat.R
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.ViewConfig.Alignment

/**
 * Renders a field -- its icon, short label and primary number -- to a Bitmap in DIN 1451
 * Mittelschrift, and pushes it into the RemoteViews via setImageViewBitmap.
 * The unit suffix (°C, ...) is intentionally not drawn.
 *
 * The header is ours rather than Karoo's: the field sends UpdateGraphicConfig(showHeader =
 * false), which buys the whole tile and lets the label be a short form ("HSI") instead of
 * Karoo's uppercased displayName ("HEAT - STRAIN INDEX"). Label and number share the one font.
 *
 * Neither bitmap is sized to [ViewConfig.viewSize]: on the Karoo the reported view size does
 * not match the actual ImageView, so anything measured against it gets rescaled away or
 * clipped. The number is a tight, fixed-aspect box that a fit* scaleType blows up to fill the
 * real view; the header is drawn at its natural size into a wrap_content view. Both are
 * pinned to the edge [ViewConfig.alignment] asks for by the layout, not by padding baked
 * into the bitmap.
 */
object FieldRenderer {

    // Ink height of the digits, measured on the digits rather than on fontMetrics:
    // ascent/descent reserve room for accents and descenders that digits never use.
    private const val REFERENCE_GLYPHS = "0123456789"

    // Default width budget. Keeping it fixed keeps the bitmap's aspect ratio constant, so a
    // field ends up at the same on-screen text size regardless of how many characters its
    // current value has. Wider values shrink to fit; a field that is routinely wider declares
    // its own via BaseNumericField.widthTemplate.
    const val DEFAULT_WIDTH_TEMPLATE = "00.0"

    // The size the first measurement is taken at, before it is scaled to the view -- see
    // [measure]. Not a drawing size: nothing is drawn at 200 unless a tile happens to want it.
    private const val TEXT_SIZE = 200f

    // Guard rails on the derived size. The floor keeps a nonsense viewSize from producing a
    // one-pixel bitmap; the ceiling bounds the raster on a tile larger than any this screen has.
    private const val MIN_TEXT_SIZE = 12f
    private const val MAX_TEXT_SIZE = 400f

    // The header is drawn at a fixed dp size into its own unscaled ImageView, so it comes out
    // the same on every field size instead of riding along with the number's scale factor.
    private const val LABEL_HEIGHT_DP = 11.07f

    // What LABEL_HEIGHT_DP is measured against. A capital with flat top and bottom: "O" or "S"
    // would carry the overshoot rounded glyphs are drawn with, and any label's own ink carries
    // whatever ascenders, descenders and digits it happens to hold.
    private const val CAP_REFERENCE = "H"

    /**
     * Pixels of the label's top clearance handed down to the number below it.
     *
     * The header's own height IS the row every number reserves -- render() applies it as the
     * number's top view padding -- so taking it off the top inset does two things at once: the
     * label sits this much higher, and the number gets exactly the space the label gave up.
     * Anything that wants to change one without the other is in the wrong place.
     *
     * Raw pixels rather than dp because it is a nudge, not a measurement: the label was sitting
     * two pixels lower than it looked right at, on the screen this is drawn for.
     */
    private const val LABEL_LIFT_PX = 2

    /**
     * Pixels taken off the clearance BELOW the label and BELOW the number, and handed to the
     * number's box.
     *
     * Measured on a Karoo 3 before this existed: a 124px tile spent 44px on the label row and
     * 8px on the bottom edge, leaving 72 for the digits. The number was not floating in that
     * box -- the diagnostic showed ink exactly equal to fullBox, so it already filled every
     * pixel it was given. The only way to draw it taller is to give it more, which means taking
     * it from the two gaps around it.
     *
     * Both are cut by the same amount so the number stays visually centred between the label
     * and the tile edge; cutting only one would slide it towards that side.
     */
    private const val VALUE_GAIN_PX = 5

    private const val ICON_SCALE = 1.4f
    private const val ICON_GAP_DP = 3f

    /**
     * Breathing room around a field's content, in pixels.
     *
     * Applied as view padding rather than as a margin inside the bitmap: the bitmap is scaled by
     * a factor that differs with field size, so a margin drawn into it comes out a different
     * width in every field. Padding is in view space, so the gap is the same everywhere.
     *
     * From R.dimen.field_edge_padding, read with getDimensionPixelSize so code and inflater
     * round identically. It used to be a `5f` here multiplied by density and truncated, with a
     * matching literal `5dp` in the layout and a comment promising the two agreed.
     */
    internal fun edgePadding(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.field_edge_padding)

    private const val ICON_COLOR = 0xFF10B981.toInt()

    /** Matches the corner radius Karoo draws its own field cards with. */
    private const val CARD_RADIUS_DP = 10f

    /**
     * Size of the secondary part relative to the primary. Tune by eye on the device: it trades
     * how much height the primary gains against whether the secondary is still readable.
     */
    internal const val SECONDARY_SCALE = 0.5f

    /**
     * The header depends on nothing that changes between samples, but render() runs on every
     * one, so it is drawn once per distinct field and reused. Alignment is not part of the key:
     * the bitmap is content-sized, so the layout does the aligning. Both colours are, because
     * on a zone fill they follow the fill -- without them in the key a field crossing into the
     * next zone would be served the previous zone's header.
     */
    private data class HeaderKey(
        val label: String,
        val iconRes: Int,
        val labelColor: Int,
        val iconColor: Int,
    )

    private val headerCache = ConcurrentHashMap<HeaderKey, Bitmap>()

    // IntArray, so iterating allocates neither a list nor boxed ids.
    private val BITMAP_IDS = intArrayOf(R.id.bitmap_start, R.id.bitmap_center, R.id.bitmap_end)
    private val HEADER_IDS = intArrayOf(R.id.header_start, R.id.header_center, R.id.header_end)

    /**
     * The one typeface, loaded once. Building one is a native call that allocates, and render()
     * runs on every sample of every field, so without this a ride would churn through thousands
     * of identical Typefaces.
     */
    @Volatile private var loadedTypeface: Typeface? = null

    private data class MetricsKey(
        val primary: String,
        val secondary: String,
        val boxWidth: Int,
        val boxHeight: Int,
    )

    /**
     * The bitmap a field draws into, and the numbers needed to place text in it. All of it
     * follows from the width template, which does not change between samples,
     * so measuring it on every one shaped twenty glyphs and measured two strings for an answer
     * that was already known.
     *
     * [textSize] is not always [TEXT_SIZE]: see [measure].
     */
    private class Metrics(
        val textSize: Float,
        val width: Int,
        val height: Int,
        val digitTop: Int,
        val templateWidth: Float,
    )

    private val metricsCache = ConcurrentHashMap<MetricsKey, Metrics>()

    /**
     * Null when the font or template is degenerate enough to leave nothing to draw.
     *
     * The raster is sized to the box the number will actually occupy, so the fit* scaleType that
     * puts it on screen scales it by 1.0 and resamples nothing. That is both the cheapest and
     * the sharpest option, and it is the only one that is right for every tile: Karoo hands out
     * views from 238x142 to 478x288, a two-fold range of heights, and a single raster size is
     * necessarily wasteful at one end and blurry at the other. Measured on a Karoo 3, the old
     * fixed size was drawing 1.7x too many pixels on a half tile and stretching 1.6x on the
     * largest one.
     *
     * [TEXT_SIZE] survives only as the size the first measurement is taken at, and as the
     * fallback when [ViewConfig.viewSize] reports nothing usable.
     */
    private fun measure(
        number: Paint,
        secondary: Paint,
        templatePrimary: String,
        templateSecondary: String,
        boxWidth: Int,
        boxHeight: Int,
    ): Metrics? {
        val digits = Rect()
        number.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, digits)
        var width = number.measureText(templatePrimary) + secondary.measureText(templateSecondary)
        if (digits.height() <= 0 || width <= 0f) return null

        // What fit* would scale the reference raster by. Pre-applying it leaves nothing for the
        // ImageView to do; whichever of the two bounds is tighter is the one that decides the
        // on-screen size, exactly as before.
        var size = TEXT_SIZE
        if (boxWidth > 0 && boxHeight > 0) {
            val fit = minOf(boxWidth / width, boxHeight / digits.height().toFloat())
            size = (TEXT_SIZE * fit).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            number.textSize = size
            secondary.textSize = size * SECONDARY_SCALE
            number.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, digits)
            width = number.measureText(templatePrimary) + secondary.measureText(templateSecondary)
            if (digits.height() <= 0 || width <= 0f) return null
        }
        // ceil, not truncate: measureText returns an advance, and rounding it down shaves a
        // column off the outermost glyph.
        return Metrics(size, ceil(width).toInt(), digits.height(), digits.top, width)
    }

    private fun typefaceFor(context: Context): Typeface =
        loadedTypeface ?: (runCatching { context.resources.getFont(R.font.din_1451_mittelschrift) }
            .getOrDefault(Typeface.DEFAULT_BOLD))
            .also { loadedTypeface = it }

    /**
     * How much a value has to shrink to fit the room it has, as a factor of the size the width
     * template was fitted at. 1 leaves it alone.
     *
     * The room is the tile, not the template. The template is what holds a field's size steady
     * as digits come and go, and while width is what limits the fit the two are the same number
     * -- but once height is what limits it, the template is narrower than the tile, and
     * measuring the overflow against it shrank values that had room to spare: a 4-digit value
     * lost a quarter of its height on a tile with 66px of unused width beside it.
     *
     * Falls back to the template where that is the wider of the two, which is the degenerate
     * tile [measure] clamps rather than fits.
     */
    internal fun shrinkFactor(naturalWidth: Float, templateWidth: Float, boxWidth: Int): Float {
        val room = maxOf(templateWidth, boxWidth.toFloat())
        return if (naturalWidth > room && naturalWidth > 0f) room / naturalWidth else 1f
    }

    /**
     * Baseline that centres ink of [inkHeight] (whose bounds start at [inkTop], negative above
     * the baseline) inside a box of [boxHeight].
     *
     * The box keeps the full-size height even when a wide value shrinks the text, so the two
     * are not the same number. Returning -inkTop, which is right only when they match, left the
     * whole difference below the glyphs and made shrunk values ride high in the tile.
     */
    internal fun baselineFor(boxHeight: Int, inkHeight: Int, inkTop: Int): Float =
        (boxHeight - inkHeight) / 2f - inkTop

    /**
     * Puts the font on [this]. A Paint copy (the secondary number) inherits the typeface, so the
     * superscript comes out in step with the primary.
     *
     * The DIN digits are all one width except a narrower "1", and the font carries no `tnum`
     * table to even that out, so a value can nudge sideways when a 1 comes or goes.
     */
    private fun Paint.applyFont(context: Context) {
        typeface = typefaceFor(context)
    }

    fun render(
        context: Context,
        views: RemoteViews,
        config: ViewConfig,
        label: String,
        iconRes: Int,
        templatePrimary: String,
        templateSecondary: String,
        primary: String,
        secondary: String,
        primaryColor: Int,
        /** Fill for the whole field, or null to leave Karoo's own background showing. */
        backgroundColor: Int? = null,
    ) {
        // The header comes first because the number's box is what it leaves behind. It is cached
        // and depends on nothing the number does, so this is a reorder rather than extra work.
        val onBackground = backgroundColor?.let { ZoneColors.onColor(it) }
        val header = header(
            context, label, iconRes,
            labelColor = onBackground ?: Theme.textColor(context),
            iconColor = onBackground ?: ICON_COLOR,
        )
        val pad = edgePadding(context)

        // What the number actually gets on screen, from the view Karoo reports. The layout puts
        // the header above it and pads the other three sides; see numeric_field.xml.
        val (viewWidth, viewHeight) = config.viewSize
        val boxWidth = viewWidth - 2 * pad
        // The header always takes its own height off the top, and the number always gets that
        // back as VIEW padding below. That pairing is what makes the clearance survive a
        // [ViewConfig.viewSize] that does not match the view -- see [render]'s note on it.
        val fullBox = viewHeight - valueBottomPad(context) - header.height

        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            applyFont(context)
            this.textSize = TEXT_SIZE
            color = primaryColor
            isSubpixelText = true
        }
        val secondaryPaint = Paint(numberPaint).apply { textSize = TEXT_SIZE * SECONDARY_SCALE }

        // Tight box: a fixed template width keeps the aspect ratio -- and so the on-screen text
        // size -- constant no matter how many characters the current value has.
        //
        // The paints are reset to TEXT_SIZE before each attempt: measure() scales the paints it
        // is handed, so a call must not start from the size a previous one left on them.
        fun measured(boxHeight: Int): Metrics? {
            // Reset BEFORE the cache lookup, not between it and measure(): on a cache hit the
            // paints keep whatever the previous call left on them, and only textSize is written
            // back afterwards. Resetting unconditionally makes both paths start from the same
            // state whatever else measure() may come to touch.
            numberPaint.textSize = TEXT_SIZE
            secondaryPaint.textSize = TEXT_SIZE * SECONDARY_SCALE
            val key = MetricsKey(templatePrimary, templateSecondary, boxWidth, boxHeight)
            metricsCache[key]?.let { return it }
            return measure(
                numberPaint, secondaryPaint, templatePrimary, templateSecondary, boxWidth, boxHeight,
            )?.also { metricsCache[key] = it }
        }

        val metrics = measured(fullBox) ?: return
        // The header's own height, always, and applied below as VIEW padding rather than as a
        // reservation computed from the reported view size.
        //
        // ViewConfig.viewSize cannot be trusted for this: on a Karoo 3 map page it measured
        // 239x228 reported against a tile actually drawn at ~237x165, so a reservation computed
        // from it is decided for a tile that does not exist. View padding is applied by the
        // framework in the view's OWN space, so a wrong viewSize can shrink the number but can
        // never push it under the header.
        val headerPad = header.height
        // TEMPORARY DIAGNOSTIC -- remove once the fix is confirmed on the device. This is the
        // only instrument that shows the reported size next to what is drawn from it.
        if (BuildConfig.DEBUG) {
            Log.i(
                "CoreHeatDiag",
                "label=$label view=${viewWidth}x$viewHeight pad=$pad hdr=${header.height} " +
                    "fullBox=$fullBox ink=${metrics.height} headerPad=$headerPad " +
                    "align=${config.alignment}",
            )
        }
        numberPaint.textSize = metrics.textSize
        secondaryPaint.textSize = metrics.textSize * SECONDARY_SCALE
        val h = metrics.height
        var digitTop = metrics.digitTop
        // Tracked separately from the box height h: a shrunk value has less ink than the box,
        // and baselineFor centres the ink it is given inside the box it is given.
        var digitHeight = metrics.height

        // Measured once and kept: in the common case the shrink below does not fire, and these
        // are the same two numbers naturalWidth is built from.
        var primaryWidth = numberPaint.measureText(primary)
        var secondaryWidth = secondaryPaint.measureText(secondary)

        // Values wider than the room they have (a Fahrenheit core over 100) shrink to
        // fit. Both parts shrink by the same factor so their size relationship is unchanged.
        val naturalWidth = primaryWidth + secondaryWidth
        val factor = shrinkFactor(naturalWidth, metrics.templateWidth, boxWidth)
        if (factor < 1f) {
            numberPaint.textSize = metrics.textSize * factor
            secondaryPaint.textSize = metrics.textSize * SECONDARY_SCALE * factor
            val shrunk = Rect()
            numberPaint.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, shrunk)
            digitTop = shrunk.top
            digitHeight = shrunk.height()
            primaryWidth = numberPaint.measureText(primary)
            secondaryWidth = secondaryPaint.measureText(secondary)
        }

        // Wide enough for whatever the value came out as. Narrower values keep the template's
        // width, which is what holds their on-screen size steady as digits come and go; a value
        // that outgrew the template and was left unshrunk needs the room it actually takes, or
        // the alignment would push its leading digits off the bitmap.
        val w = maxOf(metrics.width, ceil(primaryWidth + secondaryWidth).toInt())

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        fun startX(width: Float) = when (config.alignment) {
            Alignment.LEFT -> 0f
            Alignment.CENTER -> (w - width) / 2f
            Alignment.RIGHT -> w - width
        }

        val baseline = baselineFor(h, digitHeight, digitTop)
        val left = startX(primaryWidth + secondaryWidth)
        canvas.drawText(primary, left, baseline, numberPaint)

        if (secondary.isNotEmpty()) {
            // Superscript: the secondary sits to the right with its ink top on the primary's, so
            // it reads as attached to the value rather than as a second number below it.
            val small = Rect()
            secondaryPaint.getTextBounds(REFERENCE_GLYPHS, 0, REFERENCE_GLYPHS.length, small)
            val secondaryBaseline = baseline + digitTop - small.top
            canvas.drawText(secondary, left + primaryWidth, secondaryBaseline, secondaryPaint)
        }

        // The bottom is its own number, not pad reused: it is the gap fullBox was computed
        // against, and the two have to be the same or the raster is measured for one box and
        // fitted into another.
        val bottomPad = valueBottomPad(context)
        for (id in BITMAP_IDS) {
            views.setViewPadding(id, pad, headerPad, pad, bottomPad)
        }

        val target = when (config.alignment) {
            Alignment.LEFT -> R.id.bitmap_start
            Alignment.CENTER -> R.id.bitmap_center
            Alignment.RIGHT -> R.id.bitmap_end
        }
        for (id in BITMAP_IDS) {
            views.setViewVisibility(id, if (id == target) View.VISIBLE else View.GONE)
        }
        views.setImageViewBitmap(target, bitmap)

        // The header is aligned by the layout, so pick the copy sitting at the same edge as the
        // number.
        val headerTarget = when (config.alignment) {
            Alignment.LEFT -> R.id.header_start
            Alignment.CENTER -> R.id.header_center
            Alignment.RIGHT -> R.id.header_end
        }
        for (id in HEADER_IDS) {
            views.setViewVisibility(id, if (id == headerTarget) View.VISIBLE else View.GONE)
        }
        // Zero top padding, written EXPLICITLY and to every header id rather than simply not
        // written at all. Two separate reasons, and both bite:
        //
        // The header bitmap already carries its own edge padding, so anything added here is that
        // inset counted twice. Measured on a Karoo 3, an extra `pad` here put the label 20px
        // below the tile's top edge where the 5dp inset every other edge uses is 11.
        //
        // Explicitly, because RemoteViews actions are replayed onto views the host has already
        // inflated: a view carrying padding from a previous version of this code keeps it until
        // something overwrites it, including a copy that is GONE today and comes back later.
        for (id in HEADER_IDS) {
            views.setViewPadding(id, 0, 0, 0, 0)
        }
        // A fresh RemoteViews per update means this has to repeat even though the bitmap is
        // cached -- only the drawing is saved, not the transfer.
        views.setImageViewBitmap(headerTarget, header)

        views.setInt(R.id.root, "setBackgroundColor", backgroundColor ?: Color.TRANSPARENT)
        // Karoo does NOT clip this view to its rounded card -- measured on a Karoo 3 ride
        // page, where a fill came out with square corners sitting over the rounded card. So
        // the rounding is ours to do, everywhere and not just in the page editor.
        // setViewOutlinePreferredRadius is API 31; minSdk here is 29, Karoo 3 runs 33, so on
        // anything older the fill simply stays square.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewOutlinePreferredRadius(R.id.root, CARD_RADIUS_DP, TypedValue.COMPLEX_UNIT_DIP)
            views.setBoolean(R.id.root, "setClipToOutline", true)
        }
    }

    /** Cached [renderHeader]; see [headerCache]. */
    private fun header(
        context: Context,
        label: String,
        iconRes: Int,
        labelColor: Int,
        iconColor: Int,
    ): Bitmap = headerCache.getOrPut(
        HeaderKey(label, iconRes, labelColor, iconColor),
    ) {
        renderHeader(context, label, iconRes, labelColor, iconColor)
    }

    /**
     * The label's Paint, at the size that makes its CAPITALS [LABEL_HEIGHT_DP] tall.
     *
     * Measured on a capital rather than on the label, and the label drawn in capitals. Scaling
     * each label's own ink to the target made the size depend on which glyphs it happened to
     * contain: a lowercase label reaches from the cap line to a "p"'s tail, so its capitals came out a
     * fifth shorter than "HSI"'s to keep the whole ink at 11dp, and centring that taller ink
     * pushed them off the line every other label sat on.
     */
    private fun labelPaint(context: Context): Paint {
        val labelHeight = LABEL_HEIGHT_DP * context.resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            applyFont(context)
            textSize = labelHeight
        }
        val bounds = Rect()
        paint.getTextBounds(CAP_REFERENCE, 0, CAP_REFERENCE.length, bounds)
        if (bounds.height() > 0) paint.textSize = labelHeight * labelHeight / bounds.height()
        return paint
    }

    /**
     * How wide a header bitmap is for [label] -- icon, gap, the label itself and the edge padding
     * on both sides.
     */
    private fun headerWidth(context: Context, label: String): Int {
        val density = context.resources.displayMetrics.density
        val labelHeight = LABEL_HEIGHT_DP * density
        val iconSize = (labelHeight * ICON_SCALE).toInt()
        val pad = 2 * edgePadding(context)
        val labelWidth = labelPaint(context).measureText(label.uppercase())
        return (iconSize + ICON_GAP_DP * density + labelWidth + pad).toInt()
    }

    /**
     * How tall a header bitmap is. The same for every field, because it follows the icon and the
     * fixed label size and neither depends on the label's text.
     */
    private fun headerHeight(context: Context): Int =
        (labelBand(context) + headerTopInset(context) + headerBottomInset(context)).toInt()

    /**
     * Clearance between the label and the number below it. Less than the edge padding by
     * [VALUE_GAIN_PX]: the label's own band already separates the two, and the full padding on
     * top of it was a gap wider than the label's capitals are tall.
     */
    internal fun headerBottomInset(context: Context): Int =
        (edgePadding(context) - VALUE_GAIN_PX).coerceAtLeast(0)

    /**
     * Clearance under the number, against [edgePadding] at its sides. Vertical room is what the
     * number is short of -- horizontally it has the width template's slack -- so the bottom is
     * the one edge where the padding is worth spending on the digits instead.
     */
    internal fun valueBottomPad(context: Context): Int =
        (edgePadding(context) - VALUE_GAIN_PX).coerceAtLeast(0)

    /**
     * The band the icon and the label are centred in, before the insets above and below it.
     * Icon-led, since [ICON_SCALE] draws the glyph taller than the capitals beside it.
     */
    private fun labelBand(context: Context): Float {
        val labelHeight = LABEL_HEIGHT_DP * context.resources.displayMetrics.density
        return maxOf((labelHeight * ICON_SCALE).toInt().toFloat(), labelHeight)
    }

    /**
     * Clearance above the label: the edge padding every other side gets, less [LABEL_LIFT_PX].
     * Floored at zero so a low-density screen cannot ask for a negative inset and draw the
     * label off the top of its own bitmap.
     */
    internal fun headerTopInset(context: Context): Int =
        (edgePadding(context) - LABEL_LIFT_PX).coerceAtLeast(0)

    /**
     * Icon plus short label, at a fixed dp size. The bitmap is exactly as wide as its content
     * plus the edge padding: it is drawn unscaled into a wrap_content view, and the layout's
     * gravity puts it at the same edge as the number. Sizing it to [ViewConfig.viewSize] instead
     * would inherit that value's inaccuracy as a visible offset or a clipped label.
     */
    internal fun renderHeader(
        context: Context,
        label: String,
        iconRes: Int,
        labelColor: Int,
        iconColor: Int,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val labelHeight = LABEL_HEIGHT_DP * density
        val iconSize = (labelHeight * ICON_SCALE).toInt()
        val iconGap = ICON_GAP_DP * density
        val padding = edgePadding(context).toFloat()

        val paint = labelPaint(context).apply {
            color = labelColor
            isSubpixelText = true
        }
        val bounds = Rect()
        paint.getTextBounds(CAP_REFERENCE, 0, CAP_REFERENCE.length, bounds)

        val text = label.uppercase()
        val w = headerWidth(context, label)
        val h = headerHeight(context)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // The bitmap is content-sized, so there is only one place the content can go
        // horizontally. Vertically the content is centred in its BAND and the band is offset by
        // the top inset, rather than centred in the whole bitmap: those are the same arithmetic
        // until the inset stops matching the padding below it, which is exactly what
        // LABEL_LIFT_PX does.
        val left = padding
        val top = headerTopInset(context)
        val band = labelBand(context)
        val iconTop = top + ((band - iconSize) / 2f).toInt()
        context.getDrawable(iconRes)?.mutate()?.apply {
            setTint(iconColor)
            setBounds(left.toInt(), iconTop, left.toInt() + iconSize, iconTop + iconSize)
            draw(canvas)
        }
        canvas.drawText(
            text,
            left + iconSize + iconGap,
            top + baselineFor(band.toInt(), bounds.height(), bounds.top),
            paint,
        )
        return bitmap
    }
}
