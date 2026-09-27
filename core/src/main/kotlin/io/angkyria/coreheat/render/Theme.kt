package io.angkyria.coreheat.render

import android.content.Context
import android.content.res.Configuration

/**
 * Karoo's light/dark setting is the Android night mode, so the field can read it from its own
 * configuration -- karoo-ext exposes nothing about the theme. Read per render rather than
 * cached: a theme switch then shows up on the next update without restarting anything.
 */
object Theme {

    private const val ON_DARK = 0xFFFFFFFF.toInt()
    private const val ON_LIGHT = 0xFF000000.toInt()

    // Named apart from the two above rather than reusing them inverted: ON_LIGHT is "ink for a
    // light ground" and happens to equal the night card's colour.
    private const val CARD_NIGHT = 0xFF000000.toInt()
    private const val CARD_DAY = 0xFFFFFFFF.toInt()

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Colour for text that carries no zone colour of its own. */
    fun textColor(context: Context): Int = textColor(isNight(context))

    /** The same, for a caller that has already read the theme and keys a cache on it. */
    fun textColor(isNight: Boolean): Int = if (isNight) ON_DARK else ON_LIGHT

    /**
     * The colour Karoo draws its field cards in. Only for something that paints its own opaque
     * copy of that ground: the HUD's pill, whose track is translucent and would otherwise show
     * the divider and the halves' zone fills through it.
     */
    fun cardColor(isNight: Boolean): Int = if (isNight) CARD_NIGHT else CARD_DAY
}
