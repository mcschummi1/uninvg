package dev.uninvg

import dev.uninvg.render.FrameCadence
import org.junit.Assert.*
import org.junit.Test

class FrameCadenceTest {
    @Test fun smallSensorJitterDoesNotHalveThirtyFps() {
        val cadence = FrameCadence()
        val accepted = (0 until 300).count { cadence.accept(1_000_000_000L + it * 33_333_000L, 30f) }
        assertEquals(300, accepted)
    }
    @Test fun thirtyCaptureFramesYieldOneNewOutputPerSecond() {
        val cadence = FrameCadence()
        val accepted = (0 until 300).count { cadence.accept(1_000_000_000L + it * 33_333_333L, 1f) }
        assertEquals(10, accepted)
    }
    @Test fun settingsAndSourceChangesResetCadence() {
        val cadence = FrameCadence()
        assertTrue(cadence.accept(9_000_000_000L, 1f))
        assertFalse(cadence.accept(9_000_000_000L, 1f))
        assertTrue(cadence.accept(9_020_000_000L, 30f))
        assertTrue(cadence.accept(1_000_000_000L, 30f))
    }
}
