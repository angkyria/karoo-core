package io.angkyria.coreheat.render

/**
 * Colour helpers shared by every field. The heat colours themselves live with the heat maths
 * (see HeatStrain and HeatAdaptation); this is what sits on top of them.
 */
object ZoneColors {

    /**
     * Black or white, whichever reads better on [background]. Used when a field is filled with
     * its zone colour: the number, the label and the icon all sit on that fill.
     *
     * Plain WCAG relative luminance rather than anything cleverer.
     */
    fun onColor(background: Int): Int =
        if (relativeLuminance(background) > 0.179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    private fun relativeLuminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
