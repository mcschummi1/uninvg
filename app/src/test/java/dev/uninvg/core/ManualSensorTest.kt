package dev.uninvg.core

import org.junit.Assert.*
import org.junit.Test

class ManualSensorTest {
    private val caps = CameraCaps(true, 37, 3200, 800, 100_000, 2_000_000_000, 2_000_000_000, 8_000_000)
    private val manual = NvSettings(automatic = false, fps = 10f, manualIso = 500, manualShutterNs = 20_000_000)

    @Test fun shutterAndIsoAreIndependentOfExposureProductAndDigitalGain() {
        val plan = ExposurePlanner.plan(caps, manual, 1.0)
        assertEquals(20_000_000L, plan.exposureNs)
        assertEquals(500, plan.iso)
        assertEquals(plan, ExposurePlanner.plan(caps, manual.copy(digitalGain = 16f, exposureBias = 2f), Double.MAX_VALUE))
    }
    @Test fun changingIsoDoesNotChangeShutter() {
        val a = ExposurePlanner.plan(caps, manual, 1.0)
        val b = ExposurePlanner.plan(caps, manual.copy(manualIso = 1600), 1.0)
        assertEquals(a.exposureNs, b.exposureNs)
        assertEquals(1600, b.iso)
    }
    @Test fun changingShutterDoesNotChangeIso() {
        val plan = ExposurePlanner.plan(caps, manual.copy(manualShutterNs = 50_000_000), 1.0)
        assertEquals(50_000_000L, plan.exposureNs)
        assertEquals(500, plan.iso)
    }
    @Test fun clampsOnlyToHardwareBoundsAndCadence() {
        for (fps in SUPPORTED_FPS) {
            val plan = ExposurePlanner.plan(caps, manual.copy(fps = fps, manualIso = Int.MAX_VALUE, manualShutterNs = Long.MAX_VALUE), 1.0)
            assertEquals(caps.isoMax, plan.iso)
            assertEquals(ExposurePlanner.maxExposureNs(caps, fps), plan.exposureNs)
            assertTrue(plan.frameDurationNs in plan.exposureNs..caps.maxFrameDurationNs)
        }
        val minimum = ExposurePlanner.plan(caps, manual.copy(manualIso = 1, manualShutterNs = 1), 1.0)
        assertEquals(caps.isoMin, minimum.iso)
        assertEquals(caps.exposureMinNs, minimum.exposureNs)
    }
    @Test fun autoModeDoesNotUseStoredManualValues() {
        val auto = manual.copy(automatic = true)
        assertEquals(ExposurePlanner.plan(caps, auto, 1e9), ExposurePlanner.plan(caps, auto.copy(manualIso = 1, manualShutterNs = 1), 1e9))
    }
    @Test fun aeFallbackNeverClaimsToApplyManualValues() {
        val plan = ExposurePlanner.plan(caps.copy(manualSensor = false, aeFpsRanges = listOf(FpsRange(10, 30))), manual, 1e9)
        assertFalse(plan.manual)
        assertEquals(FpsRange(10, 30), plan.aeRange)
    }
    @Test fun logarithmicSlidersCoverBothEndpointsAndMonotonicValues() {
        val scale = LogScale(37.0, 3200.0)
        assertEquals(37.0, scale.value(0), 0.0)
        assertEquals(3200.0, scale.value(1000), 0.0)
        assertEquals(kotlin.math.sqrt(37.0 * 3200), scale.value(500), 0.00001)
        for (progress in 0..1000) assertEquals(progress, scale.progress(scale.value(progress)))
    }
    @Test fun singleValueAndOutOfRangeSliderValuesAreBounded() {
        val fixed = LogScale(100.0, 100.0)
        assertEquals(100.0, fixed.value(500), 0.0)
        assertEquals(0, fixed.progress(1.0))
        val scale = LogScale(50.0, 1000.0)
        assertEquals(0, scale.progress(-1.0))
        assertEquals(1000, scale.progress(Double.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { scale.progress(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LogScale(0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { NvSettings(manualIso = 0) }
    }
}
