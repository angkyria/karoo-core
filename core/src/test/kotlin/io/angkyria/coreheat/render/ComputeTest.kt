package io.angkyria.coreheat.render

import io.angkyria.coreheat.ZoneColorMode
import io.angkyria.coreheat.fields.BaseNumericField
import io.angkyria.coreheat.format.Formatters
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises [BaseNumericField.compute] directly. It is pure over its six parameters -- no
 * Context, no android.* call -- so it is the one piece of the field pipeline this Robolectric-
 * free module can drive in a plain JVM test. [BaseNumericField.frameFlow] needs a live Context
 * to build its flows and is left untested here; it stays a thin, hand-checked wrapper around
 * this function plus [io.angkyria.coreheat.Settings].
 */
class ComputeTest {

    private val defaultColor = 0xFF123456.toInt()

    /**
     * Minimal concrete field, just enough to reach [BaseNumericField.compute]. karoo is null:
     * compute() never touches it, and this module has no Robolectric to hand it a real
     * KarooSystemService, which needs a live Context to construct.
     */
    private class TestField(private val band: Int? = null) : BaseNumericField("test", "test_type", null) {
        override val upstreamTypeId = "test.upstream"
        override val label = "TEST"
        override val iconRes = 0
        override val format = Formatters.tenths
        override val previewValue = 4.1

        override fun bandColor(raw: Double): Int? = band
    }

    private fun streaming(value: Double) =
        StreamState.Streaming(DataPoint("test.upstream", mapOf("value" to value)))

    @Test fun `streaming value renders formatted text with the default colour and no background`() {
        val visual = TestField().compute(
            streaming(3.24), profile = null, preview = false, testMode = false,
            mode = ZoneColorMode.TEXT, defaultColor = defaultColor,
        )
        assertEquals("3.2", visual.text)
        assertEquals(defaultColor, visual.color)
        assertNull(visual.background)
    }

    @Test fun `test mode shows the preview value even while a stream is arriving`() {
        val visual = TestField().compute(
            streaming(9.9), profile = null, preview = false, testMode = true,
            mode = ZoneColorMode.OFF, defaultColor = defaultColor,
        )
        assertEquals("4.1", visual.text)
    }

    @Test fun `preview shows the preview value when no stream is available`() {
        val visual = TestField().compute(
            StreamState.Idle, profile = null, preview = true, testMode = false,
            mode = ZoneColorMode.OFF, defaultColor = defaultColor,
        )
        assertEquals("4.1", visual.text)
    }

    @Test fun `no value falls back to a dash with no colour`() {
        val visual = TestField(band = 0xFFFFF500.toInt()).compute(
            StreamState.NotAvailable, profile = null, preview = false, testMode = false,
            mode = ZoneColorMode.FILL, defaultColor = defaultColor,
        )
        assertEquals("--", visual.text)
        assertEquals(defaultColor, visual.color)
        assertNull(visual.background)
    }

    @Test fun `a heat colour is honoured by the colour setting`() {
        val band = 0xFFFFF500.toInt()
        val field = TestField(band = band)
        fun at(mode: ZoneColorMode) = field.compute(
            streaming(3.8), profile = null, preview = false, testMode = false,
            mode = mode, defaultColor = defaultColor,
        )

        assertEquals(band, at(ZoneColorMode.TEXT).color)
        assertNull(at(ZoneColorMode.TEXT).background)
        assertEquals(band, at(ZoneColorMode.FILL).background)
        assertEquals(ZoneColors.onColor(band), at(ZoneColorMode.FILL).color)
        assertEquals(defaultColor, at(ZoneColorMode.OFF).color)
        assertNull(at(ZoneColorMode.OFF).background)
    }
}
