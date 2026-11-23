package dev.uninvg.memory

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.uninvg.camera.CameraInfo
import dev.uninvg.core.NvSettings
import dev.uninvg.core.SceneAnalyzer
import dev.uninvg.render.CameraOrientation
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class SceneMemoryController(
    private val activity: Activity,
    private val overlay: SceneOverlay,
    private val requestLocation: () -> Unit,
    private val refreshPanel: () -> Unit,
    private val work: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()
) {
    private data class Reference(val lines: List<ImageLine>, val pose: ViewPose, val lens: ViewLens, val acquiredNs: Long, val lit: Boolean, val stable: Boolean)
    private val preferences = activity.getSharedPreferences("sceneMemory", Context.MODE_PRIVATE)
    private val store = SceneStore(File(activity.filesDir, "scene-memory"))
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private val sensors = SceneSensors(activity) { redraw() }
    private var info: CameraInfo? = null
    private var display = 0
    private var lens: ViewLens? = null
    private var reference: Reference? = null
    private var draft: MutableList<ScenePatch>? = null
    private var draftPlace: PlaceFix? = null
    private var active: SavedScene? = null
    private var scenes = emptyList<SavedScene>()
    private var unreadable = 0
    private var loading = false
    private var saving = false
    private var closed = false
    private var resumed = false
    private var generation = 0
    private var settings = NvSettings()
    private var color = Color.GREEN
    private var sensorEnabled = false
    private var sensorsRunning = false
    private val referenceExpired = Runnable { reference = null; redraw() }
    private val anchors = mutableListOf<ArAnchor>()
    private var nextAnchorId = 1L
    private var horizonOn = preferences.getBoolean("horizon", false)
    private var autoCapture = preferences.getBoolean("autoCapture", true)
    private var lastAutoPatchMs = 0L

    fun setAutoCapture(enabled: Boolean) { autoCapture = enabled; preferences.edit().putBoolean("autoCapture", enabled).apply(); refreshPanel() }
    fun autoCaptureEnabled() = autoCapture
    fun setHorizon(enabled: Boolean) { horizonOn = enabled; preferences.edit().putBoolean("horizon", enabled).apply(); redraw() }
    fun horizonEnabled() = horizonOn
    fun anchorCount() = anchors.size

    /** Drop an AR pin straight ahead on a world ray; find it again from any angle, edge ticks guide the turn. */
    fun dropAnchor() {
        val camera = info
        val pose = camera?.let { sensors.pose(display, it.frontFacing) }
        if (pose == null) { toast("Hold the phone steady first"); return }
        if (anchors.size >= ArHud.MAX_ANCHORS) { toast("Anchor limit reached — clear some first"); return }
        val anchor = ArAnchor(nextAnchorId, pose.forward, "P$nextAnchorId")
        nextAnchorId++
        anchors.add(anchor)
        redraw()
        refreshPanel()
    }

    fun clearAnchors() { anchors.clear(); redraw(); refreshPanel() }

    fun camera(camera: CameraInfo?, displayDegrees: Int) {
        generation++
        reference = null
        info = camera
        display = displayDegrees
        lens = camera?.let { lens(it, displayDegrees) }
        if (camera == null && draft != null) { draft = null; draftPlace = null; toast("Scene capture stopped") }
        redraw()
    }

    fun resume() { resumed = true; startSensors(); redraw() }
    private fun startSensors() {
        if (resumed && sensorEnabled && !sensorsRunning) { sensors.start(useLocation()); sensorsRunning = true }
    }
    fun panelClosed() {
        sensorEnabled = active != null || draft != null
        if (!sensorEnabled) { sensors.stop(); sensorsRunning = false; reference = null }
    }
    fun pause() {
        resumed = false
        generation++
        reference = null
        main.removeCallbacks(referenceExpired)
        sensors.stop()
        sensorsRunning = false
        if (draft != null) { draft = null; draftPlace = null; toast("Scene capture stopped") }
        redraw()
    }
    fun release() {
        pause()
        closed = true
        work.shutdown()
    }
    fun update(settings: NvSettings, color: Int) { this.settings = settings; this.color = color; redraw() }
    fun locationPermissionChanged() {
        if (resumed && sensorEnabled) { sensors.start(useLocation()); sensorsRunning = true }
        refreshPanel()
    }

    fun reference(luma: FloatArray, width: Int, height: Int) {
        if (!resumed || !sensorEnabled || closed || !busy.compareAndSet(false, true)) return
        val camera = info
        val projection = lens
        val acquired = SystemClock.elapsedRealtimeNanos()
        val pose = camera?.let { sensors.pose(display, it.frontFacing, acquired) }
        if (projection == null || pose == null) { busy.set(false); return }
        val token = generation
        val stable = sensors.stable()
        work.execute {
            try {
                val stats = SceneAnalyzer.analyze(luma, width, height)
                val edges = SceneEdges.extract(luma, width, height)
                val result = Reference(edges, pose, projection, acquired, stats.p50 >= 0.08f && stats.noise < 0.08f, stable)
                main.post {
                    if (token == generation && resumed && !closed) {
                        reference = result
                        main.removeCallbacks(referenceExpired)
                        main.postDelayed(referenceExpired, 1500)
                        redraw()
                        maybeAutoCapture()
                    }
                }
            } catch (error: RuntimeException) {
                android.util.Log.e("UniNVG.Memory", "operation=reference_analysis", error)
                main.post { if (token == generation) { reference = null; redraw() } }
            } finally { busy.set(false) }
        }
    }

    fun panel(): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (!sensors.supported) {
            container.addView(text("Scene Memory needs gyro and orientation sensors"))
            return container
        }
        sensorEnabled = true
        startSensors()
        val location = Switch(activity).apply {
            text = "Location suggestions"
            textSize = 13f
            setTextColor(Color.rgb(235, 242, 236))
            isChecked = useLocation()
            minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("location", enabled).apply()
                if (!enabled) draftPlace = null
                if (enabled && !sensors.hasLocationPermission()) requestLocation()
                else if (resumed) sensors.start(enabled)
            }
        }
        container.addView(location)
        if (useLocation() && !sensors.hasLocationPermission()) container.addView(action("Allow location") { requestLocation() })
        container.addView(Switch(activity).apply {
            text = "Coach · auto-capture while steady"
            textSize = 13f
            setTextColor(Color.rgb(235, 242, 236))
            isChecked = autoCapture
            minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled -> setAutoCapture(enabled) }
        })
        container.addView(text("AR OVERLAY"))
        container.addView(Switch(activity).apply {
            text = "Horizon line"
            textSize = 13f
            setTextColor(Color.rgb(235, 242, 236))
            isChecked = horizonOn
            minHeight = dp(48)
            setOnCheckedChangeListener { _, enabled -> setHorizon(enabled) }
        })
        container.addView(action("Drop anchor here") { dropAnchor() })
        container.addView(action(if (anchors.isEmpty()) "Clear anchors" else "Clear anchors · ${anchors.size}") { clearAnchors() })
        if (saving) container.addView(text("Saving scene…"))
        else if (draft != null) {
            container.addView(text("${draft!!.size}/24 views · same position"))
            container.addView(text("Coach · ${coachMessage().ifEmpty { "hold steady" }}"))
            container.addView(action("Add view") { addView() })
            container.addView(action("Finish scene") { finish() })
            container.addView(action("Cancel capture") { draft = null; draftPlace = null; redraw(); refreshPanel() })
        } else container.addView(action("Capture lit scene") {
            if (info == null || lens == null) { toast("Open a live camera with known field of view"); return@action }
            active = null
            draft = mutableListOf()
            draftPlace = sensors.place()
            redraw()
            refreshPanel()
        })
        if (active != null) {
            container.addView(text("${active!!.name} · saved outlines, not live"))
            container.addView(action("Hide saved outlines") { active = null; redraw(); refreshPanel() })
        }
        container.addView(text("SAVED SCENES"))
        val place = sensors.place()
        scenes.sortedWith(compareByDescending<SavedScene> { place != null && it.place?.couldBeSamePlace(place) == true }
            .thenByDescending { it.createdMs }).forEach { scene ->
            val near = place != null && scene.place?.couldBeSamePlace(place) == true
            container.addView(action(scene.name + if (near) " · GPS nearby" else "") {
                AlertDialog.Builder(activity).setTitle(scene.name).setMessage("Saved outlines only. Position and current obstacles are not verified.")
                    .setPositiveButton("Recall") { _, _ ->
                        draft = null
                        active = scene
                        redraw()
                        refreshPanel()
                    }.setNeutralButton("Remove") { _, _ -> remove(scene) }.setNegativeButton("Cancel", null).show()
            })
        }
        if (scenes.isEmpty()) container.addView(text(if (loading) "Loading scenes…" else "No saved scenes"))
        if (unreadable > 0) container.addView(text("$unreadable unreadable saved scenes"))
        if (!loading) reload()
        return container
    }

    private fun addView() {
        val patches = draft ?: return
        val reason = captureGuard(reference, patches)
        if (reason != null) { toast(reason); return }
        addReferencePatch(reference!!)
    }

    private fun captureGuard(current: Reference?, patches: List<ScenePatch>): String? = when {
        current == null -> "No live outlines yet — hold still"
        SystemClock.elapsedRealtimeNanos() - current.acquiredNs !in 0..1_000_000_000 -> "Reference is stale — hold still a moment"
        !current.stable || !sensors.stable() -> "Hold still until orientation is available"
        !current.lit || current.lines.size < 8 -> "More light and visible outlines needed"
        patches.size >= 24 -> "24 views captured; finish this scene"
        patches.any { it.forward.dot(current.pose.forward) > 0.978 } -> "Turn toward a new view from the same position"
        else -> null
    }

    private fun addReferencePatch(current: Reference) {
        val patches = draft ?: return
        patches += SceneEdges.patch(current.lines, current.pose, current.lens)
        redraw()
        refreshPanel()
    }

    /** The automatic part: while capturing, the coach grabs each view itself and offers the save. */
    private fun maybeAutoCapture() {
        if (!autoCapture) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoPatchMs < 2000) return
        val verdict = SceneMemoryCoach.advise(coachSnap() ?: return)
        when {
            verdict.captureComplete -> if (draft?.isNotEmpty() == true) { lastAutoPatchMs = now; finish() }
            verdict.readyForCapture -> {
                lastAutoPatchMs = now
                val patches = draft ?: return
                if (captureGuard(reference, patches) == null) addReferencePatch(reference!!)
            }
        }
    }

    private fun coachSnap(): SceneMemoryCoach.Snap? {
        val patches = draft ?: return null
        val current = reference
        return SceneMemoryCoach.Snap(
            capturing = true,
            poseAvailable = info?.let { sensors.pose(display, it.frontFacing) } != null,
            stable = current?.stable == true && sensors.stable(),
            lit = current?.lit == true,
            outlines = current?.lines?.size ?: 0,
            views = patches.size,
            freshReference = current != null && SystemClock.elapsedRealtimeNanos() - current.acquiredNs in 0..1_000_000_000,
            repeatedAim = current != null && patches.any { it.forward.dot(current.pose.forward) > 0.978 }
        )
    }

    private fun coachMessage(): String {
        val snap = coachSnap() ?: return ""
        return SceneMemoryCoach.advise(snap).message
    }

    private fun finish() {
        val patches = draft?.toList() ?: return
        if (patches.isEmpty()) { toast("Add a lit view first"); return }
        val name = EditText(activity).apply { hint = "Scene name"; setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(64)) }
        val dialog = AlertDialog.Builder(activity).setTitle("Save scene").setView(name).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = name.text.toString().trim()
            if (label.isEmpty()) { name.error = "Enter a name"; return@setOnClickListener }
            val scene = SavedScene(UUID.randomUUID().toString(), label, System.currentTimeMillis(), draftPlace, patches)
            saving = true
            dialog.dismiss()
            refreshPanel()
            work.execute {
                val result = runCatching { store.save(scene) }
                main.post {
                    saving = false
                    if (closed) return@post
                    if (result.isSuccess) { draft = null; active = scene; toast("Scene saved locally"); reload() }
                    else toast("Save failed: ${result.exceptionOrNull()?.message}")
                    redraw()
                    refreshPanel()
                }
            }
        } }
        dialog.show()
    }

    private fun reload() {
        if (loading || closed) return
        loading = true
        work.execute {
            val result = runCatching { store.list() }
            main.post {
                loading = false
                if (closed) return@post
                result.onSuccess { inventory ->
                    val changed = scenes != inventory.scenes || unreadable != inventory.unreadable
                    scenes = inventory.scenes
                    unreadable = inventory.unreadable
                    if (changed) refreshPanel()
                }.onFailure { toast("Could not read saved scenes") }
            }
        }
    }

    private fun remove(scene: SavedScene) {
        work.execute {
            val result = runCatching { store.delete(scene.id) }
            main.post {
                if (closed) return@post
                if (result.isSuccess) { if (active?.id == scene.id) active = null; reload(); redraw() }
                else toast("Scene could not be removed")
            }
        }
    }

    private fun redraw() {
        val remembered = active
        val projection = lens
        val pose = info?.let { sensors.pose(display, it.frontFacing) }
        if (!resumed) {
            overlay.show(emptyList(), null, "", settings.vr, color)
            return
        }
        if (pose == null || projection == null) {
            overlay.show(emptyList(), null, if (remembered != null || draft != null) "Orientation unavailable — hold the phone up" else "", settings.vr, color)
            return
        }
        val lines = remembered?.let { remembered -> SceneEdges.projected(remembered.patches, pose, projection) } ?: emptyList()
        val matches = remembered != null && reference?.takeIf { SystemClock.elapsedRealtimeNanos() - it.acquiredNs in 0..1_000_000_000 }
            ?.takeIf { it.lines.size >= 8 }
            ?.let { frame ->
                val live = SceneEdges.projected(listOf(SceneEdges.patch(frame.lines, frame.pose, frame.lens)), pose, projection)
                SceneEdges.resemblance(lines, live)
            } == true
        val label = when {
            draft != null -> "Memory · ${coachMessage().ifEmpty { "hold steady" }}"
            remembered != null -> if (matches) "Reference resembles view · position unverified" else "Saved outlines · approximate · not live"
            else -> ""
        }
        val ar = buildList {
            if (horizonOn) addAll(ArHud.horizon(pose, projection))
            for (anchor in anchors) addAll(ArHud.marker(anchor, pose, projection)
                .ifEmpty { listOfNotNull(ArHud.edgeTick(anchor, pose, projection)) })
        }
        val marks = anchors.mapNotNull { anchor ->
            pose.project(anchor.ray, projection)
                ?.takeIf { it.x in 0.0..1.0 && it.y in 0.0..1.0 }
                ?.let { point -> point to anchor.label }
        }
        overlay.show(lines, projection, label, settings.vr, color, ar, marks)
    }

    private fun lens(info: CameraInfo, displayDegrees: Int): ViewLens? {
        val x = info.tanHalfFovX ?: return null
        val y = info.tanHalfFovY ?: return null
        if (!x.isFinite() || !y.isFinite() || x <= 0 || y <= 0) return null
        val aspect = info.width.toDouble() / info.height
        val tanX = min(x, y * aspect)
        val tanY = min(y, x / aspect)
        val rotated = CameraOrientation(info.sensorOrientation, displayDegrees, info.frontFacing).relativeDegrees % 180 != 0
        return if (rotated) ViewLens(tanY, tanX, 1 / aspect) else ViewLens(tanX, tanY, aspect)
    }
    private fun useLocation() = preferences.getBoolean("location", false)
    private fun text(label: String) = TextView(activity).apply {
        text = label; textSize = 12f; setTextColor(Color.rgb(160, 180, 164)); setPadding(0, dp(12), 0, dp(8))
    }
    private fun action(label: String, click: () -> Unit) = Button(activity).apply {
        text = label; textSize = 13f; isAllCaps = false
        setTextColor(Color.rgb(235, 242, 236))
        background = GradientDrawable().apply { setColor(Color.rgb(31, 40, 35)); cornerRadius = dp(12).toFloat() }
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) }
        setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
}
