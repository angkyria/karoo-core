package io.angkyria.coreheat.format

import io.hammerhead.karooext.models.UserProfile.PreferredUnit
import io.hammerhead.karooext.models.UserProfile.PreferredUnit.UnitType
import java.util.Locale

object Formatters {

    val count: (Double, PreferredUnit?) -> Pair<String, String> =
        { v, _ -> "${v.toInt()}" to "" }

    /** A bare number to the tenth: the heat strain index and the heat training load, 0 to 10. */
    val tenths: (Double, PreferredUnit?) -> Pair<String, String> =
        { v, _ -> "%.1f".fmt(v) to "" }

    val percent: (Double, PreferredUnit?) -> Pair<String, String> =
        { v, _ -> compact(v) to "%" }

    /**
     * A body temperature, kept to the tenth: core temperature spends a ride inside two or three
     * degrees, and the difference between 38.1 and 38.9 is the whole reason to have the field.
     */
    val bodyTemperature: (Double, PreferredUnit?) -> Pair<String, String> = { v, p ->
        when (p?.temperature) {
            UnitType.IMPERIAL -> "%.1f".fmt(v * 9.0 / 5.0 + 32) to "°F"
            else              -> "%.1f".fmt(v)                  to "°C"
        }
    }

    /**
     * One decimal while it fits the field's width budget, none once the value grows past it.
     * A field is sized for a fixed number of glyphs; without this, an adaptation of "100.0" would
     * be rendered ~20% smaller than every other value just to fit.
     */
    // Branch on the rounded value, not the raw one: 99.96 is below 100 but "%.1f" prints it
    // as "100.0", one glyph wider than the "00.0" budget this exists to hold.
    private fun compact(v: Double): String =
        if (v <= -10.0 || Math.round(v * 10) >= 1000) "${Math.round(v)}" else "%.1f".fmt(v)

    private fun String.fmt(vararg args: Any): String =
        String.format(Locale.US, this, *args)
}
