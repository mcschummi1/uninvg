package dev.uninvg

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import dev.uninvg.memory.SceneSensors
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SceneSensorsTest {
    private lateinit var manager: SensorManager
    private lateinit var sensors: SceneSensors
    private lateinit var rotation: Sensor
    private lateinit var gyro: Sensor

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotation = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
        gyro = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)
        shadowOf(manager).addSensor(rotation)
        shadowOf(manager).addSensor(gyro)
        sensors = SceneSensors(context) {}
    }
    @After fun stop() { sensors.stop() }

    @Test fun registersOnlyWhileActiveAndClearsPoseOnStop() {
        assertTrue(sensors.supported)
        assertFalse(shadowOf(manager).hasListener(sensors))
        sensors.start(false)
        assertTrue(shadowOf(manager).hasListener(sensors))
        sample()
        assertNotNull(sensors.pose(0, false))
        sensors.stop()
        assertFalse(shadowOf(manager).hasListener(sensors))
        assertNull(sensors.pose(0, false))
        assertFalse(sensors.stable())
    }
    @Test fun lowOrientationAccuracyNeverProvidesAnOverlayPose() {
        sensors.start(false)
        sample(accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE)
        assertNull(sensors.pose(0, false))
        sample(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        assertNotNull(sensors.pose(0, false))
        sensors.onAccuracyChanged(rotation, SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        assertNull(sensors.pose(0, false))
    }
    @Test fun oldPoseCannotRemainUsableIndefinitely() {
        sensors.start(false)
        sample()
        val timestamp = SystemClock.elapsedRealtimeNanos()
        assertNotNull(sensors.pose(0, false, timestamp))
        assertNull(sensors.pose(0, false, timestamp + 400_000_000))
    }
    @Test fun movingGyroPreventsStableCapture() {
        sensors.start(false)
        sample(speed = 0.5f)
        assertFalse(sensors.stable())
        sample(speed = 0.01f)
        assertTrue(sensors.stable())
    }
    @Test fun stoppingLocationRequiresNoPermissionAndNeverReturnsOldFix() {
        sensors.start(false)
        assertNull(sensors.place())
        sensors.stop()
        assertNull(sensors.place())
    }
    @Test fun missingGyroscopeDisablesFeatureInsteadOfInventingOrientation() {
        shadowOf(manager).removeSensor(gyro)
        sensors = SceneSensors(RuntimeEnvironment.getApplication()) {}
        assertFalse(sensors.supported)
        sensors.start(false)
        assertNull(sensors.pose(0, false))
        assertFalse(shadowOf(manager).hasListener(sensors))
    }
    private fun sample(accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH, speed: Float = 0f) {
        val timestamp = SystemClock.elapsedRealtimeNanos()
        sensors.onSensorChanged(event(gyro, floatArrayOf(speed, 0f, 0f), timestamp, accuracy))
        sensors.onSensorChanged(event(rotation, floatArrayOf(0f, 0f, 0f, 1f), timestamp, accuracy))
    }
    private fun event(sensor: Sensor, values: FloatArray, timestamp: Long, accuracy: Int): SensorEvent {
        val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
        return constructor.newInstance(values.size).apply {
            this.sensor = sensor
            this.timestamp = timestamp
            this.accuracy = accuracy
            values.copyInto(this.values)
        }
    }
}
