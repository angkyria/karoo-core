package io.angkyria.coreheat.fields

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.angkyria.coreheat.Appearance
import io.angkyria.coreheat.R
import io.angkyria.coreheat.Settings
import io.angkyria.coreheat.ZoneColorMode
import io.angkyria.coreheat.format.Formatters
import io.angkyria.coreheat.heat.HeatAdaptation
import io.angkyria.coreheat.heat.HeatState
import io.angkyria.coreheat.heat.HeatStrain
import io.angkyria.coreheat.heat.HeatTracker
import io.angkyria.coreheat.render.FieldRenderer
import io.angkyria.coreheat.render.HeatPill
import io.angkyria.coreheat.render.Theme
import io.angkyria.coreheat.render.ZoneColors
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

private const val TAG = "CoreHeatHud"

/**
 * How long a failed half waits before its stream is resubscribed: long enough that a sensor
 * dropping out does not become a resubscribe loop, short enough that the half comes back on its
 * own without the rider touching the page.
 */
private const val SLOT_RETRY_DELAY_MS = 2000L

/**
 * How long the HUD holds its collector after drawing.
 *
 * karoo-ext's ViewEmitter.updateView DROPS -- does not queue -- any view sent within 900ms of the
 * previous one ("ignoring updateView, too soon"). The HUD draws "--" the moment it starts and its
 * real values milliseconds later, so without this the real frame is the one thrown away, and a
 * temperature that then holds still never re-emits to repaint it. Waiting turns a dropped update
 * into a delayed one: conflate() keeps only the newest state meanwhile. 1000 rather than 900 so
 * scheduling jitter cannot land back inside the window.
 */
private const val EMITTER_WINDOW_MS = 1000L

/**
 * The widest value a pill is budgeted for when deciding what fits: the index and the load top out
 * at "10.0", and adaptation at "99.9" before it drops its decimal for "100".
 */
private const val PILL_TEXT_TEMPLATE = "00.0"

/**
 * Where the load pill lights its squares. CORE gives heat training load no bands of its own, so
 * these are the pill's: the first at the one threshold CORE does define -- a day only counts toward
 * adaptation once its load is above [HeatAdaptation.MIN_LOAD], compared the same way -- then every
 * 2 points up to 8, so it counts out in four squares like the zone and adaptation pills.
 */
private val LOAD_STEPS = doubleArrayOf(HeatAdaptation.MIN_LOAD, 4.0, 6.0, 8.0)

/**
 * What a HUD shows in its pill. One picker entry per metric, so each data page can carry the one
 * it wants.
 */
enum class HudMetric(
    internal val read: (HeatState) -> Double?,
    internal val previewValue: Double,
) {
    /** CORE's four heat zones as squares, and the Heat Strain Index they are cut from. */
    ZONE({ it.hsi }, FieldCatalog.HSI_PREVIEW),

    /**
     * Today's heat training load, a square for each of [LOAD_STEPS] it is past. Uncoloured, like
     * the load's own field: CORE gives the load no colour.
     */
    LOAD({ it.load }, FieldCatalog.LOAD_PREVIEW),

    /** CORE's four adaptation levels as squares, and the score. */
    ADAPTATION({ it.adaptation }, FieldCatalog.ADAPTATION_PREVIEW),
    ;

    /** How many squares the pill has, lit or not. */
    internal val segments: Int
        get() = when (this) {
            ZONE -> HeatStrain.ZONES
            LOAD -> LOAD_STEPS.size
            ADAPTATION -> HeatAdaptation.Level.entries.size
        }

    /** The value the pill shows, by the rule BaseNumericField.rawValue applies to a field. */
    internal fun shown(live: Double?, preview: Boolean, testMode: Boolean): Double? = when {
        testMode -> previewValue
        preview && live == null -> previewValue
        else -> live
    }

    /**
     * One pill for [value], its text formatted the way this metric's own field formats it, so a
     * pill never disagrees by a digit with the field beside it.
     */
    internal fun pillFrame(value: Double?, mode: ZoneColorMode): PillFrame {
        if (value == null) return PillFrame(lit = 0, segments = segments, color = null, text = "--")
        val lit = when (this) {
            ZONE -> HeatStrain.zone(value)
            LOAD -> LOAD_STEPS.count { value > it }
            ADAPTATION -> HeatAdaptation.level(value).ordinal + 1
        }
        // Heat colours off means no colour anywhere; the lit squares still carry the count.
        val color = if (mode == ZoneColorMode.OFF) null else when (this) {
            ZONE -> HeatStrain.color(value)
            LOAD -> null
            ADAPTATION -> HeatAdaptation.color(value)
        }
        val text = when (this) {
            ZONE, LOAD -> Formatters.tenths(value, null).first
            ADAPTATION -> Formatters.percent(value, null).first
        }
        return PillFrame(lit, segments, color, text)
    }
}

/** One update of the pill: [lit] squares of [segments], the band's colour, and the value. */
internal data class PillFrame(val lit: Int, val segments: Int, val color: Int?, val text: String)

/**
 * Core and skin temperature side by side, with a heat metric in a pill between their labels.
 *
 * Modelled on karoo-bignum's HUD (github.com/smartycoder/karoo-bignum, Apache-2.0). Each half is
 * the CORE or SKIN field itself -- its number, heat-zone colour and fill -- centred in its half,
 * with a hairline between them. The labels are the tile's own: one row across the top with CORE
 * and SKIN centred over their numbers and the pill between them, carrying the tile's one icon.
 * That row is the one each half reserves for a header anyway, so it costs neither number height.
 *
 * A [DataTypeImpl] rather than a [BaseNumericField]: it has no stream or format of its own; it
 * composes two fields that already know how to draw themselves.
 */
class HudField(
    extension: String,
    typeId: String,
    private val core: BaseNumericField,
    private val skin: BaseNumericField,
    private val heat: HeatTracker,
    private val metric: HudMetric,
) : DataTypeImpl(extension, typeId) {

    /** One half: what its field would draw on its own. */
    internal data class Half(val visual: BaseNumericField.Visual, val appearance: Appearance)

    /** One pass through the combine in [startView]. */
    internal data class Tick(val left: Half, val right: Half, val pill: PillFrame)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Both halves draw their own header, so Karoo's would only stack a third on top of them.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Registered before launch; see BaseNumericField.startView.
        emitter.setCancellable { scope.cancel() }
        scope.launch {
            combine(
                half(core, context, config.preview),
                half(skin, context, config.preview),
                pillFlow(context, config.preview),
            ) { left, right, pill -> Tick(left, right, pill) }
                // Only the newest state is kept while the collector waits out the emitter window.
                .conflate()
                .collect { tick ->
                    // Everything that draws is inside this try. A throw here is NOT caught by
                    // SupervisorJob: it reaches the default handler and takes the extension down
                    // mid-ride, every field on every page with it. Throwable, because the likeliest
                    // failure is Bitmap.createBitmap's OutOfMemoryError.
                    val views = try {
                        hudViews(context, config, tick)
                    } catch (t: Throwable) {
                        Log.w(TAG, "HUD render failed", t)
                        null
                    }
                    if (views != null) {
                        emitter.updateView(views)
                        delay(EMITTER_WINDOW_MS)
                    }
                }
        }
    }

    /** One half's updates, with a failed stream shown as "--" and resubscribed, not a dead half. */
    private fun half(field: BaseNumericField, context: Context, preview: Boolean): Flow<Half> =
        field.frameFlow(context, preview)
            .map { (visual, appearance) -> Half(visual, appearance) }
            .withSlotRecovery(
                fallback = { Half(field.missingFrame(context), Appearance(Settings.raisedTail(context))) },
                delayMs = SLOT_RETRY_DELAY_MS,
            )

    private fun pillFlow(context: Context, preview: Boolean): Flow<PillFrame> =
        combine(
            heat.state.map { metric.read(it) }.distinctUntilChanged(),
            Settings.testModeFlow(context),
            Settings.zoneColorModeFlow(context),
        ) { live, testMode, mode -> metric.pillFrame(metric.shown(live, preview, testMode), mode) }
            .distinctUntilChanged()

    /**
     * The whole tile for one [tick]. A fresh RemoteViews every time, never a reused one: it is an
     * append-only list of actions, and addView appends, so a reused one would grow without bound.
     *
     * Internal so the debug build's HudPreviewActivity can draw a tile at any size without a Karoo.
     */
    internal fun hudViews(context: Context, config: ViewConfig, tick: Tick): RemoteViews {
        // The same resource the layout gives the divider, so the halves are measured for the room
        // the layout actually leaves them.
        val divider = context.resources.getDimensionPixelSize(R.dimen.hud_divider_width)
        val leftHalf = halfConfig(config, left = true, dividerPx = divider)
        val rightHalf = halfConfig(config, left = false, dividerPx = divider)

        // The labels without their icons: the pill carries the tile's one icon. Their ink is the
        // card's, or whichever reads on the halves' zone fill -- both halves are filled by the one
        // Heat Zone, so the left speaks for both.
        val ink = tick.left.visual.background?.let(ZoneColors::onColor) ?: Theme.textColor(context)
        val coreLabel = FieldRenderer.header(context, core.label, core.iconRes, ink, ink, withIcon = false)
        val skinLabel = FieldRenderer.header(context, skin.label, skin.iconRes, ink, ink, withIcon = false)

        val pillPx = pillHeight(context)
        fun pillWidth(segments: Int) = maxOf(
            HeatPill.width(context, pillPx, tick.pill.text, segments, hasIcon = true),
            HeatPill.width(context, pillPx, PILL_TEXT_TEMPLATE, segments, hasIcon = true),
        )
        val layout = pillLayout(
            leftHalf = leftHalf.viewSize.first,
            rightHalf = rightHalf.viewSize.first,
            dividerPx = divider,
            leftLabel = coreLabel.width,
            rightLabel = skinLabel.width,
            squaresPill = pillWidth(tick.pill.segments),
            solidPill = pillWidth(0),
            edgePx = FieldRenderer.edgePadding(context),
        )
        val pill = layout.style?.let { style ->
            HeatPill.render(
                context, pillPx, tick.pill.lit, tick.pill.segments,
                solid = style == PillStyle.SOLID,
                // A solid pill in the very colour the halves are filled with vanishes into them --
                // the zone pill on a narrow tile under Field background. The fill says the zone
                // already, so there the pill keeps its neutral ground and stays a lozenge.
                color = tick.pill.color?.takeUnless { style == PillStyle.SOLID && it == tick.left.visual.background },
                text = tick.pill.text,
                icon = context.getDrawable(R.drawable.ic_temp),
                isNight = Theme.isNight(context),
                rowHeightPx = FieldRenderer.headerHeight(context),
                centreY = labelCentre(context),
            )
        }

        val views = RemoteViews(context.packageName, R.layout.hud_field)
        // Visibility on BOTH paths, for the pill and the labels alike: actions are replayed onto
        // whatever the host already has, so anything left VISIBLE by the last update would stay.
        if (pill != null) {
            views.setImageViewBitmap(R.id.hud_pill, pill)
            views.setViewVisibility(R.id.hud_pill, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.hud_pill, View.GONE)
        }
        if (layout.labels) {
            views.setImageViewBitmap(R.id.hud_label_core, coreLabel)
            views.setImageViewBitmap(R.id.hud_label_skin, skinLabel)
        }
        val labels = if (layout.labels) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.hud_label_core, labels)
        views.setViewVisibility(R.id.hud_label_skin, labels)
        // removeAllViews before each addView: were the host to replay these onto a tile it had
        // already inflated, addView alone would stack a second half on the first.
        views.removeAllViews(R.id.slot_left)
        views.addView(R.id.slot_left, halfViews(context, leftHalf, core, tick.left))
        views.removeAllViews(R.id.slot_right)
        views.addView(R.id.slot_right, halfViews(context, rightHalf, skin, tick.right))
        // The halves do not round themselves -- two rounded cards meeting at the divider would
        // notch it -- so the tile is rounded as a whole, or a zone fill has square corners.
        FieldRenderer.roundToCard(views, R.id.hud_root)
        return views
    }

    /**
     * One half: its field's number, fill and colour, drawn into its own copy of the ordinary field
     * layout -- without the field's header, whose row stays reserved above the number. The labels
     * are the tile's, set in its own row by [hudViews].
     */
    private fun halfViews(context: Context, config: ViewConfig, field: BaseNumericField, half: Half): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.numeric_field)
        field.renderInto(context, views, config, half.visual, half.appearance, inSlot = true)
        return views
    }

    /** The line in the header row the labels are centred on, and so the pill's middle. */
    private fun labelCentre(context: Context): Float =
        FieldRenderer.headerTopInset(context) + FieldRenderer.labelBand(context) / 2f

    /**
     * As tall as the band the labels are centred in, and a pixel more above and below. Its value
     * then comes out the size of the labels beside it, and the pill narrow enough to sit between
     * them on a full-width tile.
     */
    private fun pillHeight(context: Context): Int =
        FieldRenderer.labelBand(context).toInt() +
            2 * context.resources.getDimensionPixelSize(R.dimen.hud_pill_inset)
}

internal enum class PillStyle { SQUARES, SOLID }

/** What the tile has room for: the two labels or not, and which pill, if any. */
internal data class PillLayout(val labels: Boolean, val style: PillStyle?)

/**
 * Fit the two labels, each centred over its own half, and the pill, centred on the tile between
 * them. What gives way, in order: the squares, which leave a solid pill in the band's colour; then
 * the labels, which leave the pill alone in the row -- with its squares again if it now has room
 * for them; and on a tile too narrow for any pill, the pill, which gives the labels back.
 *
 * The pill outlasts the labels because it is what the rider chose this HUD for; without labels,
 * CORE is still on the left and SKIN on the right, as on every wider HUD.
 *
 * Pure over widths measured by the caller, so the order can be held to in a JVM test. All in
 * pixels; the label widths include their edge padding, which is what keeps the pill off the text.
 */
internal fun pillLayout(
    leftHalf: Int,
    rightHalf: Int,
    dividerPx: Int,
    leftLabel: Int,
    rightLabel: Int,
    squaresPill: Int,
    solidPill: Int,
    edgePx: Int,
): PillLayout {
    // The pill is centred on the tile, not on either half, so each side's clearance is measured
    // against the half across from it: a pill of width p clears the left label while
    // p <= dividerPx + rightHalf - leftLabel, and the right one while p <= dividerPx + leftHalf - rightLabel.
    val besideLabels = minOf(dividerPx + rightHalf - leftLabel, dividerPx + leftHalf - rightLabel)
    val alone = leftHalf + dividerPx + rightHalf - 2 * edgePx
    return when {
        squaresPill <= besideLabels -> PillLayout(labels = true, style = PillStyle.SQUARES)
        solidPill <= besideLabels -> PillLayout(labels = true, style = PillStyle.SOLID)
        squaresPill <= alone -> PillLayout(labels = false, style = PillStyle.SQUARES)
        solidPill <= alone -> PillLayout(labels = false, style = PillStyle.SOLID)
        else -> PillLayout(labels = true, style = null)
    }
}

/**
 * The [ViewConfig] one half renders against: the tile's, narrowed to that half and centred.
 * [dividerPx] comes off the width first. Pure, so a JVM test can hold it to the layout's arithmetic.
 */
internal fun halfConfig(config: ViewConfig, left: Boolean, dividerPx: Int): ViewConfig {
    val (w, h) = config.viewSize
    val usable = w - dividerPx
    // `usable - usable / 2` for the right half rather than a second `usable / 2`: a weight=1
    // LinearLayout hands the odd pixel to one child, and flooring both would budget a half for a
    // tile a pixel narrower than the one it gets.
    return config.copy(
        viewSize = (if (left) usable / 2 else usable - usable / 2) to h,
        // Centred whatever the page's alignment: a HUD is symmetric, with each number centred
        // under its label.
        alignment = ViewConfig.Alignment.CENTER,
    )
}

/**
 * Emit [fallback] on subscribe, and again on every upstream failure before resubscribing.
 *
 * Not `.catch`: catch emits and then COMPLETES, so one failure would pin that half at "--" for the
 * rest of the ride. The onStart emission means combine, which waits for every source, is never
 * held up by a half whose stream has not spoken yet.
 */
internal fun <T> Flow<T>.withSlotRecovery(fallback: () -> T, delayMs: Long): Flow<T> =
    retryWhen { cause, attempt ->
        Log.w(TAG, "HUD half failed (attempt $attempt), retrying in ${delayMs}ms", cause)
        emit(fallback())
        delay(delayMs)
        true
    }.onStart { emit(fallback()) }
