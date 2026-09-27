package io.angkyria.coreheat.fields

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile.PreferredUnit

/**
 * A numeric field that needs nothing beyond the values passed here: the core and skin
 * temperatures, which the Karoo streams itself.
 */
class SimpleField(
    extension: String,
    typeId: String,
    karoo: KarooSystemService,
    override val upstreamTypeId: String,
    override val label: String,
    override val iconRes: Int,
    override val format: (Double, PreferredUnit?) -> Pair<String, String>,
    override val previewValue: Double = 0.0,
    override val valueField: String? = null,
    private val needsProfile: Boolean = false,
    // The field's colour scale; see BaseNumericField.bandColor.
    private val bands: ((Double) -> Int?)? = null,
) : BaseNumericField(extension, typeId, karoo) {
    override fun formatNeedsProfile() = needsProfile
    override fun bandColor(raw: Double): Int? = bands?.invoke(raw)
}
