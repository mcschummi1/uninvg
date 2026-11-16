package dev.uninvg.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object ExposurePlanner {
    fun plan(caps: CameraCaps, settings: NvSettings, exposureProduct: Double): CapturePlan {
        require(exposureProduct.isFinite() && exposureProduct > 0.0) { "Exposure product must be finite and positive" }
        require(!caps.manualSensor || caps.minFrameDurationNs <= caps.maxFrameDurationNs) {
            "Stream minimum exceeds the sensor's maximum frame duration"
        }
        val budget = frameBudgetNs(settings.fps)
        val reserve = (budget / 20).coerceIn(1_000_000L, 5_000_000L)
            .coerceAtMost(caps.maxFrameDurationNs / 10).coerceAtLeast(1L)
        val exposureCeiling = min(caps.exposureMaxNs, min(budget, caps.maxFrameDurationNs) - reserve)
            .coerceAtLeast(caps.exposureMinNs)
        val exposure = ceil(exposureProduct / caps.isoMin).toLong()
            .coerceIn(caps.exposureMinNs, exposureCeiling)
        val requiredIso = ceil(exposureProduct / exposure).toInt()
        val analogIso = requiredIso.coerceIn(caps.isoMin, caps.analogCeiling)
        val iso = if (exposureProduct > exposure.toDouble() * analogIso) {
            requiredIso.coerceIn(caps.analogCeiling, caps.isoMax)
        } else {
            analogIso
        }
        if (!caps.manualSensor) {
            val range = selectAeRange(caps.aeFpsRanges, settings.fps, caps.minFrameDurationNs)
            val nominalFps = range?.let { settings.fps.coerceIn(it.lower.toFloat(), it.upper.toFloat()) } ?: settings.fps
            val duration = max((1_000_000_000.0 / nominalFps).toLong(), caps.minFrameDurationNs)
            return CapturePlan(
                false, exposure, iso, duration, range, settings.fps,
                range == null || nominalFps != settings.fps || duration != budget,
                false
            )
        }
        val exposureWithReadout = if (exposure > Long.MAX_VALUE - reserve) Long.MAX_VALUE else exposure + reserve
        val duration = max(max(budget, caps.minFrameDurationNs), exposureWithReadout)
            .coerceAtMost(caps.maxFrameDurationNs)
        return CapturePlan(
            true, exposure, iso, duration, null, settings.fps,
            duration != budget || duration < caps.minFrameDurationNs || duration < exposureWithReadout,
            exposure == exposureCeiling && iso == caps.isoMax
        )
    }

    private fun selectAeRange(ranges: List<FpsRange>, fps: Float, streamMinimum: Long): FpsRange? {
        val streamCeiling = if (streamMinimum == 0L) Double.POSITIVE_INFINITY else 1_000_000_000.0 / streamMinimum
        return ranges.minWithOrNull(
            compareBy<FpsRange> { it.lower > streamCeiling }
                .thenBy {
                    val upper = min(it.upper.toDouble(), streamCeiling)
                    max(max(it.lower - fps.toDouble(), fps - upper), 0.0)
                }
                .thenBy { it.upper - it.lower }
                .thenBy { it.upper }
                .thenBy { it.lower }
        )
    }
}
