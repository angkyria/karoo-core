package io.angkyria.coreheat

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import io.angkyria.coreheat.fields.BaseNumericField
import io.angkyria.coreheat.fields.FieldCatalog
import io.angkyria.coreheat.fields.HudField
import io.angkyria.coreheat.fields.HudMetric
import io.angkyria.coreheat.format.Formatters
import io.angkyria.coreheat.heat.HeatStrain
import io.angkyria.coreheat.heat.HeatTracker
import io.angkyria.coreheat.render.FieldRenderer
import io.angkyria.coreheat.render.HeatPill
import io.angkyria.coreheat.render.Theme
import io.angkyria.coreheat.render.ZoneColors
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooext.models.ViewConfig.Alignment
import java.io.File

/**
 * Debug builds only: draws the HUD at the tile sizes a Karoo hands out and writes each to a PNG,
 * so its layout can be checked at every size without a sensor, a ride or a data page.
 *
 *     adb shell am start -n io.angkyria.coreheat/.HudPreviewActivity
 *     adb pull /sdcard/Android/data/io.angkyria.coreheat/files/hud-preview
 *
 * The tile is the real thing: the RemoteViews the extension sends, inflated by RemoteViews.apply
 * the way the Karoo inflates them. Only the card behind it is ours.
 */
class HudPreviewActivity : Activity() {

    private data class Sample(
        val metric: HudMetric,
        val width: Int,
        val height: Int,
        val mode: ZoneColorMode = ZoneColorMode.TEXT,
        val night: Boolean = true,
        val alignment: Alignment = Alignment.CENTER,
        /** No sensor: both halves and the pill at "--". */
        val missing: Boolean = false,
    ) {
        val name = "${metric.name.lowercase()}-${width}x$height-${mode.name.lowercase()}" +
            (if (night) "" else "-light") +
            (if (alignment != Alignment.CENTER) "-${alignment.name.lowercase()}" else "") +
            (if (missing) "-missing" else "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = File(getExternalFilesDir(null), "hud-preview").apply { deleteRecursively(); mkdirs() }
        // Never connected: nothing here streams. The catalogue is built only for its HUDs.
        val karoo = KarooSystemService(this)
        val huds = FieldCatalog.build(CoreExtension.EXTENSION_ID, karoo, HeatTracker(this, karoo))
            .filterIsInstance<HudField>()
            .associateBy { it.typeId }
        val written = SAMPLES.map { render(huds.getValue(typeIdOf(it.metric)), it, out) }
        File(out, "metrics.txt").writeText(metrics())
        setContentView(TextView(this).apply { text = "Wrote ${written.size} HUD previews to\n$out" })
    }

    /** What the pill has to fit into on this screen, for tuning it: the widths pillLayout weighs. */
    private fun metrics(): String = buildString {
        val pillPx = FieldRenderer.labelBand(this@HudPreviewActivity).toInt() +
            2 * resources.getDimensionPixelSize(R.dimen.hud_pill_inset)
        appendLine("density=${resources.displayMetrics.density} header=${FieldRenderer.headerHeight(this@HudPreviewActivity)} pill=$pillPx")
        for (label in listOf("CORE", "SKIN")) {
            appendLine("label $label=${FieldRenderer.headerWidth(this@HudPreviewActivity, label, withIcon = false)}")
        }
        for (segments in listOf(4, 0)) {
            appendLine("pill segments=$segments width=${HeatPill.width(this@HudPreviewActivity, pillPx, "00.0", segments, hasIcon = true)}")
        }
    }

    private fun typeIdOf(metric: HudMetric) = when (metric) {
        HudMetric.ZONE -> "hudZone"
        HudMetric.LOAD -> "hudLoad"
        HudMetric.ADAPTATION -> "hudAdaptation"
    }

    private fun render(hud: HudField, s: Sample, out: File): File {
        val context = themed(s.night)
        val config = ViewConfig(
            gridSize = 60 to 60,
            viewSize = s.width to s.height,
            textSize = 0,
            alignment = s.alignment,
            boundariesEnabled = false,
            preview = false,
        )
        val ink = Theme.textColor(context)
        val tick = HudField.Tick(
            left = half(FieldCatalog.CORE_PREVIEW, s, ink),
            right = half(FieldCatalog.SKIN_PREVIEW, s, ink),
            pill = s.metric.pillFrame(if (s.missing) null else s.metric.previewValue, s.mode),
        )
        val views = hud.hudViews(context, config, tick)

        val card = FrameLayout(context).apply { setBackgroundColor(Theme.cardColor(s.night)) }
        card.addView(views.apply(context, card), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        card.measure(
            MeasureSpec.makeMeasureSpec(s.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(s.height, MeasureSpec.EXACTLY),
        )
        card.layout(0, 0, s.width, s.height)
        val bitmap = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        card.draw(Canvas(bitmap))
        return File(out, "${s.name}.png").also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * What the CORE or SKIN field draws for [celsius] in the demo story, coloured as its own
     * compute() would colour it by the demo Heat Zone. Built here rather than through compute():
     * the fields read that zone from a HeatTracker, and this one never runs.
     */
    private fun half(celsius: Double, s: Sample, ink: Int): HudField.Half {
        val text = if (s.missing) "--" else Formatters.bodyTemperature(celsius, null).first
        val zone = HeatStrain.color(FieldCatalog.HSI_PREVIEW)!!
        val visual = when {
            s.missing || s.mode == ZoneColorMode.OFF -> BaseNumericField.Visual(text, ink, null)
            s.mode == ZoneColorMode.FILL -> BaseNumericField.Visual(text, ZoneColors.onColor(zone), zone)
            else -> BaseNumericField.Visual(text, zone, null)
        }
        return HudField.Half(visual, Appearance(raisedTail = true))
    }

    /** This activity's context in the Karoo's light or dark theme, which Theme reads from uiMode. */
    private fun themed(night: Boolean): Context {
        val config = Configuration(resources.configuration)
        val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        return createConfigurationContext(config)
    }

    private companion object {
        /**
         * In the dark theme and centre alignment unless a sample says otherwise, as the Karoo 2
         * this was tuned on is set up.
         */
        val SAMPLES = listOf(
            // The tiles this Karoo 2 hands a HUD: a full-width row and a half-width one.
            Sample(HudMetric.ZONE, 478, 127),
            Sample(HudMetric.ADAPTATION, 478, 127),
            Sample(HudMetric.LOAD, 478, 127),
            Sample(HudMetric.ZONE, 238, 127),
            Sample(HudMetric.ADAPTATION, 238, 127),
            Sample(HudMetric.LOAD, 238, 127),
            // Taller and shorter rows, and a quarter of a 2x2 page.
            Sample(HudMetric.ZONE, 478, 288),
            Sample(HudMetric.ZONE, 478, 190),
            Sample(HudMetric.ZONE, 478, 110),
            Sample(HudMetric.ZONE, 238, 340),
            // Heat colours, the light theme, a right-aligned page and no sensor.
            Sample(HudMetric.ZONE, 478, 127, mode = ZoneColorMode.FILL),
            Sample(HudMetric.ZONE, 238, 127, mode = ZoneColorMode.FILL),
            Sample(HudMetric.ZONE, 478, 127, mode = ZoneColorMode.OFF),
            Sample(HudMetric.ZONE, 478, 127, night = false),
            Sample(HudMetric.ADAPTATION, 238, 127, night = false),
            Sample(HudMetric.ZONE, 478, 127, alignment = Alignment.RIGHT),
            Sample(HudMetric.ZONE, 478, 127, missing = true),
        )
    }
}
