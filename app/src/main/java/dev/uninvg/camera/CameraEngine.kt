package dev.uninvg.camera

import android.Manifest
import androidx.annotation.RequiresApi
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import dev.uninvg.core.CameraCaps
import dev.uninvg.core.CaptureMetadata
import dev.uninvg.core.CapturePlan
import dev.uninvg.core.DeviceIdentity
import dev.uninvg.core.DeviceProfile
import dev.uninvg.core.ExposurePlanner
import dev.uninvg.core.FpsRange
import dev.uninvg.core.NvSettings
import dev.uninvg.core.ProfileSelector
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CameraInfo(
    val cameraId: String,
    val profile: DeviceProfile,
    val caps: CameraCaps,
    val width: Int,
    val height: Int,
    val sensorOrientation: Int,
    val frontFacing: Boolean,
    val hasFlash: Boolean,
    val diagnostics: String,
    val tanHalfFovX: Double? = null,
    val tanHalfFovY: Double? = null
)

/** Owns the camera and Surface wrapper, never the caller's SurfaceTexture. */
class CameraEngine(context: Context, private val listener: Listener) {
    interface Listener {
        fun onReady(info: CameraInfo)
        fun onCapture(metadata: CaptureMetadata)
        fun onError(message: String)
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("NvCamera").apply { start() }
    private val worker = Handler(thread.looper)
    private val gate = Any()
    private val generation = AtomicLong()
    @Volatile private var released = false
    @Volatile private var callbackAttempt: Attempt? = null
    private var desired: Run? = null
    private var attempt: Attempt? = null
    private var releaseDraining = false
    private var stoppedCallback: Pair<Long, () -> Unit>? = null
    private val manager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private class Run(
        val generation: Long,
        val texture: SurfaceTexture,
        val identity: DeviceIdentity,
        var settings: NvSettings
    ) {
        var exposureProduct = 1_000_000_000.0
        var candidate: Candidate? = null
        var retried = false
        var retryReason: String? = null
    }

    private data class Candidate(
        val id: String,
        val characteristics: CameraCharacteristics,
        val keys: Set<CaptureRequest.Key<*>>,
        val sizes: List<Size>,
        val durations: Map<Size, Long>,
        val sensorArea: Double,
        val equivalentFocal: Double?,
        val aperture: Float?
    )

    private class Attempt(val run: Run, val candidate: Candidate, val info: CameraInfo) {
        var surface: Surface? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var opening = false
        var configuring = false
        var closing = false
        var failed = false
        var ready = false
        var lastParameters: Parameters? = null
        var captureFailures = 0
        var region: MeteringRectangle? = null
        var focusMode: Int? = null
        var focusSerial = 0L
        var focusTimeout: Runnable? = null
        var startupTimeout: Runnable? = null
    }

    private data class Parameters(
        val manual: Boolean,
        val exposure: Long?,
        val iso: Int?,
        val duration: Long?,
        val aeRange: FpsRange?,
        val compensation: Int,
        val torch: Boolean
    )

    private data class FocusTag(val serial: Long, val trigger: Int)

    fun start(texture: SurfaceTexture, identity: DeviceIdentity, settings: NvSettings) {
        synchronized(gate) {
            if (released) return
            val token = generation.incrementAndGet()
            worker.post {
                if (!current(token)) return@post
                desired = Run(token, texture, identity, settings)
                stoppedCallback = null
                callbackAttempt = null
                val old = attempt
                if (old != null) retire(old) else advance()
            }
        }
    }

    fun update(settings: NvSettings, exposureProduct: Double) {
        synchronized(gate) {
            if (released) return
            val token = generation.get()
            worker.post {
                val run = desired?.takeIf { it.generation == token && current(token) } ?: return@post
                run.settings = settings
                // A bad feedback sample must not take down an otherwise healthy preview.
                if (exposureProduct.isFinite() && exposureProduct > 0.0) run.exposureProduct = exposureProduct
                val active = attempt?.takeIf { live(it) && it.ready } ?: return@post
                operate(active, "update repeating request") { repeat(active) }
            }
        }
    }

    /** Coordinates are normalized over the sensor active array; rotation/mirroring belong to the caller. */
    fun focus(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        synchronized(gate) {
            if (released) return
            val token = generation.get()
            worker.post {
                if (!current(token)) return@post
                val active = attempt?.takeIf { live(it) && it.ready } ?: return@post
                operate(active, "focus") { focusInternal(active, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) }
            }
        }
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        synchronized(gate) {
            if (released) return
            val token = generation.incrementAndGet()
            worker.post {
                desired = null
                callbackAttempt = null
                stoppedCallback = onStopped?.let { token to it }
                attempt?.let(::retire)
                deliverStopped()
            }
        }
    }

    private fun deliverStopped() {
        if (attempt != null || desired != null) return
        stoppedCallback?.let { (token, callback) -> notify(token, action = callback) }
        stoppedCallback = null
    }

    /** Nonblocking: the worker drains outstanding open/close callbacks before quitting. */
    fun release() {
        synchronized(gate) {
            if (released) return
            released = true
            generation.incrementAndGet()
            worker.post {
                releaseDraining = true
                desired = null
                callbackAttempt = null
                attempt?.let(::retire)
                quitIfReleased()
            }
        }
    }

    private fun current(token: Long) = !released && generation.get() == token
    private fun live(a: Attempt) = attempt === a && desired === a.run && current(a.run.generation) && !a.closing

    private fun advance() {
        if (attempt != null) return
        val run = desired?.takeIf { current(it.generation) }
        if (run == null) {
            deliverStopped()
            quitIfReleased()
            return
        }
        try {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("CAMERA permission has not been granted")
            }
            val service = manager ?: throw IllegalStateException("Camera service unavailable")
            val candidate = run.candidate ?: selectCamera(service).also { run.candidate = it }
            val initialCaps = capabilities(candidate, 0L)
            val profileIdentity = if (run.retried) DeviceIdentity("", "", "") else run.identity
            var profile = ProfileSelector.select(profileIdentity, initialCaps)
            var size = selectSize(candidate, if (run.retried) min(360, run.settings.resolution) else run.settings.resolution, run.settings.fps)
            var caps = capabilities(candidate, candidate.durations.getValue(size))
            val checkedProfile = ProfileSelector.select(profileIdentity, caps)
            if (checkedProfile != profile) {
                profile = checkedProfile
                size = selectSize(candidate, if (run.retried) min(360, run.settings.resolution) else run.settings.resolution, run.settings.fps)
                caps = capabilities(candidate, candidate.durations.getValue(size))
            }
            val c = candidate.characteristics
            val info = CameraInfo(
                candidate.id, profile, caps, size.width, size.height,
                c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
                c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT,
                c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true,
                diagnostics(service, run, candidate, profile, caps, size),
                c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.width?.toDouble()?.let { physical ->
                    c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.takeIf { it > 0 }?.let { physical / (2 * it) }
                },
                c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.height?.toDouble()?.let { physical ->
                    c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.takeIf { it > 0 }?.let { physical / (2 * it) }
                }
            )
            if (!current(run.generation) || desired !== run) return
            val next = Attempt(run, candidate, info)
            attempt = next
            operate(next, "open camera") {
                run.texture.setDefaultBufferSize(size.width, size.height)
                next.surface = Surface(run.texture)
                check(next.surface?.isValid == true) { "Preview Surface is invalid" }
                next.startupTimeout = Runnable {
                    if (live(next) && !next.ready) fail(next, "Camera open/configuration timed out", null)
                }.also { worker.postDelayed(it, 12_000L) }
                next.opening = true
                try {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        throw SecurityException("CAMERA permission was revoked before open")
                    }
                    service.openCamera(candidate.id, deviceCallback(next), worker)
                } catch (e: Exception) {
                    next.opening = false
                    throw e
                }
            }
        } catch (e: Exception) {
            terminal(run, "Camera discovery/stream selection failed", e)
        }
    }

    private fun deviceCallback(a: Attempt) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            a.opening = false
            a.device = camera
            if (!live(a)) {
                retire(a)
                return
            }
            operate(a, "configure SurfaceTexture session") {
                a.configuring = true
                try {
                    // Exactly one PRIVATE output: analysis reads the original texture in the renderer.
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(requireNotNull(a.surface)), sessionCallback(a), worker)
                } catch (e: Exception) {
                    a.configuring = false
                    throw e
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            a.opening = false
            a.device = camera
            fail(a, "Camera disconnected", null)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            a.opening = false
            a.device = camera
            val reason = when (error) {
                ERROR_CAMERA_IN_USE -> "camera in use"
                ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
                ERROR_CAMERA_DISABLED -> "camera disabled by policy"
                ERROR_CAMERA_DEVICE -> "camera device failure"
                ERROR_CAMERA_SERVICE -> "camera service failure"
                else -> "error $error"
            }
            fail(a, "Camera open/device error: $reason", null, error != ERROR_CAMERA_DISABLED)
        }

        override fun onClosed(camera: CameraDevice) {
            if (a.device === camera) a.device = null
            // Device close releases all streams; session sequence-draining callbacks may be suppressed.
            a.session = null
            a.opening = false
            a.configuring = false
            if (live(a)) fail(a, "Camera closed unexpectedly", null) else finishRetiring(a)
        }
    }

    private fun sessionCallback(a: Attempt) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            a.configuring = false
            a.session = session
            if (!live(a)) {
                retire(a)
                return
            }
            operate(a, "start repeating preview") {
                repeat(a)
                if (!live(a)) {
                    retire(a)
                    return@operate
                }
                // AUTO-only lenses need a scan; IDLE alone would leave their initial focus unchanged.
                if (preferredAf(a.candidate.characteristics) == CaptureRequest.CONTROL_AF_MODE_AUTO &&
                    CaptureRequest.CONTROL_AF_MODE in a.candidate.keys && CaptureRequest.CONTROL_AF_TRIGGER in a.candidate.keys) {
                    session.capture(request(a, requireNotNull(a.lastParameters), CaptureRequest.CONTROL_AF_TRIGGER_START).build(), captureCallback(a), worker)
                }
                a.ready = true
                a.startupTimeout?.let(worker::removeCallbacks)
                a.startupTimeout = null
                callbackAttempt = a
                notify(a.run.generation, a) { listener.onReady(a.info) }
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            a.configuring = false
            // A failed session is already closed and need not issue an onClosed callback.
            try {
                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "${label(a)} closing failed session", e)
            }
            a.session = null
            fail(a, "SurfaceTexture session configuration failed", null)
        }

        override fun onClosed(session: CameraCaptureSession) {
            if (a.session === session) a.session = null
            a.configuring = false
            if (live(a)) fail(a, "Preview session closed unexpectedly", null) else finishRetiring(a)
        }
    }

    private fun captureCallback(a: Attempt) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!live(a) || !a.ready || a.session !== session) return
            a.captureFailures = 0
            val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]
            if (timestamp != null) {
                val metadata = CaptureMetadata(
                    timestamp,
                    result[CaptureResult.SENSOR_EXPOSURE_TIME],
                    result[CaptureResult.SENSOR_SENSITIVITY],
                    result[CaptureResult.SENSOR_FRAME_DURATION]
                )
                notify(a.run.generation, a) { listener.onCapture(metadata) }
            }
            val tag = request.tag as? FocusTag
            val af = result[CaptureResult.CONTROL_AF_STATE]
            if (a.focusMode != null && tag?.serial == a.focusSerial &&
                tag.trigger != CaptureRequest.CONTROL_AF_TRIGGER_CANCEL &&
                (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                operate(a, "restore continuous AF") { restoreFocus(a) }
            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (!live(a) || a.session !== session) return
            a.captureFailures++
            if (a.captureFailures == 1) Log.w(TAG, "${label(a)} capture failed: reason=${failure.reason}, frame=${failure.frameNumber}")
            if (a.captureFailures >= 3) fail(a, "Three consecutive preview capture failures (reason ${failure.reason})", null)
        }
    }

    private fun parameters(a: Attempt): Parameters {
        val caps = a.info.caps
        val settings = a.run.settings
        val plan: CapturePlan = ExposurePlanner.plan(caps, settings, a.run.exposureProduct)
        if (plan.manual) {
            require(caps.manualSensor && plan.iso in caps.isoMin..caps.isoMax &&
                plan.exposureNs in caps.exposureMinNs..caps.exposureMaxNs &&
                plan.frameDurationNs >= max(plan.exposureNs, caps.minFrameDurationNs) &&
                plan.frameDurationNs <= caps.maxFrameDurationNs) { "ExposurePlanner returned unsupported sensor controls: $plan" }
        }
        val c = a.candidate.characteristics
        val compensationRange = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
        val step = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toDouble() ?: 0.0
        val compensation = if (!plan.manual && compensationRange != null && step.isFinite() && step > 0.0) {
            (settings.exposureBias / step).roundToInt().coerceIn(compensationRange.lower, compensationRange.upper)
        } else 0
        val aeRange = plan.aeRange?.takeIf { it in caps.aeFpsRanges }
        return Parameters(
            plan.manual, plan.exposureNs.takeIf { plan.manual }, plan.iso.takeIf { plan.manual },
            plan.frameDurationNs.takeIf { plan.manual }, aeRange, compensation,
            settings.torch && a.info.hasFlash
        )
    }

    private fun repeat(a: Attempt, force: Boolean = false) {
        if (!live(a)) return
        val parameters = parameters(a)
        if (!force && parameters == a.lastParameters) return
        val session = requireNotNull(a.session)
        val request = request(a, parameters).build()
        session.setRepeatingRequest(request, captureCallback(a), worker)
        a.lastParameters = parameters
    }

    private fun request(a: Attempt, p: Parameters, trigger: Int = CaptureRequest.CONTROL_AF_TRIGGER_IDLE): CaptureRequest.Builder {
        val c = a.candidate.characteristics
        val builder = requireNotNull(a.device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(requireNotNull(a.surface))
        set(a, builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= 31 &&
            c[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES]?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
            set(a, builder, CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
        if (p.manual) {
            set(a, builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(a, builder, CaptureRequest.SENSOR_EXPOSURE_TIME, requireNotNull(p.exposure))
            set(a, builder, CaptureRequest.SENSOR_SENSITIVITY, requireNotNull(p.iso))
            set(a, builder, CaptureRequest.SENSOR_FRAME_DURATION, requireNotNull(p.duration))
        } else {
            check(c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AE_MODE_ON) == true) {
                "Camera does not advertise non-flash auto exposure"
            }
            set(a, builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            p.aeRange?.let { set(a, builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.lower, it.upper)) }
            set(a, builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.compensation)
            if (c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true) set(a, builder, CaptureRequest.CONTROL_AE_LOCK, false)
        }
        val afMode = a.focusMode ?: preferredAf(c)
        afMode?.let { set(a, builder, CaptureRequest.CONTROL_AF_MODE, it) }
        if (afMode != null && afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
            set(a, builder, CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }
        if (c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO) == true) {
            set(a, builder, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        if (c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true) set(a, builder, CaptureRequest.CONTROL_AWB_LOCK, false)
        set(a, builder, CaptureRequest.FLASH_MODE, if (p.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        mode(c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION],
            *(if (a.info.profile.preferOis) intArrayOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            else intArrayOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)))?.let {
            set(a, builder, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it)
        }
        mode(c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES], CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)?.let {
            set(a, builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
        }
        // MINIMAL/FAST preserve cadence; renderer performs denoising and tone mapping.
        mode(c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES],
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL, CaptureRequest.NOISE_REDUCTION_MODE_FAST, CaptureRequest.NOISE_REDUCTION_MODE_OFF)?.let {
            set(a, builder, CaptureRequest.NOISE_REDUCTION_MODE, it)
        }
        mode(c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES], CaptureRequest.EDGE_MODE_OFF, CaptureRequest.EDGE_MODE_FAST)?.let {
            set(a, builder, CaptureRequest.EDGE_MODE, it)
        }
        mode(c[CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES], CaptureRequest.HOT_PIXEL_MODE_FAST, CaptureRequest.HOT_PIXEL_MODE_OFF)?.let {
            set(a, builder, CaptureRequest.HOT_PIXEL_MODE, it)
        }
        a.region?.let { region ->
            if (supportsAfRegion(a)) set(a, builder, CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if (!p.manual && supportsAeRegion(a)) set(a, builder, CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
        if (a.focusMode != null) builder.setTag(FocusTag(a.focusSerial, trigger))
        return builder
    }

    private fun <T> set(a: Attempt, builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        if (key in a.candidate.keys) builder.set(key, value)
    }

    private fun mode(available: IntArray?, vararg preference: Int): Int? = preference.firstOrNull { available?.contains(it) == true }

    private fun preferredAf(c: CameraCharacteristics) = mode(c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES],
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
        CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_OFF)

    private fun supportsAfRegion(a: Attempt) =
        (a.candidate.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0 &&
            CaptureRequest.CONTROL_AF_REGIONS in a.candidate.keys &&
            preferredAf(a.candidate.characteristics)?.let { it != CaptureRequest.CONTROL_AF_MODE_OFF } == true

    private fun supportsAeRegion(a: Attempt) =
        (a.candidate.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0 &&
            CaptureRequest.CONTROL_AE_REGIONS in a.candidate.keys

    private fun focusInternal(a: Attempt, x: Float, y: Float) {
        val afSupported = supportsAfRegion(a)
        val aeSupported = !a.info.caps.manualSensor && supportsAeRegion(a)
        if (!afSupported && !aeSupported) return
        val active = a.candidate.characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return
        if (active.width() <= 0 || active.height() <= 0) return
        val width = max(1, (active.width() * 0.15f).roundToInt())
        val height = max(1, (active.height() * 0.15f).roundToInt())
        val left = (active.left + x * active.width() - width / 2f).roundToInt().coerceIn(active.left, active.right - width)
        val top = (active.top + y * active.height() - height / 2f).roundToInt().coerceIn(active.top, active.bottom - height)
        a.region = MeteringRectangle(Rect(left, top, left + width, top + height), MeteringRectangle.METERING_WEIGHT_MAX)
        val canTrigger = afSupported && CaptureRequest.CONTROL_AF_TRIGGER in a.candidate.keys && CaptureRequest.CONTROL_AF_MODE in a.candidate.keys
        if (!canTrigger) {
            repeat(a, true)
            return
        }
        a.focusTimeout?.let(worker::removeCallbacks)
        a.focusSerial++
        a.focusMode = mode(a.candidate.characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES],
            CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        val p = parameters(a)
        val session = requireNotNull(a.session)
        session.capture(request(a, p, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(), captureCallback(a), worker)
        session.capture(request(a, p, CaptureRequest.CONTROL_AF_TRIGGER_START).build(), captureCallback(a), worker)
        repeat(a, true)
        a.focusTimeout = Runnable {
            if (live(a) && a.ready) operate(a, "AF timeout recovery") { restoreFocus(a) }
        }.also { worker.postDelayed(it, 3_000L) }
    }

    private fun restoreFocus(a: Attempt) {
        a.focusTimeout?.let(worker::removeCallbacks)
        a.focusTimeout = null
        a.focusMode = null
        // CANCEL also unlocks continuous modes on devices with no AF_MODE_AUTO.
        val p = parameters(a)
        requireNotNull(a.session).capture(request(a, p, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(), captureCallback(a), worker)
        repeat(a, true)
    }

    private fun operate(a: Attempt, operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            fail(a, operation, e, e !is SecurityException)
        }
    }

    private fun fail(a: Attempt, operation: String, error: Exception?, retry: Boolean = true) {
        if (!live(a)) {
            retire(a)
            return
        }
        if (a.failed) return
        a.failed = true
        val message = "${label(a)} $operation${error?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""}"
        Log.e(TAG, message, error)
        callbackAttempt = null
        if (retry && !a.run.retried) {
            a.run.retried = true
            a.run.retryReason = message
            Log.w(TAG, "Retrying with Universal profile and an advertised stream no larger than 640")
        } else {
            desired = null
            notify(a.run.generation) { listener.onError("$message${if (a.run.retried) "; conservative retry exhausted" else ""}") }
        }
        retire(a)
    }

    private fun terminal(run: Run, operation: String, error: Exception) {
        if (!current(run.generation) || desired !== run) return
        val message = "$operation (camera=${run.candidate?.id ?: "unselected"}, API=${Build.VERSION.SDK_INT}, model=${Build.MODEL})" +
            ": ${error.javaClass.simpleName}: ${error.message}${if (run.retried) "; conservative retry exhausted" else ""}"
        Log.e(TAG, message, error)
        desired = null
        callbackAttempt = null
        attempt?.let(::retire)
        notify(run.generation) { listener.onError(message) }
    }

    private fun retire(a: Attempt) {
        if (!a.closing) {
            a.closing = true
            a.ready = false
            if (callbackAttempt === a) callbackAttempt = null
            a.startupTimeout?.let(worker::removeCallbacks)
            a.focusTimeout?.let(worker::removeCallbacks)
            a.startupTimeout = null
            a.focusTimeout = null
        }
        a.session?.let { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "${label(a)} closing session", e)
                a.session = null
            }
        }
        a.device?.let { camera ->
            try {
                camera.close()
            } catch (e: Exception) {
                Log.e(TAG, "${label(a)} closing camera", e)
                a.device = null
            }
        }
        finishRetiring(a)
    }

    private fun finishRetiring(a: Attempt) {
        if (!a.closing || a.opening || a.configuring || a.device != null || a.session != null) return
        // Do not resize the same BufferQueue or open a replacement until old producers are closed.
        a.surface?.let { surface ->
            try {
                surface.release()
            } catch (e: Exception) {
                Log.e(TAG, "${label(a)} releasing Surface", e)
            }
        }
        a.surface = null
        if (attempt === a) {
            attempt = null
            advance()
        }
        quitIfReleased()
    }

    private fun quitIfReleased() {
        if (releaseDraining && attempt == null) thread.quitSafely()
    }

    private fun notify(token: Long, a: Attempt? = null, action: () -> Unit) {
        main.post {
            if (!current(token) || (a != null && callbackAttempt !== a)) return@post
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Listener failed (generation=$token)", e)
            }
        }
    }

    private fun label(a: Attempt) = "camera=${a.candidate.id} generation=${a.run.generation} " +
        "profile=${a.info.profile.id} stream=${a.info.width}x${a.info.height}"

    private fun selectCamera(service: CameraManager): Candidate {
        val candidates = service.cameraIdList.sorted().mapNotNull { id ->
            try {
                val c = service.getCameraCharacteristics(id)
                val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return@mapNotNull null
                val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.filter { it.width > 0 && it.height > 0 }?.distinct().orEmpty()
                if (sizes.isEmpty()) return@mapNotNull null
                val physical = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
                val diagonal = physical?.let { hypot(it.width.toDouble(), it.height.toDouble()) } ?: 0.0
                val equivalents = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.filter { it > 0f && it.isFinite() }
                    ?.mapNotNull { if (diagonal > 0.0) it * 43.27 / diagonal else null }.orEmpty()
                val equivalent = equivalents.minByOrNull { abs(it - 26.0) }
                val aperture = c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.filter { it > 0f && it.isFinite() }?.minOrNull()
                val durations = sizes.associateWith { size ->
                    try {
                        map.getOutputMinFrameDuration(SurfaceTexture::class.java, size).coerceAtLeast(0L)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "camera=$id no minimum duration for $size", e)
                        0L
                    }
                }
                Candidate(id, c, c.availableCaptureRequestKeys.orEmpty().toSet(), sizes, durations,
                    physical?.let { (it.width.toDouble() * it.height).takeIf { area -> area.isFinite() && area > 0.0 } } ?: 0.0,
                    equivalent, aperture)
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Cannot interrogate camera=$id", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid characteristics for camera=$id", e)
                null
            }
        }
        check(candidates.isNotEmpty()) { "No camera advertises a SurfaceTexture output" }
        val rear = candidates.filter { it.characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
        // Camera2 has no main-lens label; normal field of view avoids ultrawide/tele traps.
        return (rear.ifEmpty { candidates }).sortedWith(
            compareByDescending<Candidate> { when (it.equivalentFocal) { null -> 1; in 18.0..40.0 -> 2; else -> 0 } }
                .thenByDescending { it.sensorArea }
                .thenBy { it.aperture ?: Float.MAX_VALUE }
                .thenByDescending { capabilities(it, 0L).manualSensor }
                .thenByDescending { hardwareRank(it.characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]) }
                .thenBy { abs((it.equivalentFocal ?: 26.0) - 26.0) }
                .thenBy { it.id }
        ).first()
    }

    private fun selectSize(candidate: Candidate, resolution: Int, fps: Float): Size {
        val bounded = candidate.sizes.filter {
            max(it.width, it.height).toDouble() / min(it.width, it.height) <= 2.0
        }
        check(bounded.isNotEmpty()) { "camera=${candidate.id} has no reasonable SurfaceTexture size" }
        val reasonable = bounded.filter { max(it.width, it.height).toDouble() / min(it.width, it.height) in 1.2..1.9 }.ifEmpty { bounded }
        val budget = (1_000_000_000.0 / fps).toLong()
        val fast = reasonable.filter { candidate.durations.getValue(it) <= budget }.ifEmpty { reasonable }
        return fast.minWithOrNull(
            compareBy<Size> { abs(min(it.width, it.height) - resolution) }
                .thenByDescending { it.width.toLong() * it.height }
                .thenBy { candidate.durations.getValue(it) }
        ) ?: error("No stream size available")
    }

    private fun capabilities(candidate: Candidate, minimumDuration: Long): CameraCaps {
        val c = candidate.characteristics
        val iso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.takeIf { it.lower > 0 && it.upper >= it.lower }
        val exposure = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.takeIf { it.lower > 0L && it.upper >= it.lower }
        val duration = c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION]?.takeIf { it > 0L }
        val exposureMin = exposure?.lower ?: 1_000_000L
        val maxDuration = max(duration ?: 33_333_333L, exposureMin)
        val writable = MANUAL_KEYS.all { it in candidate.keys }
        val manual = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
            writable && iso != null && exposure != null && duration != null && duration >= exposureMin && duration >= minimumDuration &&
            c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true &&
            c[CameraCharacteristics.CONTROL_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_MODE_AUTO) == true
        return CameraCaps(
            manual, iso?.lower ?: 100, iso?.upper ?: 100,
            c[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY]?.takeIf { it > 0 },
            exposureMin, exposure?.upper ?: 33_333_333L, maxDuration, minimumDuration,
            c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
                .filter { it.lower > 0 && it.upper >= it.lower }.map { FpsRange(it.lower, it.upper) }.distinct()
        )
    }

    private fun diagnostics(service: CameraManager, run: Run, candidate: Candidate, profile: DeviceProfile, caps: CameraCaps, size: Size): String {
        val c = candidate.characteristics
        val level = c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
        val hardware = when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "unknown($level)"
        }
        val night = if (Build.VERSION.SDK_INT >= 31) NightExtension.query(service, candidate.id) else "not queryable before API 31"
        val missing = MANUAL_KEYS.filterNot { it in candidate.keys }.joinToString { it.name }
        return buildString {
            appendLine("API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}; identity=${run.identity}; profile=${profile.label}")
            appendLine("Camera ${candidate.id}; hardware=$hardware; facing=${c[CameraCharacteristics.LENS_FACING]}; orientation=${c[CameraCharacteristics.SENSOR_ORIENTATION]}")
            appendLine("Main-lens heuristic: equivalent focal=${candidate.equivalentFocal}mm, aperture=f/${candidate.aperture}, sensor area=${candidate.sensorArea}mm²; rear/normal FOV, area, aperture, manual/full preference")
            appendLine("Capabilities=${c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.joinToString()}; request keys=${candidate.keys.size}")
            appendLine("Manual usable=${caps.manualSensor}; missing manual keys=${missing.ifEmpty { "none" }}; AE modes=${c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.joinToString()}")
            appendLine("ISO reported=${c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]}, effective=${caps.isoMin}..${caps.isoMax}; analog reported=${caps.maxAnalogIso}, effective ceiling=${caps.analogCeiling}")
            appendLine("Exposure reported=${c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]}ns; effective=${caps.exposureMinNs}..${caps.exposureMaxNs}ns; max frame reported=${c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION]}ns")
            appendLine("SurfaceTexture only $size; stream minimum=${caps.minFrameDurationNs}ns (0=unknown); advertised durations=" +
                candidate.durations.entries.filter { max(it.key.width, it.key.height) <= 4096 }.joinToString { "${it.key}:${it.value}ns" })
            appendLine("AE FPS=${caps.aeFpsRanges.joinToString { "${it.lower}-${it.upper}" }}; requested=${run.settings.fps}; resolution=${run.settings.resolution}p; AE uses advertised ranges only")
            appendLine("AE compensation=${c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]}, EV step=${c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]}; sensor timestamp source=${c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]}")
            appendLine("NR=${c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.joinToString()} (0=OFF,1=FAST,2=HQ,3=MINIMAL; prefer MINIMAL/FAST/OFF)")
            appendLine("OIS=${c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.joinToString()} (0=OFF,1=ON); prefer=${profile.preferOis}; flash=${c[CameraCharacteristics.FLASH_INFO_AVAILABLE]}")
            appendLine("AF=${c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.joinToString()}; AF/AE regions=${c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF]}/${c[CameraCharacteristics.CONTROL_MAX_REGIONS_AE]}")
            appendLine("NIGHT extension: $night; query only, NOT applied to this ordinary Camera2 preview")
            append("Public Camera2 limits only; root (including A71) does not unlock controls here.")
            run.retryReason?.let { append("\nConservative 640/Universal retry after: $it") }
        }
    }

    @RequiresApi(31)
    private object NightExtension {
        fun query(service: CameraManager, id: String): String = try {
            val extensions = service.getCameraExtensionCharacteristics(id).supportedExtensions
            "advertised=${CameraExtensionCharacteristics.EXTENSION_NIGHT in extensions}; supported=$extensions"
        } catch (e: Exception) {
            Log.w(TAG, "camera=$id optional extension query unavailable", e)
            "query unavailable (${e.javaClass.simpleName})"
        }
    }

    private fun hardwareRank(level: Int?) = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> 4
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> 3
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> 2
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> 1
        else -> 0
    }

    private companion object {
        const val TAG = "NvCamera"
        val MANUAL_KEYS = listOf(
            CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_SENSITIVITY,
            CaptureRequest.SENSOR_FRAME_DURATION
        )
    }
}
