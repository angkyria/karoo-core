package io.angkyria.coreheat.format

import io.angkyria.coreheat.render.FieldRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split is pure string work, so it is testable here even though the drawing is not.
 *
 * Two things have to hold. A value's decimal must end up in the secondary part, and the template
 * must be wide enough for every form the formatter can produce -- a field scaled against a
 * budget it does not actually use changes size as its value crosses that budget.
 */
class RaisedTailTest {

    @Test
    fun `a decimal becomes the raised tail and the point goes`() {
        assertEquals("38" to "6", RaisedTail.split("38.6", raised = true))
        assertEquals("4" to "1", RaisedTail.split("4.1", raised = true))
    }

    @Test
    fun `text with no separator is left whole`() {
        assertEquals("100" to "", RaisedTail.split("100", raised = true))
        assertEquals("--" to "", RaisedTail.split("--", raised = true))
    }

    @Test
    fun `a separator with nothing in front of it is not a tail`() {
        assertEquals(".5" to "", RaisedTail.split(".5", raised = true))
    }

    @Test
    fun `switched off, nothing is raised`() {
        assertEquals("38.6" to "", RaisedTail.split("38.6", raised = false))
    }

    @Test
    fun `a decimal template budgets for the form that has no decimal at all`() {
        assertEquals("000" to "", RaisedTail.template("00.0", raised = true))
        assertEquals("00.0" to "", RaisedTail.template("00.0", raised = false))
    }

    @Test
    fun `every value a heat field can produce fits its raised template`() {
        val (primary, secondary) = RaisedTail.template(FieldRenderer.DEFAULT_WIDTH_TEMPLATE, raised = true)
        // The renderer's own scale, not a copy of it: at any other value this test would pass
        // while the field really was over budget, which is the one thing it exists to catch.
        val scale = FieldRenderer.SECONDARY_SCALE
        val budget = primary.length + secondary.length * scale
        // Not a Fahrenheit core temperature: "100.9" keeps its tenth by design and is simply
        // shrunk to fit by the renderer (see FieldRenderer.shrinkFactor).
        for (text in listOf("38.6", "99.9", "4.1", "10.0", "100", "3")) {
            val (p, s) = RaisedTail.split(text, raised = true)
            assertTrue("$text over budget", p.length + s.length * scale <= budget)
        }
    }
}
