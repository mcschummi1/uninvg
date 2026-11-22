package dev.uninvg.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Scene-aware assistant for manual exposure. Reads the live view statistics and proposes a
 * bounded shutter/ISO correction toward the balanced target. Every proposal stays inside the
 * sensor's own limits, applies through the normal settings path (nothing is ever locked), and
 * the UI temporarily silences it after a user touch so it never fights the operator.
 */
object ManualAssist {
    const val TARGET = 0.16
    const val DEAD_BAND_EV = 0.35
    const val MAX_STEP_EV = 1.0

    data class Suggestion(val shutterNs: Long, val iso: Int)

    fun suggest(stats: SceneStats, caps: CameraCaps, settings: NvSettings, bias: Float): Suggestion? {
        require(stats.tiles.size == 16) { "Expected 16 normalized tiles" }
        require(stats.p10 <= stats.p50 && stats.p50 <= stats.p90) { "Scene percentiles must be ordered" }
        val luminance = max(1e-5, (stats.p50 * 0.7 + stats.mean * 0.3).toDouble())
        val target = TARGET * 2.0.pow(bias.toDouble())
        var errorEv = ln(target / luminance) / ln(2.0)
        if (stats.mean > 0.3f && (stats.clippedFraction > 0.03f || stats.p90 > 0.96f)) {
            errorEv = min(errorEv, ln(0.8 / max(stats.p90.toDouble(), 0.8)) / ln(2.0))
        }
        if (errorEv in -DEAD_BAND_EV..DEAD_BAND_EV) return null
        val wanted = settings.manualShutterNs.toDouble() * settings.manualIso * 2.0.pow(errorEv.coerceIn(-MAX_STEP_EV, MAX_STEP_EV))
        val shutter = (wanted / settings.manualIso).toLong().coerceIn(caps.exposureMinNs, ExposurePlanner.maxExposureNs(caps, settings.fps))
        val iso = (wanted / shutter).toInt().coerceIn(caps.isoMin, caps.isoMax)
        if (shutter == settings.manualShutterNs && iso == settings.manualIso) return null
        return Suggestion(shutter, iso)
    }
}
