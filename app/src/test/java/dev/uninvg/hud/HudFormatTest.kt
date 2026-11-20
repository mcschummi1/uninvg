package dev.uninvg.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudFormatTest {
    @Test fun batteryAndTemperatureRenderTogether() {
        assertEquals("Battery 87%  \u00b7  Temp 31.4\u00b0C", HudFormat.batteryLine(87, 314))
    }

    @Test fun partialReadingsStillRender() {
        assertEquals("Battery 87%", HudFormat.batteryLine(87, null))
        assertEquals("Temp 31.4\u00b0C", HudFormat.batteryLine(null, 314))
    }

    @Test fun missingReadingsHideTheLine() {
        assertNull(HudFormat.batteryLine(null, null))
    }

    @Test fun batteryPercentIsClamped() {
        assertEquals("Battery 100%  \u00b7  Temp -2.5\u00b0C", HudFormat.batteryLine(420, -25))
        assertEquals("Battery 0%", HudFormat.batteryLine(-3, null))
    }
}
