package dev.uninvg.core

import org.junit.Assert.*
import org.junit.Test

class ManualAssistTest {
    private val caps = CameraCaps(
        manualSensor = true, isoMin = 50, isoMax = 6400, maxAnalogIso = 800,
        exposureMinNs = 10_000, exposureMaxNs = 2_000_000_000, maxFrameDurationNs = 4_000_000_000
    )
    private val settings = NvSettings(fps = 15f, manualShutterNs = 63_000_000, manualIso = 800)

    private fun stats(mean: Float, clipped: Float = 0f, noise: Float = 0.01f) =
        SceneStats(mean, mean * 0.5f, mean, mean, clipped, noise, 0.2f, List(16) { mean }, SceneKind.DARK)

    private fun apply(current: NvSettings, scene: SceneStats, caps: CameraCaps, limit: Int): NvSettings {
        var settings = current
        for (step in 0 until limit) {
            val suggestion = ManualAssist.suggest(scene, caps, settings, 0f) ?: break
            settings = settings.copy(manualShutterNs = suggestion.shutterNs, manualIso = suggestion.iso)
        }
        return settings
    }

    @Test
    fun darkViewAsksForMoreLightAndBrightViewForLess() {
        val dark = ManualAssist.suggest(stats(0.005f), caps, settings, 0f)
        assertNotNull(dark)
        assertTrue(dark!!.shutterNs * dark.iso > settings.manualShutterNs.toDouble() * settings.manualIso)

        val bright = ManualAssist.suggest(stats(0.45f), caps, settings, 0f)
        assertNotNull(bright)
        assertTrue(bright!!.shutterNs * bright.iso < settings.manualShutterNs.toDouble() * settings.manualIso)
    }

    @Test
    fun balancedViewLeavesTheOperatorAlone() {
        assertNull(ManualAssist.suggest(stats(0.16f), caps, settings, 0f))
        assertNull(ManualAssist.suggest(stats(0.19f), caps, settings, 0f))
        assertNull(ManualAssist.suggest(stats(0.14f), caps, settings, 0f))
    }

    @Test
    fun exposureBiasShiftsTheTargetButNeverLocksIt() {
        val dimmer = ManualAssist.suggest(stats(0.16f), caps, settings, -2f)
        assertNotNull(dimmer)
        assertTrue(dimmer!!.shutterNs * dimmer.iso < settings.manualShutterNs.toDouble() * settings.manualIso)
        val brighter = ManualAssist.suggest(stats(0.16f), caps, settings, 2f)
        assertNotNull(brighter)
        assertTrue(brighter!!.shutterNs * brighter.iso > settings.manualShutterNs.toDouble() * settings.manualIso)
    }

    @Test
    fun repeatedProposalsStayInsideSensorLimitsAndPreferShutterFirst() {
        val ceiling = ExposurePlanner.maxExposureNs(caps, 15f)
        val pitchDark = apply(settings, stats(0.00001f), caps, 24)
        assertEquals(ceiling, pitchDark.manualShutterNs)
        assertEquals(caps.isoMax, pitchDark.manualIso)

        val sunlit = apply(settings.copy(manualIso = 3200), stats(0.45f), caps, 24)
        assertEquals(caps.exposureMinNs, sunlit.manualShutterNs)
        assertEquals(caps.isoMin, sunlit.manualIso)
    }

    @Test
    fun smallCorrectionStepsKeepThePaceStable() {
        val step = ManualAssist.suggest(stats(0.00001f), caps, settings, 0f)
        val before = settings.manualShutterNs.toDouble() * settings.manualIso
        val after = step!!.shutterNs.toDouble() * step.iso
        assertTrue(kotlin.math.ln(after / before) / kotlin.math.ln(2.0) <= ManualAssist.MAX_STEP_EV + 0.01)
    }
}
