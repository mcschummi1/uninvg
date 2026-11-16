package dev.uninvg.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

object SceneAnalyzer {
    private const val BINS = 4096

    // Meter the unenhanced ISP reference, not calibrated lux or the displayed result.
    fun analyze(luma: FloatArray, width: Int, height: Int): SceneStats {
        require(width > 0 && height > 0 && width.toLong() * height == luma.size.toLong()) {
            "Dimensions must be positive and match the luminance array"
        }
        val histogram = IntArray(BINS)
        val residuals = IntArray(BINS)
        val tileSums = DoubleArray(16)
        val tileCounts = IntArray(16)
        var sum = 0.0
        var clipped = 0
        var gradients = 0.0
        var gradientCount = 0L
        var residualCount = 0
        for (y in 0 until height) {
            val tileY = (y.toLong() * 4 / height).toInt()
            for (x in 0 until width) {
                val index = y * width + x
                val value = luma[index]
                require(value.isFinite() && value in 0f..1f) { "Invalid reference luminance at index $index" }
                histogram[(value * (BINS - 1)).roundToInt()]++
                sum += value
                if (value >= 0.98f) clipped++
                val tile = tileY * 4 + (x.toLong() * 4 / width).toInt()
                tileSums[tile] += value
                tileCounts[tile]++
                if (x > 0) {
                    gradients += abs(value - luma[index - 1])
                    gradientCount++
                }
                if (y > 0) {
                    gradients += abs(value - luma[index - width])
                    gradientCount++
                }
            }
        }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val localMean = (luma[index - 1] + luma[index + 1] + luma[index - width] + luma[index + width]) * 0.25f
                residuals[(abs(luma[index] - localMean) * (BINS - 1)).roundToInt()]++
                residualCount++
            }
        }
        val noise = if (residualCount == 0) 0f else (percentile(residuals, residualCount, 0.5) * 1.326f).coerceAtMost(1f)
        val edges = if (gradientCount == 0L) 0f else (gradients / gradientCount - 1.128 * noise).toFloat().coerceIn(0f, 1f)
        val mean = (sum / luma.size).toFloat()
        val p10 = percentile(histogram, luma.size, 0.1)
        val p50 = percentile(histogram, luma.size, 0.5)
        val p90 = percentile(histogram, luma.size, 0.9)
        val tiles = List(16) { tile ->
            if (tileCounts[tile] > 0) (tileSums[tile] / tileCounts[tile]).toFloat() else {
                val x = ((tile % 4 + 0.5) * width / 4).toInt().coerceAtMost(width - 1)
                val y = ((tile / 4 + 0.5) * height / 4).toInt().coerceAtMost(height - 1)
                luma[y * width + x]
            }
        }
        val kind = when {
            p90 < 0.015f && mean < 0.01f -> SceneKind.PHOTON_STARVED
            p50 < 0.12f || mean < 0.10f -> SceneKind.DARK
            else -> SceneKind.LIT
        }
        return SceneStats(mean, p10, p50, p90, clipped.toFloat() / luma.size, noise, edges, tiles, kind)
    }

    private fun percentile(histogram: IntArray, count: Int, fraction: Double): Float {
        val rank = fraction * (count - 1)
        val lowerRank = floor(rank).toInt()
        val upperRank = kotlin.math.ceil(rank).toInt()
        var cumulative = 0
        var lower = -1
        for (bin in histogram.indices) {
            cumulative += histogram[bin]
            if (lower < 0 && cumulative > lowerRank) lower = bin
            if (cumulative > upperRank) {
                return ((lower + (bin - lower) * (rank - lowerRank)) / (BINS - 1)).toFloat()
            }
        }
        return 1f
    }
}
