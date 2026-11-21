package dev.uninvg.core

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class AutoControllerTest {
    private val caps = CameraCaps(
        manualSensor = true, isoMin = 50, isoMax = 800, maxAnalogIso = 400,
        exposureMinNs = 100_000, exposureMaxNs = 1_000_000_000, maxFrameDurationNs = 1_000_000_000
    )
    private val settings = NvSettings()
    private val black = scene(0f)
    private val start = ExposurePlanner.plan(caps, settings, 50_000_000.0)

    private fun scene(value: Float) = SceneAnalyzer.analyze(FloatArray(32 * 32) { value }, 32, 32)
    private fun result(plan: CapturePlan, time: Long) = CaptureMetadata(time, plan.exposureNs, plan.iso, plan.frameDurationNs)
    private fun product(plan: CapturePlan) = plan.exposureNs.toDouble() * plan.iso

    @Test
    fun blackAndOutlineFramesRampSensorBeforeAnyAutomaticDigitalGain() {
        val outlines = SceneAnalyzer.analyze(FloatArray(1024) { if (it % 32 == 16) 0.035f else 0.004f }, 32, 32)
        for (stats in listOf(black, outlines)) {
            val controller = AutoController()
            val first = controller.update(stats, result(start, 0), start, settings, 0)
            val next = controller.update(stats, result(start, 100_000_000), start, settings, 100_000_000)
            assertTrue(next.exposureProduct > first.exposureProduct)
            assertEquals(1f, next.enhancement.gain, 0f)
            assertTrue(log2(next.exposureProduct / first.exposureProduct) <= 0.150001)
        }
    }

    @Test
    fun sensorSaturationEnablesBoundedDigitalWithoutBlackFrameRunaway() {
        val controller = AutoController()
        var plan = start
        var decision = controller.update(black, result(plan, 0), plan, settings, 0)
        var reachedSaturation = false
        val maximum = product(ExposurePlanner.plan(caps, settings, Double.MAX_VALUE))
        for (frame in 1..200) {
            val time = frame * 100_000_000L
            decision = controller.update(black, result(plan, time), plan, settings, time)
            assertTrue(decision.exposureProduct.isFinite())
            assertTrue(decision.exposureProduct <= maximum * 2.0.pow(0.15) * 1.001)
            if (!plan.sensorGainExhausted) assertEquals(1f, decision.enhancement.gain, 0f)
            reachedSaturation = reachedSaturation || plan.sensorGainExhausted
            plan = ExposurePlanner.plan(caps, settings, decision.exposureProduct)
        }
        assertTrue(reachedSaturation)
        assertEquals(2f, decision.enhancement.gain, 0.001f)
        assertEquals(maximum, decision.exposureProduct, maximum * 0.001)
    }

    @Test
    fun brightOriginalFramesRecoverBothSensorAndDigitalGain() {
        val controller = AutoController()
        var plan = ExposurePlanner.plan(caps, settings, Double.MAX_VALUE)
        val dark = scene(0.025f).copy(edgeEnergy = 0.04f, noise = 0.002f)
        var decision = controller.update(dark, result(plan, 0), plan, settings, 0)
        for (frame in 1..40) {
            val time = frame * 100_000_000L
            decision = controller.update(dark, result(plan, time), plan, settings, time)
        }
        assertTrue(decision.enhancement.gain > 2f)
        val before = decision.exposureProduct
        for (frame in 41..70) {
            val time = frame * 100_000_000L
            decision = controller.update(scene(0.8f), result(plan, time), plan, settings, time)
            plan = ExposurePlanner.plan(caps, settings, decision.exposureProduct)
        }
        assertEquals(1f, decision.enhancement.gain, 0.001f)
        assertTrue(decision.exposureProduct < before / 4)
    }

    @Test
    fun unknownAndUnmatchedMetadataNeverMasqueradeAsAppliedExposure() {
        val controller = AutoController()
        val saturated = ExposurePlanner.plan(caps, settings, Double.MAX_VALUE)
        val first = controller.update(black, null, saturated, settings, 0)
        val invalid = listOf<CaptureMetadata?>(
            null,
            CaptureMetadata(200_000_000, null, saturated.iso, null),
            CaptureMetadata(300_000_000, saturated.exposureNs, null, null),
            CaptureMetadata(400_000_000, saturated.exposureNs, 0, null),
            CaptureMetadata(450_000_000, saturated.exposureNs, saturated.iso, null),
            CaptureMetadata(700_000_000, saturated.exposureNs, saturated.iso, null),
            CaptureMetadata(700_000_000, saturated.exposureNs / 2, saturated.iso * 2, null)
        )
        invalid.forEachIndexed { index, metadata ->
            val time = (index + 1) * 100_000_000L
            val decision = controller.update(black, metadata, saturated, settings, time)
            assertEquals(first.exposureProduct, decision.exposureProduct, 0.0)
            assertEquals(1f, decision.enhancement.gain, 0f)
        }
        val time = 800_000_000L
        val mismatched = result(start, time)
        val held = controller.update(black, mismatched, saturated, settings, time)
        assertEquals(first.exposureProduct, held.exposureProduct, 0.0)
        val confirmed = controller.update(black, result(saturated, time + 100_000_000), saturated, settings, time + 100_000_000)
        assertTrue(confirmed.enhancement.gain > 1f)
    }

    @Test
    fun duplicateAndOutOfOrderFramesDoNotConsumeTimeOrModifyState() {
        val controller = AutoController()
        controller.update(black, result(start, 0), start, settings, 0)
        val accepted = controller.update(black, result(start, 100_000_000), start, settings, 100_000_000)
        for (time in listOf(100_000_000L, 50_000_000L, 0L)) {
            assertEquals(accepted, controller.update(scene(1f), result(start, time), start, settings, time))
        }
        val plan = ExposurePlanner.plan(caps, settings, accepted.exposureProduct)
        val next = controller.update(black, result(plan, 200_000_000), plan, settings, 200_000_000)
        assertTrue(log2(next.exposureProduct / accepted.exposureProduct) in 0.149..0.151)
        val paused = controller.update(black, result(plan, 100_000_000_000), plan, settings, 100_000_000_000)
        assertTrue(log2(paused.exposureProduct / next.exposureProduct) <= 0.750001)
        val changedCadence = AutoController()
        val slow = ExposurePlanner.plan(caps, settings.copy(fps = 1f), Double.MAX_VALUE)
        val fast = ExposurePlanner.plan(caps, settings.copy(fps = 30f), Double.MAX_VALUE)
        val before = changedCadence.update(black, result(slow, 0), slow, settings.copy(fps = 1f), 0)
        val after = changedCadence.update(black, result(fast, 100_000_000), fast, settings.copy(fps = 30f), 100_000_000)
        assertTrue(log2(after.exposureProduct / before.exposureProduct) >= -0.300001)
    }

    @Test
    fun exposureRateDependsOnElapsedTimeNotCallbackFrequency() {
        fun run(stepNs: Long, frames: Int): Double {
            val controller = AutoController()
            var plan = start
            var decision = controller.update(black, result(plan, 0), plan, settings, 0)
            for (frame in 1..frames) {
                val time = stepNs * frame
                decision = controller.update(black, result(plan, time), plan, settings, time)
                plan = ExposurePlanner.plan(caps, settings, decision.exposureProduct)
            }
            return decision.exposureProduct
        }
        val fine = run(50_000_000, 20)
        val coarse = run(100_000_000, 10)
        assertEquals(fine, coarse, fine * 0.0001)
        assertEquals(1.5, log2(fine / product(start)), 0.001)
    }

    @Test
    fun repeatedOriginalInputHasNoDigitalFeedbackOrUnboundedIntegration() {
        val controller = AutoController()
        val stats = scene(0.03f)
        controller.update(stats, result(start, 0), start, settings, 0)
        val first = controller.update(stats, result(start, 100_000_000), start, settings, 100_000_000)
        for (frame in 2..300) {
            val time = frame * 100_000_000L
            val decision = controller.update(stats, result(start, time), start, settings, time)
            assertEquals(first.exposureProduct, decision.exposureProduct, 1e-6)
            assertEquals(1f, decision.enhancement.gain, 0f)
            assertEquals(settings.gamma, decision.enhancement.gamma, 0f)
        }
    }

    @Test
    fun noiseLimitsDigitalWhileHysteresisAvoidsNearTargetOscillation() {
        fun gain(noise: Float): Float {
            val controller = AutoController()
            val plan = ExposurePlanner.plan(caps, settings, Double.MAX_VALUE)
            val stats = scene(0.02f).copy(edgeEnergy = 0.05f, noise = noise)
            var decision = controller.update(stats, result(plan, 0), plan, settings, 0)
            for (frame in 1..40) {
                val time = frame * 100_000_000L
                decision = controller.update(stats, result(plan, time), plan, settings, time)
            }
            return decision.enhancement.gain
        }
        assertTrue(gain(0.1f) < gain(0.001f))
        val controller = AutoController()
        controller.update(scene(0.16f), result(start, 0), start, settings, 0)
        for (frame in 1..20) {
            val time = frame * 100_000_000L
            val value = if (frame % 2 == 0) 0.15f else 0.17f
            val decision = controller.update(scene(value), result(start, time), start, settings, time)
            assertEquals(product(start), decision.exposureProduct, 1e-6)
        }
    }

    @Test
    fun manualDigitalSettingsSurviveDisablingAutomaticAndResetClearsHistory() {
        val controller = AutoController()
        val saturated = ExposurePlanner.plan(caps, settings, Double.MAX_VALUE)
        controller.update(black, result(saturated, 0), saturated, settings, 0)
        controller.update(black, result(saturated, 500_000_000), saturated, settings, 500_000_000)
        val manual = settings.copy(automatic = false, digitalGain = 3.5f, gamma = 1.7f)
        val decision = controller.update(black, null, start, manual, 600_000_000)
        assertEquals(3.5f, decision.enhancement.gain, 0f)
        assertEquals(1.7f, decision.enhancement.gamma, 0f)
        assertEquals(product(start), decision.exposureProduct, 0.0)
        controller.reset()
        val fresh = AutoController().update(black, result(start, 0), start, settings, 0)
        assertEquals(fresh, controller.update(black, result(start, 0), start, settings, 0))
    }

    @Test
    fun regionalMultipliersAttenuateHighlightsWithBoundedSpatialNeighbors() {
        val tiles = List(16) { if (it == 15) 1f else 0.015f }
        val stats = scene(0.015f).copy(tiles = tiles)
        val manual = settings.copy(automatic = false, regional = true, digitalGain = 8f)
        val decision = AutoController().update(stats, null, start, manual, 0)
        val gains = decision.enhancement.regionalGains
        assertEquals(16, gains.size)
        assertTrue(gains[0] > gains[15])
        assertTrue(gains[15] < 1f)
        assertTrue(gains.all { it in 0.125f..1f })
        for (tile in 0 until 16) {
            for (neighbor in listOfNotNull(if (tile % 4 < 3) tile + 1 else null, if (tile / 4 < 3) tile + 4 else null)) {
                assertTrue(abs(log2(gains[tile].toDouble() / gains[neighbor])) <= 0.500001)
            }
        }
        val uniform = AutoController().update(scene(0.01f), null, start, manual, 0)
        assertEquals(List(16) { 1f }, uniform.enhancement.regionalGains)
        val disabled = AutoController().update(stats, null, start, manual.copy(regional = false), 0)
        assertEquals(List(16) { 1f }, disabled.enhancement.regionalGains)
    }

    @Test
    fun brightWindowDoesNotSuppressDarkRoomExposure() {
        val stats = SceneAnalyzer.analyze(FloatArray(1024) { if (it < 64) 1f else 0.005f }, 32, 32)
        val controller = AutoController()
        val first = controller.update(stats, result(start, 0), start, settings, 0)
        val next = controller.update(stats, result(start, 200_000_000), start, settings, 200_000_000)
        assertTrue(next.exposureProduct > first.exposureProduct)
    }

    @Test
    fun stillPhotoGetsAutomaticGainWithoutInventingSensorMetadata() {
        val plan = start.copy(manual = false)
        val decision = AutoController().update(scene(0.02f), null, plan, settings, 0, stillImage = true)
        assertTrue(decision.enhancement.gain > 1f)
        assertEquals(plan.exposureNs.toDouble() * plan.iso, decision.exposureProduct, 0.0)
    }

    @Test
    fun regionalGainChangesAreTemporallySmoothed() {
        val controller = AutoController()
        val manual = settings.copy(automatic = false, regional = true, digitalGain = 8f)
        controller.update(scene(0.01f), null, start, manual, 0)
        val stats = scene(0.01f).copy(tiles = List(16) { if (it == 15) 1f else 0.01f })
        val next = controller.update(stats, null, start, manual, 100_000_000)
        val instant = AutoController().update(stats, null, start, manual, 100_000_000)
        assertTrue(next.enhancement.regionalGains[15] > instant.enhancement.regionalGains[15])
        assertTrue(next.enhancement.regionalGains[15] < 1f)
    }

    @Test
    fun invalidStatsFailFastAndAeDoesNotClaimSaturationFromRequestedExposure() {
        val controller = AutoController()
        assertThrows(IllegalArgumentException::class.java) { controller.update(black.copy(mean = Float.NaN), null, start, settings, 0) }
        assertThrows(IllegalArgumentException::class.java) { controller.update(black.copy(tiles = emptyList()), null, start, settings, 0) }
        assertThrows(IllegalArgumentException::class.java) { controller.update(black, null, start, settings, -1) }
        val ae = ExposurePlanner.plan(caps.copy(manualSensor = false), settings, Double.MAX_VALUE)
        controller.update(black, result(ae, 0), ae, settings, 0)
        val decision = controller.update(black, result(ae, 100_000_000), ae, settings, 100_000_000)
        assertTrue(decision.enhancement.gain > 1f)
        assertEquals(ae.exposureNs.toDouble() * ae.iso, decision.exposureProduct, 0.0)
        assertNull(CaptureMetadata(0, null, null, null).exposureProduct)
    }

    @Test
    fun deviceGainCeilingBoundsAutoGainWithoutOverridingTheManualSetting() {
        val ae = ExposurePlanner.plan(caps.copy(manualSensor = false), settings, Double.MAX_VALUE)
        val capped = AutoController().update(black, null, ae, settings.copy(digitalGain = 1f), 0, true, 1.5f)
        assertEquals(1.5f, capped.enhancement.gain, 1e-4f)
        val userOwn = AutoController().update(black, null, ae, settings.copy(digitalGain = 12f), 0, true, 8f)
        assertEquals(12f, userOwn.enhancement.gain, 1e-4f)
        val open = AutoController().update(black, null, ae, settings.copy(digitalGain = 1f), 0, true, 16f)
        assertTrue(open.enhancement.gain > capped.enhancement.gain)
    }
}
