package dev.uninvg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    @Test fun systemStatusesMapToGuardianLevels() {
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.levelForStatus(0))
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.levelForStatus(1))
        assertEquals(ThermalPolicy.WARM, ThermalPolicy.levelForStatus(2))
        assertEquals(ThermalPolicy.HOT, ThermalPolicy.levelForStatus(3))
        assertEquals(ThermalPolicy.HOT, ThermalPolicy.levelForStatus(6))
    }

    @Test fun batteryHeatStartsAtFortyDegreesAndHotAtFortyEight() {
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.heatLevel(395, null))
        assertEquals(ThermalPolicy.WARM, ThermalPolicy.heatLevel(400, null))
        assertEquals(ThermalPolicy.WARM, ThermalPolicy.heatLevel(470, null))
        assertEquals(ThermalPolicy.HOT, ThermalPolicy.heatLevel(480, null))
    }

    @Test fun siliconHeatStartsAtSixtyDegreesAndHotAtSeventy() {
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.heatLevel(null, 590))
        assertEquals(ThermalPolicy.WARM, ThermalPolicy.heatLevel(null, 600))
        assertEquals(ThermalPolicy.WARM, ThermalPolicy.heatLevel(null, 690))
        assertEquals(ThermalPolicy.HOT, ThermalPolicy.heatLevel(null, 700))
    }

    @Test fun implausibleReadingsAreIgnored() {
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.heatLevel(null, null))
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.heatLevel(9_999, null))
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.heatLevel(null, 42))
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.heatLevel(-50, 20))
    }

    @Test fun strongestSignalWins() {
        assertEquals(ThermalPolicy.WARM, ThermalPolicy.effective(0, 410, null))
        assertEquals(ThermalPolicy.HOT, ThermalPolicy.effective(0, 410, 700))
        assertEquals(ThermalPolicy.HOT, ThermalPolicy.effective(3, null, null))
        assertEquals(ThermalPolicy.NORMAL, ThermalPolicy.effective(0, 300, 500))
    }

    @Test fun pacingCapsNeverExceedTheUserChoice() {
        assertEquals(30f, ThermalPolicy.renderFpsCap(ThermalPolicy.WARM, 120f), 1e-6f)
        assertEquals(15f, ThermalPolicy.renderFpsCap(ThermalPolicy.HOT, 120f), 1e-6f)
        assertEquals(120f, ThermalPolicy.renderFpsCap(ThermalPolicy.NORMAL, 120f), 1e-6f)
        assertEquals(5f, ThermalPolicy.renderFpsCap(ThermalPolicy.HOT, 5f), 1e-6f)
    }

    @Test fun stacksSlimAsHeatRisesAndRecover() {
        assertEquals(8, ThermalPolicy.stackCap(ThermalPolicy.WARM, 16))
        assertEquals(2, ThermalPolicy.stackCap(ThermalPolicy.HOT, 16))
        assertEquals(4, ThermalPolicy.stackCap(ThermalPolicy.WARM, 4))
        assertEquals(16, ThermalPolicy.stackCap(ThermalPolicy.NORMAL, 16))
    }

    @Test fun sharpeningEasesOnHotSilicon() {
        assertTrue(ThermalPolicy.sharpenScale(ThermalPolicy.HOT) < ThermalPolicy.sharpenScale(ThermalPolicy.WARM))
        assertTrue(ThermalPolicy.sharpenScale(ThermalPolicy.WARM) < ThermalPolicy.sharpenScale(ThermalPolicy.NORMAL))
    }
}
