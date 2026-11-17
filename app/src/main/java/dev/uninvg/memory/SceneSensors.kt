package dev.uninvg.memory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

class SceneSensors(context: Context, private val onChange: () -> Unit) : SensorEventListener, LocationListener {
    private val context = context.applicationContext
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val handler = Handler(Looper.getMainLooper())
    private val poses = ArrayDeque<Pair<Long, FloatArray>>()
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var angularSpeed = Double.POSITIVE_INFINITY
    private var gyroTimestamp = 0L
    private var running = false
    private var locationEnabled = false
    private var lastLocation: PlaceFix? = null
    private val expire = Runnable { onChange() }
    val supported: Boolean get() = rotation != null && gyro != null

    fun start(useLocation: Boolean) {
        stop()
        running = true
        if (supported) {
            sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME, handler)
            sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        locationEnabled = useLocation
        if (useLocation && hasLocationPermission()) {
            try {
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (locations.isProviderEnabled(provider)) {
                        locations.getLastKnownLocation(provider)?.let(::acceptLocation)
                        locations.requestLocationUpdates(provider, 10_000L, 5f, this, Looper.getMainLooper())
                    }
                }
            } catch (error: SecurityException) {
                Log.w("UniNVG.Memory", "operation=location permission_revoked", error)
            } catch (error: IllegalArgumentException) {
                Log.w("UniNVG.Memory", "operation=location provider_unavailable", error)
            }
        }
    }

    fun stop() {
        running = false
        sensors.unregisterListener(this)
        locations.removeUpdates(this)
        handler.removeCallbacks(expire)
        poses.clear()
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        angularSpeed = Double.POSITIVE_INFINITY
        gyroTimestamp = 0L
        lastLocation = null
    }

    fun hasLocationPermission(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun place(): PlaceFix? = lastLocation?.takeIf { locationEnabled && hasLocationPermission() && System.currentTimeMillis() - it.timeMs in 0..300_000 }
    fun stable(): Boolean = angularSpeed < 0.12 && SystemClock.elapsedRealtimeNanos() - gyroTimestamp in 0..300_000_000
    fun pose(displayDegrees: Int, frontFacing: Boolean, atNs: Long = SystemClock.elapsedRealtimeNanos()): ViewPose? {
        if (!supported || accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM || !running) return null
        val nearest = poses.minByOrNull { kotlin.math.abs(it.first - atNs) } ?: return null
        if (kotlin.math.abs(nearest.first - atNs) > 300_000_000L) return null
        return ViewPose.fromDeviceMatrix(nearest.second, displayDegrees, frontFacing)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (event.values.size < 3 || event.values.take(3).any { !it.isFinite() }) {
                angularSpeed = Double.POSITIVE_INFINITY
                gyroTimestamp = 0
                return
            }
            angularSpeed = sqrt(event.values.take(3).sumOf { it.toDouble() * it })
            gyroTimestamp = event.timestamp
        } else if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if (event.values.size < 3 || event.values.take(4).any { !it.isFinite() }) return
            val norm = event.values.take(4).sumOf { it.toDouble() * it }
            if (event.values.size >= 4 && kotlin.math.abs(norm - 1) > 0.02) return
            if (event.values.size == 3 && norm > 1.02) return
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            poses.addLast(event.timestamp to matrix)
            while (poses.size > 120) poses.removeFirst()
            accuracy = event.accuracy
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, 350)
            onChange()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) { this.accuracy = accuracy; onChange() }
    }
    override fun onLocationChanged(location: Location) { if (running) { acceptLocation(location); onChange() } }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { lastLocation = null; onChange() }
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun acceptLocation(location: Location) {
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0 || location.time <= 0) return
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (System.currentTimeMillis() - location.time !in 0..300_000) return
        val previous = lastLocation
        if (previous == null || location.time > previous.timeMs) lastLocation = PlaceFix(location.latitude, location.longitude, location.accuracy, location.time)
    }
}
