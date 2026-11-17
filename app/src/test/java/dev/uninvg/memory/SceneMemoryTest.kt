package dev.uninvg.memory

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class SceneMemoryTest {
    private val xAxis = Vec3(1.0, 0.0, 0.0)
    private val yAxis = Vec3(0.0, 1.0, 0.0)
    private val zAxis = Vec3(0.0, 0.0, 1.0)
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val lens = ViewLens(1.0, 1.0, 1.0)
    private val front = ViewPose(xAxis, yAxis, zAxis)

    @Test fun rayAndProjectionAreInversesAcrossRotationsFacingAndUnequalLensAxes() {
        val deviceMatrix = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)
        val lenses = listOf(lens, ViewLens(0.8, 0.45, 16.0 / 9.0), ViewLens(0.02, 5.0, 0.1))
        for (display in listOf(0, 90, 180, 270)) for (frontFacing in listOf(false, true)) {
            val pose = ViewPose.fromDeviceMatrix(deviceMatrix, display, frontFacing)
            for (viewLens in lenses) for (x in listOf(0.0, 0.13, 0.5, 0.79, 1.0)) {
                for (y in listOf(0.0, 0.24, 0.5, 0.88, 1.0)) {
                    val ray = pose.ray(x, y, viewLens)
                    assertEquals(1.0, ray.dot(ray), 1e-12)
                    val point = requireNotNull(pose.project(ray, viewLens))
                    assertEquals(x, point.x, 1e-9)
                    assertEquals(y, point.y, 1e-9)
                }
            }
        }
    }

    @Test fun displayBasisUsesDeviceColumnsNotRowsAndChangesForwardOnlyForFacing() {
        val matrix = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)
        val expected = listOf(yAxis to zAxis, zAxis to (yAxis * -1.0),
            (yAxis * -1.0) to (zAxis * -1.0), (zAxis * -1.0) to yAxis)
        listOf(0, 90, 180, 270).forEachIndexed { index, display ->
            val rear = ViewPose.fromDeviceMatrix(matrix, display, false)
            val selfie = ViewPose.fromDeviceMatrix(matrix, display, true)
            assertVector(expected[index].first, rear.right)
            assertVector(expected[index].second, rear.up)
            assertVector(xAxis * -1.0, rear.forward)
            assertVector(rear.right, selfie.right)
            assertVector(rear.up, selfie.up)
            assertVector(xAxis, selfie.forward)
        }
    }

    @Test fun offCenterRayUsesEachLensAxisOnce() {
        val asymmetricLens = ViewLens(1.0, 0.3, 2.0)
        assertVector(Vec3(0.6, -0.15, 1.0).unit(), front.ray(0.8, 0.25, asymmetricLens))
        assertVector(zAxis, front.ray(0.5, 0.5, asymmetricLens))
    }

    @Test fun oneWorldRayMovesToExpectedImageCoordinatesForEveryDisplayRotation() {
        val ray = front.ray(0.8, 0.35, lens)
        val expected = listOf(Point2(0.8, 0.35), Point2(0.35, 0.2), Point2(0.2, 0.65), Point2(0.65, 0.8))
        listOf(0, 90, 180, 270).forEachIndexed { index, display ->
            val point = requireNotNull(ViewPose.fromDeviceMatrix(identity, display, true).project(ray, lens))
            assertEquals(expected[index].x, point.x, 1e-12)
            assertEquals(expected[index].y, point.y, 1e-12)
        }
    }

    @Test fun frontAndRearDoNotRecallEachOthersForwardRays() {
        for (display in listOf(0, 90, 180, 270)) {
            val rear = ViewPose.fromDeviceMatrix(identity, display, false)
            val selfie = ViewPose.fromDeviceMatrix(identity, display, true)
            assertVector(rear.forward * -1.0, selfie.forward)
            assertNull(rear.project(selfie.ray(0.5, 0.5, lens), lens))
            assertNull(selfie.project(rear.ray(0.3, 0.7, lens), lens))
        }
    }

    @Test fun raysBehindOrAtNearHorizonAreRejectedBeforeDivision() {
        assertNull(front.project(zAxis * -1.0, lens))
        assertNull(front.project(xAxis, lens))
        assertNull(front.project(Vec3(sqrt(1.0 - 0.05 * 0.05), 0.0, 0.05), lens))
        val depth = 0.050001
        assertNotNull(front.project(Vec3(sqrt(1.0 - depth * depth), 0.0, depth), lens))
    }

    @Test fun malformedOrDegenerateDeviceMatricesCannotBecomeAPose() {
        val invalid = listOf(FloatArray(0), FloatArray(8), FloatArray(10), FloatArray(16), FloatArray(9),
            floatArrayOf(1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(1f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(1e-12f, 0f, 0f, 0f, 1e-12f, 0f, 0f, 0f, 1e-12f))
        for (matrix in invalid) {
            assertThrows(IllegalArgumentException::class.java) { ViewPose.fromDeviceMatrix(matrix, 0, false) }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (index in identity.indices) {
                val matrix = identity.copyOf().apply { this[index] = value }
                assertThrows(IllegalArgumentException::class.java) { ViewPose.fromDeviceMatrix(matrix, 0, false) }
            }
        }
        for (display in listOf(-90, 45, 360)) {
            assertThrows(IllegalArgumentException::class.java) { ViewPose.fromDeviceMatrix(identity, display, false) }
        }
    }

    @Test fun scaledSensorMatrixIsRejectedRatherThanSilentlyRepaired() {
        val scaled = floatArrayOf(2f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 4f)
        assertThrows(IllegalArgumentException::class.java) { ViewPose.fromDeviceMatrix(scaled, 0, false) }
    }

    @Test fun reflectedMatrixIsNotAValidRotationVectorPose() {
        val reflection = floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertThrows(IllegalArgumentException::class.java) { ViewPose.fromDeviceMatrix(reflection, 0, false) }
    }

    @Test fun nonFiniteVectorsAndImagePointsAreRejectedAtConstruction() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { Vec3(value, 0.0, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { Vec3(0.0, value, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { Vec3(0.0, 0.0, value) }
            assertThrows(IllegalArgumentException::class.java) { Point2(value, 0.5) }
            assertThrows(IllegalArgumentException::class.java) { Point2(0.5, value) }
        }
    }

    @Test fun zeroAndNearZeroVectorsCannotBeNormalized() {
        for (vector in listOf(Vec3(0.0, 0.0, 0.0), Vec3(1e-12, 0.0, 0.0))) {
            assertThrows(IllegalArgumentException::class.java) { vector.unit() }
        }
        assertVector(Vec3(0.6, 0.8, 0.0), Vec3(3.0, 4.0, 0.0).unit())
    }

    @Test fun overflowingVectorNormIsRejectedRatherThanReturningAZeroRay() {
        assertThrows(IllegalArgumentException::class.java) { Vec3(Double.MAX_VALUE, 0.0, 0.0).unit() }
    }

    @Test fun posesAndWorldLinesRejectNonUnitOrNonOrthogonalDirections() {
        assertThrows(IllegalArgumentException::class.java) { ViewPose(xAxis * 2.0, yAxis, zAxis) }
        assertThrows(IllegalArgumentException::class.java) { ViewPose(xAxis, xAxis, zAxis) }
        assertThrows(IllegalArgumentException::class.java) { ViewPose(xAxis, yAxis, Vec3(0.0, 0.0, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { WorldLine(xAxis * 2.0, zAxis) }
        assertThrows(IllegalArgumentException::class.java) { WorldLine(xAxis, Vec3(0.0, 0.0, 0.0)) }
    }

    @Test fun lensLimitsAreInclusiveButInvalidAxesOrAspectAreRejected() {
        ViewLens(0.02, 5.0, 0.1)
        ViewLens(5.0, 0.02, 10.0)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 0.019, 5.001)) {
            assertThrows(IllegalArgumentException::class.java) { ViewLens(value, 1.0, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { ViewLens(1.0, value, 1.0) }
        }
        for (aspect in listOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.099, 10.001)) {
            assertThrows(IllegalArgumentException::class.java) { ViewLens(1.0, 1.0, aspect) }
        }
    }

    @Test fun placeDistanceIsSymmetricAndHandlesDatelineAndAntipodes() {
        val origin = fix(0.0, 0.0)
        assertEquals(0.0, origin.distanceTo(origin), 0.0)
        val north = fix(Math.toDegrees(100.0 / 6_371_000.0), 0.0)
        assertEquals(100.0, origin.distanceTo(north), 1e-6)
        assertEquals(origin.distanceTo(north), north.distanceTo(origin), 1e-9)
        val west = fix(0.0, 179.999)
        val east = fix(0.0, -179.999)
        assertEquals(222.389853, west.distanceTo(east), 0.001)
        assertEquals(PI * 6_371_000.0, origin.distanceTo(fix(0.0, 180.0)), 0.01)
    }

    @Test fun precisePlaceFixesUseProximityFloorButNeverAnUnlimitedRadius() {
        val origin = fix(0.0, 0.0, 0f)
        assertTrue(origin.couldBeSamePlace(atDistance(24.99, 0f)))
        assertFalse(origin.couldBeSamePlace(atDistance(25.01, 0f)))
        assertFalse(origin.couldBeSamePlace(atDistance(1000.0, 0f)))
    }

    @Test fun accuracyCanExpandCandidateRadiusOnlyWhileBothFixesRemainUseful() {
        val origin = fix(0.0, 0.0, 40f)
        val near = atDistance(99.99, 60f)
        assertTrue(origin.couldBeSamePlace(near))
        assertTrue(near.couldBeSamePlace(origin))
        assertFalse(origin.couldBeSamePlace(atDistance(100.01, 60f)))
        assertTrue(fix(0.0, 0.0, 100f).couldBeSamePlace(atDistance(199.99, 100f)))
        assertFalse(fix(0.0, 0.0, 100f).couldBeSamePlace(atDistance(200.01, 100f)))
        val imprecise = fix(0.0, 0.0, 100.01f)
        assertFalse(origin.couldBeSamePlace(imprecise))
        assertFalse(imprecise.couldBeSamePlace(origin))
        assertFalse(fix(0.0, 0.0, Float.MAX_VALUE).couldBeSamePlace(origin))
    }

    @Test fun historicalPlaceCandidateDoesNotProveVisualMatchOrFacing() {
        val historical = PlaceFix(51.5, -0.12, 5f, 1L)
        val later = historical.copy(timeMs = 1_700_000_000_000L)
        assertTrue(historical.couldBeSamePlace(later))
        val saved = List(16) { index ->
            val x = 0.1 + index % 4 * 0.2
            val y = 0.1 + index / 4 * 0.2
            ImageLine(Point2(x, y), Point2(x + 0.1, y))
        }
        val unrelated = saved.map { ImageLine(Point2(it.a.x, it.a.y + 0.06), Point2(it.b.x, it.b.y + 0.06)) }
        assertTrue(SceneEdges.resemblance(saved, saved))
        assertFalse(SceneEdges.resemblance(saved, unrelated))
        val patch = SceneEdges.patch(saved, front, lens)
        val lookingAway = ViewPose(xAxis * -1.0, yAxis, zAxis * -1.0)
        assertTrue(SceneEdges.projected(listOf(patch), lookingAway, lens).isEmpty())
    }

    @Test fun invalidPlaceCoordinatesAccuracyAndMissingTimestampAreRejected() {
        for (latitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, -90.001, 90.001)) {
            assertThrows(IllegalArgumentException::class.java) { fix(latitude, 0.0) }
        }
        for (longitude in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -180.001, 180.001)) {
            assertThrows(IllegalArgumentException::class.java) { fix(0.0, longitude) }
        }
        for (accuracy in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f)) {
            assertThrows(IllegalArgumentException::class.java) { fix(0.0, 0.0, accuracy) }
        }
        for (time in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { PlaceFix(0.0, 0.0, 1f, time) }
        }
        assertTrue(fix(90.0, 180.0).distanceTo(fix(-90.0, -180.0)).isFinite())
    }

    private fun fix(latitude: Double, longitude: Double, accuracy: Float = 5f) =
        PlaceFix(latitude, longitude, accuracy, 1_700_000_000_000L)

    private fun atDistance(meters: Double, accuracy: Float) = fix(Math.toDegrees(meters / 6_371_000.0), 0.0, accuracy)

    private fun assertVector(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-12)
        assertEquals(expected.y, actual.y, 1e-12)
        assertEquals(expected.z, actual.z, 1e-12)
    }
}
