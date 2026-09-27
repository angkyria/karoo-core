package io.angkyria.coreheat.format

/**
 * Splits a formatted value so its decimal can be drawn small and raised. The point goes: the
 * raised digit already says what it is, and its width is width the number can have instead.
 *
 *     "34.9"    ->  "34"   + "9"
 *     "226"     ->  "226"  + ""
 */
object RaisedTail {

    // Literal, which holds because every value here is formatted through Formatters.fmt at
    // Locale.US. Move that to the device locale and a comma decimal stops splitting.
    private const val SEPARATOR = '.'

    fun split(text: String, raised: Boolean): Pair<String, String> {
        if (!raised) return text to ""
        val i = text.lastIndexOf(SEPARATOR)
        // One branch for both misses: -1 is no separator at all, 0 is a separator with
        // nothing in front of it, and ".5" is no more a tail than "226" is.
        return if (i <= 0) text to "" else text.substring(0, i) to text.substring(i + 1)
    }

    /**
     * The same split applied to a field's width template, which is the budget the number is
     * scaled against.
     *
     * Not just [split]: [Formatters.compact] prints one decimal while it fits and none once the
     * value grows past it, so an adaptation field draws "99.9" and then "100" -- and a budget of
     * two full-size digits plus a raised one is too narrow for three full-size ones. So the
     * template budgets as its own digits at full size with the point dropped, which covers both.
     */
    fun template(template: String, raised: Boolean): Pair<String, String> =
        if (raised && SEPARATOR in template) template.replace(SEPARATOR.toString(), "") to "" else template to ""
}
