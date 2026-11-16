package dev.uninvg.core

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class SceneAnalyzerTest {
    @Test
    fun blackFrameIsPhotonStarvedWithFiniteZeroStatistics() {
        val stats = SceneAnalyzer.analyze(FloatArray(1024), 32, 32)
        assertEquals(SceneKind.PHOTON_STARVED, stats.kind)
        assertEquals(0f, stats.mean, 0f)
        assertEquals(0f, stats.p10, 0f)
        assertEquals(0f, stats.p50, 0f)
        assertEquals(0f, stats.p90, 0f)
        assertEquals(0f, stats.noise, 0f)
        assertEquals(0f, stats.edgeEnergy, 0f)
        assertEquals(List(16) { 0f }, stats.tiles)
    }

    @Test
    fun veryDarkOutlinesNeverBecomeBalancedDueToWeakEdges() {
        val pixels = FloatArray(64 * 64) { index ->
            val x = index % 64
            val y = index / 64
            if ((x == 16 || x == 48) && y in 16..48 || (y == 16 || y == 48) && x in 16..48) 0.035f else 0.004f
        }
        val stats = SceneAnalyzer.analyze(pixels, 64, 64)
        assertNotEquals(SceneKind.LIT, stats.kind)
        assertTrue(stats.edgeEnergy > 0f)
        assertTrue(stats.noise < 0.001f)
        assertTrue(stats.p90 < 0.015f)
        val flatDark = SceneAnalyzer.analyze(FloatArray(1024) { 0.04f }, 32, 32)
        assertEquals(SceneKind.DARK, flatDark.kind)
    }

    @Test
    fun highlightsAndDarkCornerPreserveRowMajorTilesAndPercentiles() {
        val pixels = FloatArray(64) { index ->
            val tile = index / 8 / 2 * 4 + index % 8 / 2
            when (tile) { 0 -> 0.001f; 15 -> 1f; else -> 0.25f }
        }
        val original = pixels.copyOf()
        val stats = SceneAnalyzer.analyze(pixels, 8, 8)
        assertEquals(0.001f, stats.tiles[0], 1e-6f)
        assertEquals(1f, stats.tiles[15], 0f)
        assertEquals(0.25f, stats.tiles[6], 0f)
        assertEquals(1f / 16, stats.clippedFraction, 0f)
        assertEquals(0.25f, stats.p10, 0.0003f)
        assertEquals(0.25f, stats.p50, 0.0003f)
        assertEquals(0.25f, stats.p90, 0.0003f)
        assertEquals((0.001f + 1f + 14 * 0.25f) / 16, stats.mean, 1e-6f)
        assertArrayEquals(original, pixels, 0f)
    }

    @Test
    fun residualNoiseSeparatesRandomNoiseFromSmoothGradients() {
        val gradient = FloatArray(64 * 64) { (it % 64) / 128f + 0.1f }
        val smooth = SceneAnalyzer.analyze(gradient, 64, 64)
        val random = Random(712)
        val noisy = SceneAnalyzer.analyze(FloatArray(64 * 64) { 0.3f + (random.nextFloat() - 0.5f) * 0.12f }, 64, 64)
        assertTrue(smooth.noise < 0.001f)
        assertTrue(smooth.edgeEnergy > 0f)
        assertTrue(noisy.noise in 0.02f..0.06f)
        assertTrue(noisy.noise > smooth.noise)
        assertEquals(SceneKind.LIT, smooth.kind)
    }

    @Test
    fun invalidPixelsAndDimensionsFailBeforeProducingStatistics() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.001f, 1.001f)) {
            assertThrows(IllegalArgumentException::class.java) { SceneAnalyzer.analyze(floatArrayOf(value), 1, 1) }
        }
        for ((width, height) in listOf(0 to 1, -1 to 1, 1 to -1, 2 to 2, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SceneAnalyzer.analyze(floatArrayOf(0f), width, height) }
        }
        assertThrows(IllegalArgumentException::class.java) { SceneAnalyzer.analyze(floatArrayOf(), 0, 0) }
    }

    @Test
    fun smallAndOddFramesHaveSixteenFiniteTilesWithoutInventingBlackAreas() {
        for ((width, height) in listOf(1 to 1, 1 to 7, 7 to 1, 3 to 3, 5 to 9)) {
            val stats = SceneAnalyzer.analyze(FloatArray(width * height) { 0.35f }, width, height)
            assertEquals(16, stats.tiles.size)
            assertTrue(stats.tiles.all { it == 0.35f })
            assertEquals(0.35f, stats.mean, 1e-6f)
            assertEquals(0f, stats.noise, 0f)
            assertEquals(0f, stats.edgeEnergy, 0f)
        }
    }
}
