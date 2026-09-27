package io.angkyria.coreheat.fields

import android.content.Context
import android.widget.RemoteViews
import io.angkyria.coreheat.R
import io.angkyria.coreheat.Settings
import io.angkyria.coreheat.Appearance
import io.angkyria.coreheat.format.RaisedTail
import io.angkyria.coreheat.ZoneColorMode
import io.angkyria.coreheat.consumerFlow
import io.angkyria.coreheat.render.FieldRenderer
import io.angkyria.coreheat.render.Theme
import io.angkyria.coreheat.render.ZoneColors
import io.angkyria.coreheat.streamDataFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.UserProfile.PreferredUnit
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

abstract class BaseNumericField(
    extension: String,
    typeId: String,
    // Nullable only so ComputeTest can build a throwaway subclass in a plain JVM test: this
    // project has no Robolectric, so there is no live Context to hand a real KarooSystemService,
    // and compute() -- the one thing that test drives -- never touches this field anyway. Every
    // real field always constructs with a non-null instance; see the `!!` uses in frameFlow.
    private val karoo: KarooSystemService?,
) : DataTypeImpl(extension, typeId) {

    abstract val upstreamTypeId: String

    /** Short header text, drawn by us -- Karoo's own header shows the uppercased displayName. */
    abstract val label: String

    /** Header icon, drawn by us and tinted; see [io.angkyria.coreheat.render.FieldRenderer]. */
    abstract val iconRes: Int

    /**
     * Which field of the data point carries the value, for a stream that ships more than one.
     * Null takes whatever [io.hammerhead.karooext.models.DataPoint.singleValue] returns. The
     * Karoo's core and skin temperature streams ship a data-quality flag beside the reading.
     */
    open val valueField: String? = null

    abstract val format: (Double, PreferredUnit?) -> Pair<String, String>
    open val previewValue: Double = 0.0
    protected open fun formatNeedsProfile(): Boolean = false

    /**
     * Colour for [raw] on this field's own scale -- CORE's heat zones and adaptation levels.
     * Honoured by the heat colour setting, number or fill; null leaves the default ink.
     */
    open fun bandColor(raw: Double): Int? = null

    final override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // We draw the icon and label ourselves, so Karoo's header would only duplicate them
        // and eat the top of the tile.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Registered before launch: if setCancellable ran after and the emitter were torn down
        // in the gap, there would be no way to stop the collector this scope is about to start.
        emitter.setCancellable { scope.cancel() }
        scope.launch {
            frameFlow(context, config.preview).collect { (visual, appearance) ->
                // A fresh RemoteViews per update, never a reused one: RemoteViews is an
                // append-only list of actions with no way to clear it, so reusing the instance
                // would retain every bitmap ever set and re-serialize the whole growing list on
                // each send -- ending in FAILED BINDER TRANSACTION or OOM after a long ride.
                val views = RemoteViews(context.packageName, R.layout.numeric_field)
                renderInto(context, views, config, visual, appearance)
                emitter.updateView(views)
            }
        }
    }

    /**
     * Where the value comes from: the Karoo stream [upstreamTypeId] names, for every field that
     * shows a number the Karoo already has. A field that works its value out itself -- the heat
     * fields, from the CORE sensor's two temperatures -- hands its own flow in here instead, in
     * the same shape, so preview and test mode treat it like any other.
     */
    internal open fun sourceFlow(): Flow<StreamState> = karoo!!.streamDataFlow(upstreamTypeId)

    /**
     * The value half of [startView]: today's sample plus the settings that decide how it is
     * drawn, collapsed into one (frame, appearance) update.
     */
    internal fun frameFlow(context: Context, preview: Boolean): Flow<Pair<Visual, Appearance>> {
        val needsProfile = formatNeedsProfile()
        val dataFlow = sourceFlow()
        val profileFlow = if (needsProfile) karoo!!.consumerFlow<UserProfile>() else flowOf<UserProfile?>(null)

        return combine(
            dataFlow,
            profileFlow,
            Settings.zoneColorModeFlow(context),
            Settings.testModeFlow(context),
            Settings.appearanceFlow(context),
        ) { state, profile, mode, testMode, appearance ->
            // Carried alongside the frame rather than inside it: appearance decides how the
            // value is drawn, not what the value is.
            compute(state, profile, preview, testMode, mode, Theme.textColor(context)) to appearance
        }
            // Karoo sends a sample whether or not the value moved, and a temperature sits still
            // for long stretches. Without this each of those samples draws a bitmap identical to
            // the one already on screen and ships it across a process boundary to change
            // nothing. Visual and Appearance are data classes, so equality compares what is
            // drawn.
            .distinctUntilChanged()
    }

    /**
     * The drawing half of [startView]: turns one (visual, appearance) update into the actions on
     * [views].
     */
    internal fun renderInto(
        context: Context,
        views: RemoteViews,
        config: ViewConfig,
        visual: Visual,
        appearance: Appearance,
    ) {
        val raised = appearance.raisedTail
        val (primary, secondary) = RaisedTail.split(visual.text, raised)
        val (tPrimary, tSecondary) = RaisedTail.template(FieldRenderer.DEFAULT_WIDTH_TEMPLATE, raised)
        FieldRenderer.render(
            context, views, config, label, iconRes,
            tPrimary, tSecondary, primary, secondary, visual.color,
            visual.background,
        )
    }

    /**
     * What one update puts on screen. [background] is null unless the field is filled with its
     * zone colour, in which case [color] is the contrasting ink for that fill.
     */
    internal data class Visual(val text: String, val color: Int, val background: Int?)

    /** The number this field's stream is carrying, before [format], or null when there is none. */
    internal fun rawValue(state: StreamState, preview: Boolean, testMode: Boolean): Double? = when {
        // Ahead of the stream, unlike preview: with a Karoo sitting idle a live 0 and a
        // demo 0 look the same, so test mode has to win even while data is arriving.
        // Page editing keeps deferring to real data when there is any.
        testMode -> previewValue
        preview && state !is StreamState.Streaming -> previewValue
        state is StreamState.Streaming -> state.dataPoint.let { point ->
            valueField?.let { point.values[it] } ?: point.singleValue
        }
        else -> null
    }

    internal fun compute(
        state: StreamState,
        profile: UserProfile?,
        preview: Boolean,
        testMode: Boolean,
        mode: ZoneColorMode,
        defaultColor: Int,
    ): Visual {
        // No zone applies to a missing value, so no fill either -- an empty field should not sit
        // there in a colour that says something about data it does not have.
        val raw = rawValue(state, preview, testMode) ?: return Visual("--", defaultColor, null)
        val text = format(raw, profile?.preferredUnit).first
        val zone = (if (mode == ZoneColorMode.OFF) null else bandColor(raw))
            ?: return Visual(text, defaultColor, null)
        return when (mode) {
            ZoneColorMode.FILL -> Visual(text, ZoneColors.onColor(zone), zone)
            else -> Visual(text, zone, null)
        }
    }
}
