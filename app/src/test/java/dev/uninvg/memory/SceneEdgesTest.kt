package dev.uninvg.memory

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class SceneEdgesTest {
    private val pose = ViewPose(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    private val lens = ViewLens(1.0, 0.7, 96.0 / 64.0)

    @Test fun flatFramesIncludingBlackAndWhiteNeverSupplyMatchEvidence() {
        val reference = SceneEdges.extract(stripes(), 96, 64)
        assertTrue(SceneEdges.resemblance(reference, reference))
        for ((width, height) in listOf(8 to 8, 33 to 17, 256 to 256)) {
            for (level in listOf(0f, 0.001f, 0.5f, 1f)) {
                val lines = SceneEdges.extract(FloatArray(width * height) { level }, width, height)
                assertTrue(lines.isEmpty())
                assertFalse(SceneEdges.resemblance(lines, lines))
                assertFalse(SceneEdges.resemblance(reference, lines))
                assertFalse(SceneEdges.resemblance(lines, reference))
            }
        }
    }

    @Test fun deterministicDenseGrainNeverMatchesEvenTheIdenticalNoiseFrame() {
        val reference = SceneEdges.extract(stripes(), 96, 64)
        for (phase in listOf(0, 1, 17)) for (amplitude in listOf(0.02f, 0.6f)) {
            val pixels = frame(64, 64) { x, y ->
                val grain = ((x * 37 + y * 17 + x * y * 13 + phase * 11) % 101) / 100f
                0.5f + (grain - 0.5f) * amplitude
            }
            val original = pixels.copyOf()
            val lines = SceneEdges.extract(pixels, 64, 64)
            assertFalse("Noise phase=$phase amplitude=$amplitude", SceneEdges.resemblance(lines, lines))
            assertFalse(SceneEdges.resemblance(reference, lines))
            assertFalse(SceneEdges.resemblance(lines, reference))
            assertArrayEquals(original, pixels, 0f)
        }
    }

    @Test fun smoothRampAndSubThresholdRectangleDoNotInventAnEdgePatch() {
        val ramp = frame(64, 64) { x, y -> 0.1f + x / 256f + y / 512f }
        val weakRectangle = frame(64, 64) { x, y -> if (x in 16..47 && y in 16..47) 0.51f else 0.5f }
        for (pixels in listOf(ramp, weakRectangle)) {
            val lines = SceneEdges.extract(pixels, 64, 64)
            assertTrue(lines.isEmpty())
            assertFalse(SceneEdges.resemblance(lines, lines))
            assertThrows(IllegalArgumentException::class.java) { SceneEdges.patch(lines, pose, lens) }
        }
    }

    @Test fun filledRectangleProducesLongHorizontalAndVerticalPerimeterEdges() {
        val pixels = frame(64, 64) { x, y -> if (x in 16..47 && y in 16..47) 0.85f else 0.1f }
        val original = pixels.copyOf()
        val lines = SceneEdges.extract(pixels, 64, 64)
        assertTrue(lines.size >= 4)
        assertTrue(lines.any { it.a.y == it.b.y && abs(it.b.x - it.a.x) >= 28.0 / 63 })
        assertTrue(lines.any { it.a.x == it.b.x && abs(it.b.y - it.a.y) >= 28.0 / 63 })
        for (line in lines) {
            assertNormalized(line)
            for (point in listOf(line.a, line.b)) {
                assertTrue(point.x in 15.0 / 63..48.0 / 63)
                assertTrue(point.y in 15.0 / 63..48.0 / 63)
            }
            val fixed = if (line.a.y == line.b.y) line.a.y else line.a.x
            assertTrue(abs(fixed - 16.0 / 63) <= 1.01 / 63 || abs(fixed - 47.0 / 63) <= 1.01 / 63)
        }
        assertArrayEquals(original, pixels, 0f)
    }

    @Test fun verticalStripesYieldVerticalLinesAtTransitionsNotAcrossStripeInteriors() {
        val lines = SceneEdges.extract(stripes(), 96, 64)
        assertTrue(lines.size >= 16)
        lines.forEach { line ->
            assertNormalized(line)
            assertEquals(line.a.x, line.b.x, 0.0)
            assertTrue(line.b.y - line.a.y > 0.9)
            assertEquals(3, (line.a.x * 95).roundToInt() % 4)
        }
        assertTrue(SceneEdges.resemblance(lines, lines))
    }

    @Test fun contrastChangePreservesStructuredEdgeGeometryWithoutMutatingLuma() {
        val pixels = stripedRectangle()
        val changedContrast = FloatArray(pixels.size) { pixels[it] * 0.55f + 0.1f }
        val before = pixels.copyOf()
        val lines = SceneEdges.extract(pixels, 96, 64)
        val dimmerLines = SceneEdges.extract(changedContrast, 96, 64)
        assertTrue(lines.size >= 16)
        assertLines(lines, dimmerLines)
        assertTrue(SceneEdges.resemblance(lines, dimmerLines))
        assertArrayEquals(before, pixels, 0f)
    }

    @Test fun extractionBoundsDenseStructuredFramesAt240NondegenerateLines() {
        val pixels = frame(256, 256) { x, y -> if (x % 12 in 3..8 && y % 12 in 3..8) 0.9f else 0.1f }
        val lines = SceneEdges.extract(pixels, 256, 256)
        assertEquals(240, lines.size)
        lines.forEach { assertNormalized(it); assertNotEquals(it.a, it.b) }
    }

    @Test fun invalidFrameDimensionsLengthAndPixelsAreRejected() {
        for ((width, height) in listOf(0 to 8, 7 to 8, 8 to 7, 257 to 8, 8 to 257, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SceneEdges.extract(FloatArray(64), width, height) }
        }
        for (length in listOf(0, 63, 65)) {
            assertThrows(IllegalArgumentException::class.java) { SceneEdges.extract(FloatArray(length), 8, 8) }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.001f, 1.001f)) {
            val pixels = FloatArray(64).apply { this[27] = value }
            assertThrows(IllegalArgumentException::class.java) { SceneEdges.extract(pixels, 8, 8) }
        }
    }

    @Test fun structuredPatchRoundTripPreservesShapeAndProducesUnitWorldRays() {
        val lines = SceneEdges.extract(stripedRectangle(), 96, 64)
        assertTrue(lines.size >= 16)
        val patch = SceneEdges.patch(lines, pose, lens)
        assertEquals(pose.forward, patch.forward)
        assertEquals(lines.size, patch.lines.size)
        patch.lines.forEach {
            assertEquals(1.0, it.a.dot(it.a), 1e-12)
            assertEquals(1.0, it.b.dot(it.b), 1e-12)
        }
        val projected = SceneEdges.projected(listOf(patch), pose, lens)
        assertLines(lines, projected)
        assertTrue(SceneEdges.resemblance(lines, projected))
    }

    @Test fun posePanUsesWorldDirectionsAndClipsEndpointsOutsideTheNewView() {
        val lines = SceneEdges.extract(stripedRectangle(), 96, 64)
        assertTrue(lines.size >= 16)
        val patch = SceneEdges.patch(lines, pose, lens)
        val angle = Math.toRadians(6.0)
        val c = cos(angle)
        val s = sin(angle)
        val panned = ViewPose(Vec3(c, 0.0, -s), pose.up, Vec3(s, 0.0, c))
        fun expectedPoint(point: Point2): Point2 {
            val u = (2 * point.x - 1) * lens.tanX
            val v = (2 * point.y - 1) * lens.tanY
            val depth = c + s * u
            return Point2(0.5 + (c * u - s) / depth / lens.tanX / 2, 0.5 + v / depth / lens.tanY / 2)
        }
        val expected = lines.map { ImageLine(expectedPoint(it.a), expectedPoint(it.b)) }
            .filter { line -> listOf(line.a.x, line.a.y, line.b.x, line.b.y).all { it in 0.0..1.0 } }
        val projected = SceneEdges.projected(listOf(patch), panned, lens)
        assertTrue(expected.size >= 8)
        assertTrue(expected.size < lines.size)
        assertLines(expected, projected)
        projected.forEach { assertNormalized(it) }
        assertEquals(patch, SceneEdges.patch(lines, pose, lens))
    }

    @Test fun turningAwayOrSwitchingCameraFacingCannotRecallThePatch() {
        val lines = SceneEdges.extract(stripes(), 96, 64)
        val patch = SceneEdges.patch(lines, pose, lens)
        val side = ViewPose(Vec3(0.0, 0.0, -1.0), pose.up, Vec3(1.0, 0.0, 0.0))
        val rear = ViewPose(pose.right, pose.up, pose.forward * -1.0)
        assertFalse(SceneEdges.projected(listOf(patch), pose, lens).isEmpty())
        assertTrue(SceneEdges.projected(listOf(patch), side, lens).isEmpty())
        assertTrue(SceneEdges.projected(listOf(patch), rear, lens).isEmpty())
    }

    @Test fun anyEndpointBehindNearHorizonOrOutsideViewportDropsTheEntireWorldLine() {
        val center = pose.forward
        val invalidLines = listOf(
            WorldLine(center * -1.0, center),
            WorldLine(center, center * -1.0),
            WorldLine(center * -1.0, center * -1.0),
            WorldLine(pose.right, center),
            WorldLine(pose.ray(-0.1, 0.5, lens), pose.ray(0.2, 0.5, lens)),
            WorldLine(pose.ray(0.8, 0.5, lens), pose.ray(1.1, 0.5, lens)),
            WorldLine(pose.ray(0.5, -0.1, lens), pose.ray(0.5, 0.8, lens)),
            WorldLine(pose.ray(0.5, 0.2, lens), pose.ray(0.5, 1.1, lens))
        )
        val patch = ScenePatch(pose.forward, invalidLines)
        assertTrue(SceneEdges.projected(listOf(patch), pose, lens).isEmpty())
    }

    @Test fun patchRequiresEightTo240LinesAndAUnitForwardDirection() {
        val line = ImageLine(Point2(0.25, 0.25), Point2(0.75, 0.75))
        for (count in listOf(0, 7, 241)) {
            assertThrows(IllegalArgumentException::class.java) { SceneEdges.patch(List(count) { line }, pose, lens) }
        }
        assertEquals(8, SceneEdges.patch(List(8) { line }, pose, lens).lines.size)
        assertEquals(240, SceneEdges.patch(List(240) { line }, pose, lens).lines.size)
        val valid = SceneEdges.patch(List(8) { line }, pose, lens)
        assertThrows(IllegalArgumentException::class.java) { ScenePatch(pose.forward * 2.0, valid.lines) }
    }

    @Test fun tooFewLinesOrEvidenceInOnlyOneTileNeverProvesResemblance() {
        val broad = broadLines()
        assertTrue(SceneEdges.resemblance(broad, broad))
        assertFalse(SceneEdges.resemblance(broad.take(15), broad))
        assertFalse(SceneEdges.resemblance(broad, broad.take(15)))
        val local = List(32) { index ->
            val x = 0.05 + index % 8 * 0.01
            val y = 0.05 + index / 8 * 0.01
            ImageLine(Point2(x, y), Point2(x + 0.005, y))
        }
        assertFalse(SceneEdges.resemblance(local, local))
        val unrelated = broad.map { ImageLine(Point2(it.a.x, it.a.y + 0.06), Point2(it.b.x, it.b.y + 0.06)) }
        assertFalse(SceneEdges.resemblance(broad, unrelated))
        assertFalse(SceneEdges.resemblance(unrelated, broad))
    }

    @Test fun lineOrderingEndpointDirectionAndSmallJitterDoNotDestroyARealShapeMatch() {
        val lines = broadLines()
        val reversed = lines.reversed().map { line ->
            ImageLine(Point2(line.b.x + 0.004, line.b.y - 0.003), Point2(line.a.x + 0.004, line.a.y - 0.003))
        }
        assertTrue(SceneEdges.resemblance(lines, reversed))
        assertTrue(SceneEdges.resemblance(reversed, lines))
    }

    @Test fun coincidentEndpointsWithDifferentConnectivityMustNotMatchEdgeShape() {
        val horizontal = broadLines()
        val rewired = horizontal.indices.map { index -> ImageLine(horizontal[index].a, horizontal[(index + 4) % 16].b) }
        assertTrue(SceneEdges.resemblance(horizontal, horizontal))
        assertEquals(horizontal.flatMap { listOf(it.a, it.b) }.toSet(), rewired.flatMap { listOf(it.a, it.b) }.toSet())
        assertTrue(horizontal.all { it.a.y == it.b.y })
        assertTrue(rewired.all { it.a.y != it.b.y })
        assertFalse("Matching points alone must not establish matching edge shape", SceneEdges.resemblance(horizontal, rewired))
    }

    private fun stripes() = frame(96, 64) { x, _ -> if (x / 4 % 2 == 0) 0.1f else 0.9f }

    private fun stripedRectangle() = frame(96, 64) { x, y ->
        if (x in 8..87 && y in 8..55) {
            if ((x - 8) / 4 % 2 == 0) 0.85f else 0.25f
        } else 0.05f
    }

    private fun frame(width: Int, height: Int, pixel: (Int, Int) -> Float) =
        FloatArray(width * height) { pixel(it % width, it / width) }

    private fun broadLines() = List(16) { index ->
        val x = listOf(0.08, 0.29, 0.52, 0.78)[index % 4]
        val y = listOf(0.08, 0.29, 0.52, 0.78)[index / 4]
        ImageLine(Point2(x, y), Point2(x + 0.07, y))
    }

    private fun assertNormalized(line: ImageLine) {
        assertTrue(listOf(line.a.x, line.a.y, line.b.x, line.b.y).all { it in 0.0..1.0 })
    }

    private fun assertLines(expected: List<ImageLine>, actual: List<ImageLine>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertEquals(a.a.x, b.a.x, 1e-9)
            assertEquals(a.a.y, b.a.y, 1e-9)
            assertEquals(a.b.x, b.b.x, 1e-9)
            assertEquals(a.b.y, b.b.y, 1e-9)
        }
    }
}
