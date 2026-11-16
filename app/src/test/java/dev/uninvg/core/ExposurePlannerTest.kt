package dev.uninvg.core

import org.junit.Assert.*
import org.junit.Test

class ExposurePlannerTest {
    private val caps = CameraCaps(
        manualSensor = true, isoMin = 64, isoMax = 6400, maxAnalogIso = 800,
        exposureMinNs = 125_000, exposureMaxNs = 2_000_000_000,
        maxFrameDurationNs = 2_000_000_000, minFrameDurationNs = 16_666_666
    )

    @Test
    fun allSupportedCadencesReserveReadoutAndExhaustOnlyTheAvailableSensorBudget() {
        for (fps in SUPPORTED_FPS) {
            val plan = ExposurePlanner.plan(caps, NvSettings(fps = fps), Double.MAX_VALUE)
            val budget = frameBudgetNs(fps)
            assertTrue(plan.manual)
            assertEquals(fps, plan.requestedFps)
            val achievableBudget = budget.coerceIn(caps.minFrameDurationNs, caps.maxFrameDurationNs)
            assertEquals(achievableBudget, plan.frameDurationNs)
            assertTrue(plan.exposureNs in caps.exposureMinNs until minOf(budget, caps.maxFrameDurationNs))
            assertTrue(minOf(budget, caps.maxFrameDurationNs) - plan.exposureNs in 1_000_000L..5_000_000L)
            assertEquals(caps.isoMax, plan.iso)
            assertTrue(plan.sensorGainExhausted)
            assertEquals(achievableBudget != budget, plan.cadenceLimited)
            assertNull(plan.aeRange)
        }
    }

    @Test
    fun exposurePrecedesAnalogThenRemainingSensitivity() {
        val settings = NvSettings(fps = 15f)
        val ceiling = ExposurePlanner.plan(caps, settings, Double.MAX_VALUE).exposureNs
        val low = ExposurePlanner.plan(caps, settings, 10_000_000.0 * caps.isoMin)
        assertEquals(10_000_000L, low.exposureNs)
        assertEquals(caps.isoMin, low.iso)
        val analog = ExposurePlanner.plan(caps, settings, ceiling.toDouble() * 400)
        assertEquals(ceiling, analog.exposureNs)
        assertEquals(400, analog.iso)
        assertFalse(analog.sensorGainExhausted)
        val extended = ExposurePlanner.plan(caps, settings, ceiling.toDouble() * 1600)
        assertEquals(ceiling, extended.exposureNs)
        assertEquals(1600, extended.iso)
        assertFalse(extended.sensorGainExhausted)
        assertTrue(ExposurePlanner.plan(caps, settings, ceiling.toDouble() * caps.isoMax).sensorGainExhausted)
    }

    @Test
    fun sensorBoundsAndUnknownAnalogLimitsComeOnlyFromCapabilities() {
        val unusual = caps.copy(isoMin = 37, isoMax = 913, maxAnalogIso = null, exposureMinNs = 731, exposureMaxNs = 7_123_456)
        val minimum = ExposurePlanner.plan(unusual, NvSettings(), Double.MIN_VALUE)
        assertEquals(731L, minimum.exposureNs)
        assertEquals(37, minimum.iso)
        val maximum = ExposurePlanner.plan(unusual, NvSettings(), Double.MAX_VALUE)
        assertEquals(7_123_456L, maximum.exposureNs)
        assertEquals(913, maximum.iso)
        assertTrue(maximum.sensorGainExhausted)
        for (analog in listOf(-1, 37, 913, Int.MAX_VALUE)) {
            assertEquals(maximum, ExposurePlanner.plan(unusual.copy(maxAnalogIso = analog), NvSettings(), Double.MAX_VALUE))
        }
    }

    @Test
    fun streamThrottlingDoesNotBecomeExtraExposureTime() {
        val throttled = caps.copy(minFrameDurationNs = 100_000_000)
        val requested = NvSettings(fps = 30f)
        val fast = ExposurePlanner.plan(caps, requested, Double.MAX_VALUE)
        val slow = ExposurePlanner.plan(throttled, requested, Double.MAX_VALUE)
        assertEquals(100_000_000L, slow.frameDurationNs)
        assertEquals(fast.exposureNs, slow.exposureNs)
        assertEquals(fast.iso, slow.iso)
        assertTrue(slow.cadenceLimited)
    }

    @Test
    fun impossibleCadenceHonorsMaximumDurationAndMinimumExposure() {
        val shortFrame = caps.copy(maxFrameDurationNs = 20_000_000)
        val fastHardware = ExposurePlanner.plan(shortFrame, NvSettings(fps = 1f), Double.MAX_VALUE)
        assertEquals(20_000_000L, fastHardware.frameDurationNs)
        assertTrue(fastHardware.exposureNs < fastHardware.frameDurationNs)
        assertTrue(fastHardware.cadenceLimited)
        val longExposure = caps.copy(exposureMinNs = 50_000_000)
        val slowHardware = ExposurePlanner.plan(longExposure, NvSettings(fps = 30f), 1.0)
        assertEquals(50_000_000L, slowHardware.exposureNs)
        assertTrue(slowHardware.frameDurationNs > slowHardware.exposureNs)
        assertTrue(slowHardware.cadenceLimited)
        val contradictory = shortFrame.copy(minFrameDurationNs = 100_000_000)
        assertThrows(IllegalArgumentException::class.java) { ExposurePlanner.plan(contradictory, NvSettings(), 1.0) }
    }

    @Test
    fun aeUsesOnlyAdvertisedExactRangesForEveryFpsChoice() {
        val exactFps = SUPPORTED_FPS.filter { it >= 1f }.map { it.toInt() }
        val ranges = exactFps.map { FpsRange(it, it) } + FpsRange(1, 30)
        val automatic = caps.copy(manualSensor = false, minFrameDurationNs = 0, aeFpsRanges = ranges)
        for (fps in SUPPORTED_FPS) {
            val plan = ExposurePlanner.plan(automatic, NvSettings(fps = fps), Double.MAX_VALUE)
            assertEquals(FpsRange(maxOf(1, fps.toInt()), maxOf(1, fps.toInt())), plan.aeRange)
            assertFalse(plan.manual)
            assertEquals(fps < 1f, plan.cadenceLimited)
            assertFalse(plan.sensorGainExhausted)
        }
    }

    @Test
    fun unsupportedAeAndVariableIntervalsAreExplicitlyLimited() {
        val ranges = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(10, 10))
        val automatic = caps.copy(manualSensor = false, aeFpsRanges = ranges)
        for ((fps, expected, limited) in listOf(
            Triple(24f, ranges[0], false), Triple(15f, ranges[0], false),
            Triple(5f, ranges[2], true), Triple(1f, ranges[2], true)
        )) {
            val plan = ExposurePlanner.plan(automatic, NvSettings(fps = fps), 1e9)
            assertEquals(expected, plan.aeRange)
            assertTrue(plan.aeRange in ranges)
            assertEquals(limited, plan.cadenceLimited)
        }
        val nearest = ExposurePlanner.plan(automatic.copy(aeFpsRanges = listOf(FpsRange(15, 15), FpsRange(30, 30))), NvSettings(fps = 24f), 1e9)
        assertEquals(FpsRange(30, 30), nearest.aeRange)
        val missing = ExposurePlanner.plan(automatic.copy(aeFpsRanges = emptyList()), NvSettings(), 1e9)
        assertNull(missing.aeRange)
        assertTrue(missing.cadenceLimited)
    }

    @Test
    fun aePrefersStreamViableRangeAndReportsThrottling() {
        val automatic = caps.copy(manualSensor = false, minFrameDurationNs = 100_000_000, aeFpsRanges = listOf(FpsRange(30, 30), FpsRange(5, 15)))
        val plan = ExposurePlanner.plan(automatic, NvSettings(fps = 30f), 1e9)
        assertEquals(FpsRange(5, 15), plan.aeRange)
        assertEquals(100_000_000L, plan.frameDurationNs)
        assertTrue(plan.cadenceLimited)
        val fixed = ExposurePlanner.plan(automatic.copy(aeFpsRanges = listOf(FpsRange(30, 30))), NvSettings(fps = 30f), 1e9)
        assertTrue(fixed.cadenceLimited)
    }

    @Test
    fun invalidExposureProductsAreRejected() {
        for (product in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { ExposurePlanner.plan(caps, NvSettings(), product) }
        }
    }
}
