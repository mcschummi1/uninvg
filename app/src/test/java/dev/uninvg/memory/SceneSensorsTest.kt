package dev.uninvg.memory

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SceneSensorsTest {
    private lateinit var manager: SensorManager
    private lateinit var rotation: Sensor
    private lateinit var gyro: Sensor
    private lateinit var sensors: SceneSensors
    private var changes = 0
    private val identityVector = floatArrayOf(0f, 0f, 0f, 1f)

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotation = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
        gyro = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)
        shadowOf(manager).addSensor(rotation)
        shadowOf(manager).addSensor(gyro)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        sensors = SceneSensors(context) { changes++ }
        sensors.start(false)
        assertTrue(sensors.supported)
    }

    @After fun teardown() { sensors.stop() }

    @Test fun rotationPoseIsUsableOnlyWithinTheFrameTimestampFreshnessWindow() {
        val timestamp = SystemClock.elapsedRealtimeNanos()
        sensors.onSensorChanged(event(rotation, identityVector, timestamp))
        assertNotNull(sensors.pose(0, false, timestamp))
        assertNotNull(sensors.pose(0, false, timestamp - 300_000_000L))
        assertNotNull(sensors.pose(0, false, timestamp + 300_000_000L))
        assertNull(sensors.pose(0, false, timestamp - 300_000_001L))
        assertNull(sensors.pose(0, false, timestamp + 300_000_001L))
        ShadowSystemClock.advanceBy(Duration.ofMillis(301))
        assertNull(sensors.pose(0, false))
    }

    @Test fun accuracyStatusCannotInventAPoseAndLowRotationAccuracyDisablesRecall() {
        sensors.onAccuracyChanged(rotation, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        assertNull(sensors.pose(0, false))
        sensors.onSensorChanged(event(rotation, identityVector, accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW))
        assertNull(sensors.pose(0, false))
        sensors.onSensorChanged(event(rotation, identityVector, accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))
        assertNotNull(sensors.pose(0, false))
        sensors.onAccuracyChanged(gyro, SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertNotNull(sensors.pose(0, false))
        sensors.onAccuracyChanged(rotation, SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertNull(sensors.pose(0, false))
    }

    @Test fun stillnessRequiresLowAngularSpeedAndARecentNonFutureGyroReading() {
        assertFalse(sensors.stable())
        sensors.onSensorChanged(event(gyro, floatArrayOf(0.01f, 0.02f, 0.03f)))
        assertTrue(sensors.stable())
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        assertTrue(sensors.stable())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertFalse(sensors.stable())
        sensors.onSensorChanged(event(gyro, floatArrayOf(0.121f, 0f, 0f)))
        assertFalse(sensors.stable())
        sensors.onSensorChanged(event(gyro, floatArrayOf(0f, 0f, 0f), SystemClock.elapsedRealtimeNanos() + 1))
        assertFalse(sensors.stable())
    }

    @Test fun stopAndRestartCannotReuseOldPoseOrStillnessEvidence() {
        sensors.onSensorChanged(event(rotation, identityVector))
        sensors.onSensorChanged(event(gyro, floatArrayOf(0f, 0f, 0f)))
        assertNotNull(sensors.pose(0, false))
        assertTrue(sensors.stable())
        sensors.stop()
        assertNull(sensors.pose(0, false))
        assertFalse(sensors.stable())
        sensors.onSensorChanged(event(rotation, identityVector))
        sensors.onSensorChanged(event(gyro, floatArrayOf(0f, 0f, 0f)))
        sensors.start(false)
        assertNull(sensors.pose(0, false))
        assertFalse(sensors.stable())
    }

    @Test fun incompleteOrNonFiniteGyroVectorsNeverProveStillness() {
        val invalid = listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(0f, 0f),
            floatArrayOf(Float.NaN, 0f, 0f), floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f),
            floatArrayOf(0f, 0f, Float.NEGATIVE_INFINITY))
        invalid.forEach { values ->
            sensors.start(false)
            sensors.onSensorChanged(event(gyro, values))
            assertFalse("Invalid gyro vector of size ${values.size}", sensors.stable())
        }
    }

    @Test fun malformedRotationVectorsAreIgnoredWithoutCrashingOrReplacingGoodPose() {
        val invalid = listOf(floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 0f, 0f, 1f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f, 1f), floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 2f))
        invalid.forEach { values ->
            sensors.start(false)
            sensors.onSensorChanged(event(rotation, identityVector))
            val previous = requireNotNull(sensors.pose(0, false))
            val before = changes
            sensors.onSensorChanged(event(rotation, values))
            assertEquals("Invalid rotation must not publish new pose evidence", before, changes)
            assertEquals(previous, sensors.pose(0, false))
        }
    }

    @Test fun rotationWithoutRequiredGyroSupportCannotClaimAUsablePose() {
        sensors.stop()
        shadowOf(manager).removeSensor(gyro)
        val unsupported = SceneSensors(RuntimeEnvironment.getApplication()) {}
        try {
            unsupported.start(false)
            assertFalse(unsupported.supported)
            unsupported.onSensorChanged(event(rotation, identityVector))
            assertNull(unsupported.pose(0, false))
            assertFalse(unsupported.stable())
        } finally {
            unsupported.stop()
        }
    }

    private fun event(sensor: Sensor, values: FloatArray,
        timestamp: Long = SystemClock.elapsedRealtimeNanos(),
        accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH): SensorEvent {
        val event = ReflectionHelpers.callConstructor(SensorEvent::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType!!, values.size))
        event.sensor = sensor
        event.timestamp = timestamp
        event.accuracy = accuracy
        values.copyInto(event.values)
        return event
    }
}
