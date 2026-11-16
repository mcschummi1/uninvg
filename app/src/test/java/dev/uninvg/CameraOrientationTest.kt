package dev.uninvg

import dev.uninvg.render.CameraOrientation
import org.junit.Assert.*
import org.junit.Test

class CameraOrientationTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val portraitProducer = floatArrayOf(0f, -0.98f, 0f, 0f, -0.98f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0.99f, 0.99f, 0f, 1f)

    @Test fun portraitKeepsProducerRotationAndCropExactlyOnce() {
        for (sensor in listOf(90, 270)) {
            assertArrayEquals(portraitProducer, CameraOrientation(sensor, 0, false).textureMatrix(portraitProducer), 0f)
        }
    }

    @Test fun frontPreviewDoesNotMirrorTheProducerAgain() {
        assertArrayEquals(portraitProducer, CameraOrientation(270, 0, true).textureMatrix(portraitProducer), 0f)
    }

    @Test fun everyDisplayRotationSamplesTheCorrectCorner() {
        val expected = listOf(0.2f to 0.3f, 0.3f to 0.8f, 0.8f to 0.7f, 0.7f to 0.2f)
        listOf(0, 90, 180, 270).forEachIndexed { index, display ->
            val sampled = point(CameraOrientation(90, display, false).textureMatrix(identity), 0.2f, 0.3f)
            assertEquals(expected[index].first, sampled.first, 0.000001f)
            assertEquals(expected[index].second, sampled.second, 0.000001f)
        }
    }

    @Test fun producerTransformIsAppliedAfterDisplayCorrection() {
        val matrix = CameraOrientation(90, 90, false).textureMatrix(portraitProducer)
        val expected = point(portraitProducer, 0.3f, 0.8f)
        val sampled = point(matrix, 0.2f, 0.3f)
        assertEquals(expected.first, sampled.first, 0.000001f)
        assertEquals(expected.second, sampled.second, 0.000001f)
    }

    @Test fun portraitUsesPortraitOutputAndLandscapeUsesLandscapeOutput() {
        assertEquals(720 to 1280, CameraOrientation(90, 0, false).outputSize(1280, 720))
        assertEquals(1280 to 720, CameraOrientation(90, 90, false).outputSize(1280, 720))
        assertEquals(720 to 1280, CameraOrientation(90, 180, false).outputSize(1280, 720))
        assertEquals(1280 to 720, CameraOrientation(90, 270, false).outputSize(1280, 720))
    }

    @Test fun outputDimensionsWorkForEverySensorAndFacing() {
        for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val orientation = CameraOrientation(sensor, display, front)
            val size = orientation.outputSize(1280, 720)
            assertEquals(1280 * 720, size.first * size.second)
            assertEquals(if ((sensor - display) % 180 == 0) 1280 else 720, size.first)
        }
    }

    @Test fun rearTapCoordinatesUndoVisibleImageRotation() {
        assertPoint(0.3f to 0.8f, CameraOrientation(90, 0, false).sensorPoint(0.2f, 0.3f))
        assertPoint(0.2f to 0.3f, CameraOrientation(90, 90, false).sensorPoint(0.2f, 0.3f))
        assertPoint(0.7f to 0.2f, CameraOrientation(90, 180, false).sensorPoint(0.2f, 0.3f))
    }

    @Test fun frontTapCoordinatesUndoMirroringAndRotation() {
        assertPoint(0.3f to 0.2f, CameraOrientation(90, 0, true).sensorPoint(0.2f, 0.3f))
        assertPoint(0.2f to 0.7f, CameraOrientation(90, 90, true).sensorPoint(0.2f, 0.3f))
        assertPoint(0.8f to 0.3f, CameraOrientation(90, 270, true).sensorPoint(0.2f, 0.3f))
    }

    @Test fun halfTurnUpdatesMatrixEvenThoughOutputSizeIsUnchanged() {
        val upright = CameraOrientation(90, 0, false)
        val reversed = CameraOrientation(90, 180, false)
        assertEquals(upright.outputSize(1280, 720), reversed.outputSize(1280, 720))
        assertFalse(upright.textureMatrix(portraitProducer).contentEquals(reversed.textureMatrix(portraitProducer)))
    }

    @Test fun invalidOrientationAndCoordinatesFailAtBoundary() {
        assertThrows(IllegalArgumentException::class.java) { CameraOrientation(45, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { CameraOrientation(90, 360, false) }
        val orientation = CameraOrientation(90, 0, false)
        assertThrows(IllegalArgumentException::class.java) { orientation.outputSize(0, 720) }
        assertThrows(IllegalArgumentException::class.java) { orientation.sensorPoint(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { orientation.textureMatrix(FloatArray(15)) }
    }

    private fun point(matrix: FloatArray, x: Float, y: Float) =
        (matrix[0] * x + matrix[4] * y + matrix[12]) to (matrix[1] * x + matrix[5] * y + matrix[13])

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.000001f)
        assertEquals(expected.second, actual.second, 0.000001f)
    }
}
