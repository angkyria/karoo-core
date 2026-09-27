package io.angkyria.coreheat.format

import io.angkyria.coreheat.fields.FieldCatalog
import io.hammerhead.karooext.models.UserProfile.PreferredUnit
import io.hammerhead.karooext.models.UserProfile.PreferredUnit.UnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {

    private val metric = PreferredUnit(
        distance = UnitType.METRIC,
        elevation = UnitType.METRIC,
        temperature = UnitType.METRIC,
        weight = UnitType.METRIC,
    )
    private val imperial = PreferredUnit(
        distance = UnitType.IMPERIAL,
        elevation = UnitType.IMPERIAL,
        temperature = UnitType.IMPERIAL,
        weight = UnitType.IMPERIAL,
    )

    // ── percent: heat adaptation ───────────────────────────────────────────
    @Test fun `percent formats one decimal with percent unit`() {
        assertEquals("4.2" to "%", Formatters.percent(4.234, null))
    }

    // ── body temperature (input celsius) ───────────────────────────────────
    @Test fun `body temperature keeps the tenth in celsius`() {
        assertEquals("38.3" to "°C", Formatters.bodyTemperature(38.27, metric))
    }

    @Test fun `body temperature keeps the tenth in fahrenheit`() {
        assertEquals("100.9" to "°F", Formatters.bodyTemperature(38.3, imperial))
    }

    @Test fun `percent drops the decimal at 100 so it fits the budget`() {
        assertEquals("99.9" to "%", Formatters.percent(99.9, null))
        assertEquals("100" to "%", Formatters.percent(99.96, null))
        assertEquals("100" to "%", Formatters.percent(100.0, null))
    }

    // ── count: heat zone ───────────────────────────────────────────────────
    @Test fun `count drops the fraction`() {
        assertEquals("3" to "", Formatters.count(3.0, null))
    }

    // ── tenths: heat strain index and heat training load ───────────────────
    @Test fun `tenths keeps one decimal and no unit`() {
        assertEquals("4.1" to "", Formatters.tenths(4.08, null))
        assertEquals("10.0" to "", Formatters.tenths(10.0, null))
    }

    @Test fun `the heat previews tell one story - their core and skin make their index, in zone 3`() {
        val hsi = io.angkyria.coreheat.heat.HeatStrain.index(FieldCatalog.CORE_PREVIEW, FieldCatalog.SKIN_PREVIEW)!!
        assertEquals(Formatters.tenths(FieldCatalog.HSI_PREVIEW, null), Formatters.tenths(hsi, null))
        assertEquals(3, io.angkyria.coreheat.heat.HeatStrain.zone(hsi))
    }
}
