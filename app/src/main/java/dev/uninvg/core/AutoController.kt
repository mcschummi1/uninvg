package dev.uninvg.core

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class AutoController {
    private var lastTimestampNs: Long? = null
    private var lastDecision: AutoDecision? = null
    private var direction = 0
    private var gainEv = 0.0

    @Synchronized
    fun reset() {
        lastTimestampNs = null
        lastDecision = null
        direction = 0
        gainEv = 0.0
    }

    /** Stats and metadata must describe the same unenhanced frame. The first frame primes the clock. */
    @Synchronized
    fun update(
        stats: SceneStats,
        metadata: CaptureMetadata?,
        plan: CapturePlan,
        settings: NvSettings,
        timestampNs: Long,
        stillImage: Boolean = false
    ): AutoDecision {
        validate(stats, plan, timestampNs)
        val plannedProduct = plan.exposureNs.toDouble() * plan.iso
        val previous = lastDecision
        val previousTime = lastTimestampNs
        val ordered = previousTime == null || timestampNs > previousTime
        if (!ordered && settings.automatic) return requireNotNull(previous)
        val elapsed = if (previousTime == null || !ordered) 0.0 else
            ((timestampNs - previousTime) * 1e-9).coerceAtMost(0.5)
        if (ordered) lastTimestampNs = timestampNs
        var request = previous?.exposureProduct ?: plannedProduct
        if (!settings.automatic) {
            direction = 0
            gainEv = 0.0
            request = plannedProduct
        } else if (ordered) {
            val measured = matchedProduct(metadata, plan, timestampNs)
            val luminance = max(0.00001, stats.p50 * 0.7 + stats.mean * 0.3)
            val target = 0.16 * 2.0.pow(settings.exposureBias.toDouble())
            var errorEv = log2(target / luminance).coerceIn(-8.0, 8.0)
            if (stats.mean > 0.3f && (stats.clippedFraction > 0.03f || stats.p90 > 0.96f)) {
                errorEv = min(errorEv, log2(0.8 / max(stats.p90.toDouble(), 0.8)))
            }
            direction = when {
                errorEv > 0.28 -> 1
                errorEv < -0.28 -> -1
                direction == 1 && errorEv > 0.10 -> 1
                direction == -1 && errorEv < -0.10 -> -1
                else -> 0
            }
            if (plan.manual && measured != null && elapsed > 0.0) {
                val correction = if (direction == 0) 0.0 else errorEv
                val sensorStep = correction.coerceIn(-3.0 * elapsed, 1.5 * elapsed)
                val candidate = (measured * 2.0.pow(sensorStep)).let {
                    if (plan.sensorGainExhausted && correction >= 0.0) min(it, plannedProduct) else it
                }
                request = candidate.coerceIn(request * 2.0.pow(-3.0 * elapsed), request * 2.0.pow(1.5 * elapsed))
            }
            val desiredGain = when {
                !plan.manual || (measured != null && plan.sensorGainExhausted) -> {
                    val detail = stats.edgeEnergy / (stats.noise + 0.002)
                    val detailCeiling = 2.0 + 6.0 * detail / (detail + 1.0)
                    val noiseCeiling = (8.0 / (1.0 + 32.0 * stats.noise)).coerceAtLeast(1.25)
                    2.0.pow(errorEv).coerceIn(1.0, min(detailCeiling, noiseCeiling))
                }
                measured != null || errorEv < -0.10 -> 1.0
                else -> 2.0.pow(gainEv)
            }
            gainEv = if (stillImage) log2(desiredGain)
                else gainEv + (log2(desiredGain) - gainEv).coerceIn(-3.0 * elapsed, elapsed)
        }
        val gain = max(settings.digitalGain.toDouble(), 2.0.pow(gainEv)).coerceIn(1.0, 16.0).toFloat()
        val regions = regionalGains(stats.tiles, gain, settings.regional)
        val smoothedRegions = if (!settings.regional || previous == null || stillImage) regions else {
            regions.mapIndexed { index, value ->
                val oldEv = log2(previous.enhancement.regionalGains[index].toDouble())
                val newEv = oldEv + (log2(value.toDouble()) - oldEv) * (elapsed * 2).coerceAtMost(1.0)
                2.0.pow(newEv).toFloat().coerceIn(1f / gain, 1f)
            }
        }
        val enhancement = Enhancement(
            gain = gain,
            gamma = settings.gamma,
            regionalGains = smoothedRegions,
            noise = stats.noise
        )
        return AutoDecision(request, enhancement).also { lastDecision = it }
    }

    private fun matchedProduct(metadata: CaptureMetadata?, plan: CapturePlan, timestampNs: Long): Double? {
        if (metadata == null || metadata.timestampNs != timestampNs) return null
        val product = metadata.exposureProduct ?: return null
        if (plan.manual && (
                abs(metadata.exposureNs!!.toDouble() / plan.exposureNs - 1.0) > 0.03 ||
                    abs(metadata.iso!!.toDouble() / plan.iso - 1.0) > 0.03
                )) return null
        return product
    }

    private fun regionalGains(tiles: List<Float>, gain: Float, enabled: Boolean): List<Float> {
        if (!enabled || gain <= 1f) return List(16) { 1f }
        val minimum = 1.0 / gain
        val desiredEv = tiles.map { log2((0.75 / max(it.toDouble(), 0.00001) / gain).coerceIn(minimum, 1.0)) }
        return List(16) { tile ->
            var ev = desiredEv[tile]
            for (other in 0 until 16) {
                val distance = abs(tile / 4 - other / 4) + abs(tile % 4 - other % 4)
                ev = min(ev, desiredEv[other] + 0.5 * distance)
            }
            2.0.pow(ev).coerceIn(minimum, 1.0).toFloat()
        }
    }

    private fun validate(stats: SceneStats, plan: CapturePlan, timestampNs: Long) {
        require(timestampNs >= 0L) { "Frame timestamp must be nonnegative" }
        require(plan.exposureNs > 0L && plan.iso > 0 && plan.frameDurationNs > 0L) { "Invalid capture plan" }
        require(plan.requestedFps in SUPPORTED_FPS) { "Unsupported requested cadence" }
        val values = listOf(stats.mean, stats.p10, stats.p50, stats.p90, stats.clippedFraction, stats.noise, stats.edgeEnergy)
        require(values.all { it.isFinite() && it in 0f..1f }) { "Invalid scene statistics" }
        require(stats.p10 <= stats.p50 && stats.p50 <= stats.p90) { "Scene percentiles must be ordered" }
        require(stats.tiles.size == 16 && stats.tiles.all { it.isFinite() && it in 0f..1f }) { "Expected 16 normalized tiles" }
    }
}
