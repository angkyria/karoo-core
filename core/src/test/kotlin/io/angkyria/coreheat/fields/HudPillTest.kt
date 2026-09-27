package io.angkyria.coreheat.fields

import io.angkyria.coreheat.ZoneColorMode
import io.angkyria.coreheat.heat.HeatAdaptation
import io.angkyria.coreheat.heat.HeatStrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [HudMetric.pillFrame] and [HudMetric.shown]: what the pill says, before anything is drawn. */
class HudPillTest {

    private val text = ZoneColorMode.TEXT

    @Test fun `zone pill lights one square per heat zone and shows the index`() {
        assertEquals(
            PillFrame(lit = 3, segments = HeatStrain.ZONES, color = HeatStrain.color(4.1), text = "4.1"),
            HudMetric.ZONE.pillFrame(4.1, text),
        )
    }

    @Test fun `zone pill decides on the tenth it prints, as the zone field does`() {
        // 2.96 prints as "3.0", so it is zone 3 -- not zone 2 by a margin nobody can see.
        val pill = HudMetric.ZONE.pillFrame(2.96, text)
        assertEquals("3.0", pill.text)
        assertEquals(3, pill.lit)
    }

    @Test fun `adaptation pill lights one square per level`() {
        assertEquals(
            PillFrame(lit = 3, segments = 4, color = HeatAdaptation.color(72.1), text = "72.1"),
            HudMetric.ADAPTATION.pillFrame(72.1, text),
        )
        // Thermal Rookie is a level like the others: a score of 0 lights the first square.
        assertEquals(1, HudMetric.ADAPTATION.pillFrame(0.0, text).lit)
        assertEquals(4, HudMetric.ADAPTATION.pillFrame(100.0, text).lit)
        assertEquals("100", HudMetric.ADAPTATION.pillFrame(100.0, text).text)
    }

    @Test fun `load pill counts in four squares like the others, uncoloured like its field`() {
        assertEquals(PillFrame(lit = 3, segments = 4, color = null, text = "6.1"), HudMetric.LOAD.pillFrame(6.1, ZoneColorMode.FILL))
    }

    @Test fun `load pill lights a square past 2, 4, 6 and 8`() {
        fun lit(load: Double) = HudMetric.LOAD.pillFrame(load, text).lit
        assertEquals(0, lit(0.0))
        assertEquals(0, lit(2.0))
        assertEquals(1, lit(2.1))
        assertEquals(2, lit(4.5))
        assertEquals(3, lit(8.0))
        assertEquals(4, lit(8.1))
        assertEquals(4, lit(10.0))
    }

    @Test fun `the first load square lights exactly when the day starts counting toward adaptation`() {
        for (load in listOf(1.99, 2.0, 2.0001, 2.01, 3.0)) {
            val counts = HeatAdaptation.step(HeatAdaptation.Day.START, load).restStreak == 0
            assertEquals("load $load", counts, HudMetric.LOAD.pillFrame(load, text).lit >= 1)
        }
    }

    @Test fun `no value is dashes over unlit squares`() {
        assertEquals(PillFrame(0, HeatStrain.ZONES, null, "--"), HudMetric.ZONE.pillFrame(null, text))
        assertEquals(PillFrame(0, 4, null, "--"), HudMetric.LOAD.pillFrame(null, text))
    }

    @Test fun `heat colours off keeps the count and drops the colour`() {
        val pill = HudMetric.ZONE.pillFrame(4.1, ZoneColorMode.OFF)
        assertEquals(3, pill.lit)
        assertNull(pill.color)
    }

    @Test fun `test mode shows the demo value even over live data`() {
        assertEquals(FieldCatalog.HSI_PREVIEW, HudMetric.ZONE.shown(live = 8.0, preview = false, testMode = true)!!, 0.0)
    }

    @Test fun `the page editor fills in only a missing value`() {
        assertEquals(2.0, HudMetric.ZONE.shown(live = 2.0, preview = true, testMode = false)!!, 0.0)
        assertEquals(FieldCatalog.HSI_PREVIEW, HudMetric.ZONE.shown(live = null, preview = true, testMode = false)!!, 0.0)
        assertNull(HudMetric.ZONE.shown(live = null, preview = false, testMode = false))
    }

    @Test fun `each demo pill tells the same story as its own field's demo`() {
        // The zone pill's demo index is the one the Heat Zone field's demo zone 3 comes from.
        assertEquals(3, HudMetric.ZONE.pillFrame(HudMetric.ZONE.previewValue, text).lit)
        assertEquals(FieldCatalog.LOAD_PREVIEW, HudMetric.LOAD.previewValue, 0.0)
        assertEquals(FieldCatalog.ADAPTATION_PREVIEW, HudMetric.ADAPTATION.previewValue, 0.0)
    }
}
