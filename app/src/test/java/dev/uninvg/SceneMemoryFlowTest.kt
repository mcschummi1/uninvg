package dev.uninvg

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import dev.uninvg.camera.CameraInfo
import dev.uninvg.core.*
import dev.uninvg.memory.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowSensor
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneMemoryFlowTest {
    private lateinit var activity: Activity
    private lateinit var controller: SceneMemoryController
    private lateinit var overlay: SceneOverlay
    private lateinit var manager: SensorManager
    private lateinit var gyro: Sensor
    private lateinit var rotation: Sensor
    private val worker = ControlledExecutor()
    private val lit = FloatArray(4096) { index -> if ((index % 64) / 8 % 2 == 0 || (index / 64) / 8 % 2 == 0) 0.8f else 0.3f }

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
        manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotation = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
        gyro = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)
        shadowOf(manager).addSensor(rotation)
        shadowOf(manager).addSensor(gyro)
        overlay = SceneOverlay(activity)
        controller = SceneMemoryController(activity, overlay, { fail("Location must remain opt-in") }, {
            activity.setContentView(controller.panel())
        }, worker)
        val caps = CameraCaps(true, 100, 6400, 800, 100_000, 1_000_000_000, 1_000_000_000)
        val profile = ProfileSelector.select(DeviceIdentity("samsung", "SM-S921B", "Exynos 2400"), caps)
        controller.camera(CameraInfo("0", profile, caps, 1280, 720, 90, false, true, "test", 0.7, 0.5), 0)
        controller.resume()
        activity.setContentView(controller.panel())
        drain()
    }
    @After fun finish() { controller.release(); activity.finish() }

    @Test fun captureSaveHideAndRecallKeepHistoricalLabel() {
        sensorFrames()
        controller.reference(lit, 64, 64)
        drain()
        button("Capture lit scene").performClick()
        button("Add view").performClick()
        button("Finish scene").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        children(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("Test room")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        assertEquals(1, SceneStore(File(activity.filesDir, "scene-memory")).list().scenes.size)
        sensorFrames()
        assertTrue(overlay.contentDescription.toString().contains("position unverified") || overlay.contentDescription.toString().contains("not live"))
        button("Hide saved outlines").performClick()
        assertEquals(View.GONE, overlay.visibility)
        button("Test room").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        sensorFrames()
        assertEquals(View.VISIBLE, overlay.visibility)
        saveOverlay()
    }

    @Test fun darkFrameCannotBeSavedAsALitScene() {
        sensorFrames()
        controller.reference(FloatArray(4096), 64, 64)
        drain()
        button("Capture lit scene").performClick()
        button("Add view").performClick()
        button("Finish scene").performClick()
        assertTrue(SceneStore(File(activity.filesDir, "scene-memory")).list().scenes.isEmpty())
    }

    @Test fun sourceChangeInvalidatesPendingReferenceAndCapture() {
        sensorFrames()
        controller.reference(lit, 64, 64)
        controller.camera(null, 0)
        drain()
        button("Capture lit scene").performClick()
        assertTrue(children(activity.window.decorView).filterIsInstance<Button>().none { it.text == "Add view" })
    }

    @Test fun closingIdlePanelStopsSensorTracking() {
        assertTrue(shadowOf(manager).listeners.isNotEmpty())
        controller.panelClosed()
        assertTrue(shadowOf(manager).listeners.isEmpty())
    }

    @Test fun pauseRemovesHistoricalProjectionRatherThanFreezingIt() {
        sensorFrames()
        button("Capture lit scene").performClick()
        controller.pause()
        assertEquals(View.GONE, overlay.visibility)
        assertTrue(shadowOf(manager).listeners.isEmpty())
    }

    private fun sensorFrames() {
        val timestamp = SystemClock.elapsedRealtimeNanos()
        fun event(sensor: Sensor, values: FloatArray): SensorEvent {
            val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
            return constructor.newInstance(values.size).apply {
                this.sensor = sensor
                this.timestamp = timestamp
                accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                values.copyInto(this.values)
            }
        }
        shadowOf(manager).sendSensorEventToListeners(event(gyro, floatArrayOf(0f, 0f, 0f)), gyro)
        shadowOf(manager).sendSensorEventToListeners(event(rotation, floatArrayOf(0f, 0f, 0f, 1f)), rotation)
    }

    private fun saveOverlay() {
        controller.update(NvSettings(palette = Palette.GREEN), Color.rgb(255, 92, 80))
        overlay.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        overlay.draw(Canvas(bitmap))
        val folder = File("build/reports/ui").apply { mkdirs() }
        File(folder, "scene-memory-overlay.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun drain() { repeat(4) { worker.runPending(); shadowOf(Looper.getMainLooper()).idle() } }
    private fun button(label: String) = children(activity.window.decorView).filterIsInstance<Button>().single { it.text == label }
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private class ControlledExecutor : AbstractExecutorService() {
        private val work = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); work.addLast(command) }
        fun runPending() { while (work.isNotEmpty()) work.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return work.toMutableList().also { work.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && work.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
