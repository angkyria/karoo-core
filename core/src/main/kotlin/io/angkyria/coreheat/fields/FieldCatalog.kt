package io.angkyria.coreheat.fields

import io.angkyria.coreheat.R
import io.angkyria.coreheat.format.Formatters
import io.angkyria.coreheat.heat.HeatAdaptation
import io.angkyria.coreheat.heat.HeatStrain
import io.angkyria.coreheat.heat.HeatTracker
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType

/**
 * The data fields CORE Heat offers, in the order the Karoo picker shows them: everything a
 * CORE body temperature sensor gives, and nothing else.
 */
object FieldCatalog {

    /**
     * The previews tell one story: a 38.6 °C core on 35.1 °C skin is a Heat Strain Index of 4.1,
     * in heat zone 3 -- the one CORE trains in. Named so a test can hold the three to that.
     */
    internal const val CORE_PREVIEW = 38.6
    internal const val SKIN_PREVIEW = 35.1
    internal const val HSI_PREVIEW = 4.1

    fun build(extension: String, karoo: KarooSystemService, heat: HeatTracker): List<BaseNumericField> {
        // The core and skin fields each see only their own temperature, so their colour is read
        // from the tracker, which has both. At most a sample behind the number it colours.
        val heatZoneNow: (Double) -> Int? = { _ -> heat.state.value.hsi?.let(HeatStrain::color) }
        return listOf(
            // The two temperatures are the Karoo's own streams; they ship a data-quality flag
            // beside the reading, so the field they read is named rather than left to
            // singleValue. Both are coloured by the current Heat Zone, which CORE defines on the
            // two together -- a core temperature alone cannot say it.
            SimpleField(extension, "coreTemp", karoo, DataType.Type.CORE_TEMP, "CORE", R.drawable.ic_temp, Formatters.bodyTemperature, needsProfile = true, valueField = DataType.Field.CORE_TEMP, bands = heatZoneNow, previewValue = CORE_PREVIEW),
            SimpleField(extension, "skinTemp", karoo, DataType.Type.SKIN_TEMP, "SKIN", R.drawable.ic_temp, Formatters.bodyTemperature, needsProfile = true, valueField = DataType.Field.SKIN_TEMP, bands = heatZoneNow, previewValue = SKIN_PREVIEW),
            // The four below are worked out here; see HeatTracker.
            HeatField(extension, "heatStrain", karoo, heat, "HSI", Formatters.tenths, read = { it.hsi }, bands = HeatStrain::color, previewValue = HSI_PREVIEW),
            HeatField(extension, "heatZone", karoo, heat, "HEAT Z", Formatters.count, read = { state -> state.hsi?.let { HeatStrain.zone(it).toDouble() } }, bands = { HeatStrain.colorOfZone(it.toInt()) }, previewValue = 3.0),
            HeatField(extension, "heatLoad", karoo, heat, "HEAT LOAD", Formatters.tenths, read = { it.load }, previewValue = 6.1),
            HeatField(extension, "heatAdaptation", karoo, heat, "HEAT ADAPT", Formatters.percent, read = { it.adaptation }, bands = HeatAdaptation::color, previewValue = 72.1),
        )
    }
}
