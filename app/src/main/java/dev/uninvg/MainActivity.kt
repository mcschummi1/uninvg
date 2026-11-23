package dev.uninvg

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.uninvg.camera.CameraEngine
import dev.uninvg.camera.CameraInfo
import dev.uninvg.core.*
import dev.uninvg.memory.SceneMemoryController
import dev.uninvg.memory.SceneOverlay
import dev.uninvg.hud.HudFormat
import dev.uninvg.render.NightRenderer
import dev.uninvg.render.CameraOrientation
import dev.uninvg.update.AppUpdate
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), CameraEngine.Listener, NightRenderer.Listener {
    private val ink = Color.rgb(9, 13, 12)
    private val panel = Color.rgb(19, 26, 23)
    private val accent = Color.rgb(174, 221, 171)
    private val primary = Color.rgb(235, 242, 236)
    private val secondary = Color.rgb(143, 163, 152)
    private val main = Handler(Looper.getMainLooper())
    private val analysis = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean()
    private val controller = AutoController()
    private val captures = LinkedHashMap<Long, CaptureMetadata>()
    private data class PendingSample(val luma: FloatArray, val width: Int, val height: Int, val timestampNs: Long, val epoch: Int)
    private var pendingSample: PendingSample? = null
    private lateinit var camera: CameraEngine
    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: NightRenderer
    private lateinit var memory: SceneMemoryController
    private lateinit var hud: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var memoryOverlay: SceneOverlay
    private lateinit var root: FrameLayout
    private lateinit var gearButton: android.widget.ImageButton
    private lateinit var status: TextView
    private lateinit var sceneLabel: TextView
    private lateinit var placeholder: LinearLayout
    private lateinit var placeholderText: TextView
    private lateinit var permissionButton: Button
    private var lightSwitch: Switch? = null
    private lateinit var panelContent: LinearLayout
    private lateinit var tabRow: LinearLayout
    private var selectedTab = 0
    private var settingsDialog: Dialog? = null
    private var settingsScroll: ScrollView? = null
    private var updateRelease: AppUpdate.ReleaseInfo? = null
    private var updateChecking = false
    private var updateProgress = -1
    private var updateMessage: String? = null
    private var updateStatus: TextView? = null
    private var updateAutoChecked = false
    private var updateCatalog: List<AppUpdate.ReleaseInfo> = emptyList()
    private var catalogLoading = false
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    var updateFetcher: (() -> AppUpdate.ReleaseInfo?)? = null
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    var updateCatalogFetcher: (() -> List<AppUpdate.ReleaseInfo>)? = null
    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }
    private var lastDisplayDegrees = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (resumed && windowManager.defaultDisplay.displayId == displayId) refreshCameraOrientation()
        }
    }
    private var settings = NvSettings()
    private var enhancement = Enhancement()
    private var cameraInfo: CameraInfo? = null
    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var exposureProduct = 10_000_000.0 * 100
    private var measured: CaptureMetadata? = null
    private var scene = SceneKind.DARK
    private var lastOutputTimestamp = 0L
    private var smoothedFps = 0.0
    private var lastStatusUpdate = 0L
    @Volatile private var analysisEpoch = 0
    private var sourceHasFrame = false
    private var lastCameraError: String? = null
    private val preferences by lazy { getSharedPreferences("controls", MODE_PRIVATE) }
    private val identity by lazy {
        DeviceIdentity(Build.MANUFACTURER, Build.MODEL, if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = restoreSettings()
        applyVrOrientation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camera = CameraEngine(this, this)
        buildUi()
        registerThermalStatus()
    }

    /** System thermal status feeds the guardian on modern devices; battery/SoC heat covers the rest. */
    private fun registerThermalStatus() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                runOnUiThread {
                    statusLevel = dev.uninvg.core.ThermalPolicy.levelForStatus(status)
                    lastThermalPollMs = 0
                    refreshThermalGuard()
                }
            }
            pm.addThermalStatusListener(listener)
            thermalStatusListener = listener
        } catch (_: RuntimeException) { }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        gl = object : GLSurfaceView(this) {
            private var tap: Pair<Float, Float>? = null
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    tap = event.x to event.y
                    performClick()
                } else if (event.action == MotionEvent.ACTION_CANCEL) tap = null
                return true
            }
            override fun performClick(): Boolean {
                super.performClick()
                val position = tap ?: (width / 2f to height / 2f)
                focusAt(position.first, position.second)
                tap = null
                return true
            }
        }.apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            contentDescription = "Camera viewfinder. Activate to focus."
        }
        renderer = NightRenderer(gl, this)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        memoryOverlay = SceneOverlay(this)
        root.addView(memoryOverlay, FrameLayout.LayoutParams(-1, -1))
        memory = SceneMemoryController(this, memoryOverlay, { requestSceneLocation() }, {
            if (settingsDialog?.isShowing == true && selectedTab == 4) showTab(4)
        })
        hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sceneLabel = text("LOW-LIGHT OPTICS", 10f, accent).apply {
            letterSpacing = 0.12f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.argb(150, 0, 0, 0), 16)
        }
        hud.addView(sceneLabel, LinearLayout.LayoutParams(-2, -2))
        status = text("Camera idle", 10f, accent).apply {
            typeface = Typeface.MONOSPACE; maxLines = 2
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.argb(150, 0, 0, 0), 16)
        }
        infoText = text("", 10f, accent).apply {
            typeface = Typeface.MONOSPACE; maxLines = 1
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.argb(150, 0, 0, 0), 16)
        }
        hud.addView(infoText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(12); marginStart = dp(12)
        })
        layoutHud()
        gearButton = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_gear)
            contentDescription = "Settings"
            background = rounded(Color.argb(150, 0, 0, 0), 20)
            setOnClickListener { showSettings() }
        }
        root.addView(gearButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); marginEnd = dp(12)
        })
        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        placeholderText = text("Ready when you are", 18f, primary).apply { gravity = Gravity.CENTER }
        placeholder.addView(placeholderText)
        permissionButton = button("Enable camera") { requestCamera() }
        placeholder.addView(permissionButton, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(16) })
        root.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        applyHudColor()
        updateButtons()
    }

    private fun hudColor(): Int = when (settings.palette) {
        Palette.GREEN -> Color.rgb(255, 92, 80)
        Palette.RED -> Color.rgb(126, 232, 130)
        Palette.WHITE -> Color.rgb(126, 232, 130)
        Palette.ICE -> Color.rgb(255, 164, 64)
        Palette.NATURAL -> Color.rgb(126, 232, 130)
    }

    private fun applyHudColor() {
        val color = hudColor()
        sceneLabel.setTextColor(color)
        status.setTextColor(color)
        infoText.setTextColor(color)
        gearButton.setColorFilter(if (updateReady() != null) accent else color)
        memory.update(settings, color)
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun showSettings() {
        if (settingsDialog?.isShowing == true) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val header = row()
        header.addView(text("Settings", 20f, primary), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(button("Done") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(76), dp(48)))
        sheet.addView(header)
        tabRow = row()
        listOf("Image", "Capture", "Color", "Display", "Memory", "Device", "Update").forEachIndexed { index, title ->
            tabRow.addView(button(title) { showTab(index) }, LinearLayout.LayoutParams(-2, dp(44)))
        }
        sheet.addView(HorizontalScrollView(this).apply { addView(tabRow); isHorizontalScrollBarEnabled = false },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        panelContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsScroll = ScrollView(this).apply {
            addView(panelContent)
            isVerticalScrollBarEnabled = true
        }
        sheet.addView(settingsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(sheet)
        dialog.setOnDismissListener {
            memory.panelClosed()
            settingsDialog = null
            settingsScroll = null
            lightSwitch = null
            hideSystemBars()
        }
        settingsDialog = dialog
        showTab(selectedTab)
        dialog.show()
        sizeSettings()
    }

    private fun sizeSettings() {
        val metrics = resources.displayMetrics
        settingsDialog?.window?.apply {
            setBackgroundDrawable(rounded(panel, 22))
            setDimAmount(0.35f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(12) }
            setLayout(min(metrics.widthPixels - dp(24), dp(480)), min((metrics.heightPixels * 0.8f).toInt(), dp(740)))
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun showTab(index: Int) {
        if (selectedTab == 4 && index != 4) memory.panelClosed()
        selectedTab = index
        for (i in 0 until tabRow.childCount) {
            (tabRow.getChildAt(i) as Button).apply {
                isSelected = i == index
                setTextColor(if (i == index) accent else secondary)
                background = rounded(if (i == index) Color.rgb(35, 47, 40) else Color.TRANSPARENT, 12)
            }
        }
        panelContent.removeAllViews()
        when (index) {
            0 -> {
                toggle("Automatic", settings.automatic) {
                    changeSettings(settings.copy(automatic = it))
                    showTab(0)
                }
                slider("Exposure", settings.exposureBias, -2f, 2f, { String.format(Locale.US, "%+.1f EV", it) }) {
                    changeSettings(settings.copy(exposureBias = it))
                }
                slider("Digital gain", settings.digitalGain, 1f, 16f, { String.format(Locale.US, "%.1f×", it) }) {
                    changeSettings(settings.copy(digitalGain = it))
                }
                slider("Gamma", settings.gamma, 0.5f, 2.5f, { String.format(Locale.US, "%.2f", it) }) {
                    changeSettings(settings.copy(gamma = it))
                }
                slider("Denoise", settings.denoise, 0f, 1f, { "${(it * 100).toInt()}%" }) {
                    changeSettings(settings.copy(denoise = it))
                }
                toggle("Regional light · Experimental", settings.regional) { changeSettings(settings.copy(regional = it)) }
            }
            1 -> {
                if (!settings.automatic) {
                    section("MANUAL SENSOR")
                    val caps = cameraInfo?.caps
                    if (caps?.manualSensor == true) {
                        toggle("Assist · decide from the view", settings.assist) { changeSettings(settings.copy(assist = it)) }
                        logSlider("Shutter", settings.manualShutterNs.toDouble(), caps.exposureMinNs.toDouble(),
                            ExposurePlanner.maxExposureNs(caps, settings.fps).toDouble(), { formatShutter(it.toLong()) }) {
                            manualTouched()
                            changeSettings(settings.copy(manualShutterNs = it.toLong()))
                        }
                        logSlider("ISO", settings.manualIso.toDouble(), caps.isoMin.toDouble(), caps.isoMax.toDouble(), { it.toInt().toString() }) {
                            manualTouched()
                            changeSettings(settings.copy(manualIso = it.toInt()))
                        }
                        caps.maxAnalogIso?.let { iso -> panelContent.addView(text("Analog gain ceiling · ISO $iso", 11f, secondary)) }
                        val plan = ExposurePlanner.plan(caps, settings, exposureProduct)
                        if (plan.exposureNs != settings.manualShutterNs || plan.iso != settings.manualIso) {
                            panelContent.addView(text("Applied target · ${formatShutter(plan.exposureNs)} · ISO ${plan.iso}", 11f, secondary))
                        }
                    } else panelContent.addView(text("Manual sensor controls unavailable", 12f, secondary))
                }
                section("TARGET FRAME RATE")
                chips(SUPPORTED_FPS.map { fpsLabel(it) }, SUPPORTED_FPS.indexOf(settings.fps)) { position ->
                    changeSettings(settings.copy(fps = SUPPORTED_FPS[position]))
                    showTab(1)
                }
                section("TEMPORAL DENOISE")
                val limit = cameraInfo?.profile?.maxStackDepth ?: 8
                val depths = listOf(1, 2, 4, 8, 16).filter { it <= limit }
                chips(depths.map { if (it == 1) "Off" else "${it}×" }, depths.indexOf(settings.stackDepth)) { position ->
                    changeSettings(settings.copy(stackDepth = depths[position]))
                }
                section("CAMERA")
                lightSwitch = toggle("Light", settings.torch) { changeSettings(settings.copy(torch = it)) }
                panelContent.addView(button("Camera capabilities") { showDiagnostics() }, LinearLayout.LayoutParams(-1, dp(48)))
            }
            2 -> {
                section("PHOSPHOR")
                chips(Palette.entries.map { it.label }, settings.palette.ordinal) { position ->
                    changeSettings(settings.copy(palette = Palette.entries[position]))
                }
            }
            3 -> {
                toggle("Cardboard split view", settings.vr) { changeSettings(settings.copy(vr = it)) }
                section("RESOLUTION")
                chips(SUPPORTED_RESOLUTIONS.map { if (it == 2160) "4K" else "${it}p" }, SUPPORTED_RESOLUTIONS.indexOf(settings.resolution)) { position ->
                    changeSettings(settings.copy(resolution = SUPPORTED_RESOLUTIONS[position]))
                }
            }
            4 -> panelContent.addView(memory.panel())
            5 -> {
                section("DETECTED DEVICE")
                panelContent.addView(text(cameraInfo?.profile?.label ?: "Universal · camera not ready", 14f, primary))
                panelContent.addView(text(deviceSummary(), 11f, secondary).apply { setPadding(dp(4), dp(8), 0, 0) })
                if (rootAvailable()) {
                    panelContent.addView(text("Root · su available", 11f, accent).apply { setPadding(dp(4), dp(10), 0, 0) })
                }
                section("DEDICATED VERSION")
                val choices = listOf("Auto", "S24 · Exynos", "S22 · Exynos", "J3", "A71", "Universal")
                val ids: List<ProfileId?> = listOf(null, ProfileId.S24_EXYNOS, ProfileId.S22_EXYNOS, ProfileId.J3_2017, ProfileId.A71, ProfileId.UNIVERSAL)
                chips(choices, ids.indexOf(forcedProfile())) { position ->
                    preferences.edit().putInt("profile", ids[position]?.ordinal ?: -1).apply()
                    startSource()
                    showTab(5)
                }
                panelContent.addView(text("Auto follows the phone. A pinned profile keeps its tuning on every boot; exposure always adapts to the scene.", 11f, secondary).apply { setPadding(dp(4), dp(10), 0, 0) })
            }
            6 -> {
                section("APP UPDATE")
                panelContent.addView(text("Installed · v${installedVersion()} · mcschummi1/uninvg", 12f, secondary))
                panelContent.addView(button("Check for updates") { checkForUpdates() },
                    LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
                panelContent.addView(button("Browse versions") { browseVersions() },
                    LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
                if (catalogLoading) panelContent.addView(text("Loading releases…", 11f, secondary))
                updateMessage?.let { message -> panelContent.addView(text(message, 11f, secondary)) }
                updateReady()?.let { release ->
                    if (updateProgress < 0) {
                        val download = button("Download v${release.tag} & install") { downloadAndInstall(release) }
                        download.setTextColor(ink); download.background = rounded(accent, 22)
                        panelContent.addView(download, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
                    }
                }
                if (updateProgress >= 0) {
                    updateStatus = text("Downloading… $updateProgress%", 12f, accent).apply { setPadding(dp(4), dp(10), 0, 0) }
                    panelContent.addView(updateStatus)
                }
            }
        }
        updateButtons()
        settingsScroll?.post { settingsScroll?.scrollTo(0, 0) }
        val tabs = tabRow.parent as HorizontalScrollView
        tabs.post { tabs.scrollTo((tabRow.getChildAt(index).right - tabs.width).coerceAtLeast(0), 0) }
    }

    private fun section(title: String) {
        panelContent.addView(text(title, 10f, secondary).apply {
            letterSpacing = 0.1f; setPadding(dp(4), dp(14), 0, dp(6))
        })
    }

    private fun toggle(title: String, checked: Boolean, update: (Boolean) -> Unit): Switch {
        val toggle = Switch(this).apply {
            text = title; textSize = 13f; setTextColor(primary); isChecked = checked
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnCheckedChangeListener { _, value -> update(value) }
        }
        panelContent.addView(toggle, LinearLayout.LayoutParams(-1, dp(48)))
        return toggle
    }

    private fun slider(title: String, value: Float, low: Float, high: Float, format: (Float) -> String, update: (Float) -> Unit) {
        val labels = row()
        labels.addView(text(title, 12f, primary), LinearLayout.LayoutParams(0, dp(24), 1f))
        val current = text(format(value), 12f, accent).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        labels.addView(current)
        panelContent.addView(labels)
        panelContent.addView(SeekBar(this).apply {
            max = 200
            progress = ((value - low) / (high - low) * 200).toInt()
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, position: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = low + position / 200f * (high - low)
                    current.text = format(next)
                    update(next)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun formatShutter(ns: Long): String = if (ns >= 1_000_000_000L)
        String.format(Locale.US, "%.2f s", ns / 1_000_000_000.0)
    else String.format(Locale.US, "%.2f ms", ns / 1_000_000.0)

    private fun logSlider(title: String, value: Double, minimum: Double, maximum: Double, format: (Double) -> String, update: (Double) -> Unit) {
        val scale = LogScale(minimum, maximum)
        val labels = row()
        labels.addView(text(title, 12f, primary), LinearLayout.LayoutParams(0, dp(24), 1f))
        val current = text(format(value.coerceIn(minimum, maximum)), 12f, accent)
        labels.addView(current)
        panelContent.addView(labels)
        panelContent.addView(SeekBar(this).apply {
            max = scale.steps
            progress = scale.progress(value)
            contentDescription = title
            isEnabled = minimum < maximum
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, position: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = scale.value(position)
                    current.text = format(next)
                    update(next)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun chips(labels: List<String>, selected: Int, select: (Int) -> Unit) {
        val choices = row()
        labels.forEachIndexed { index, label ->
            choices.addView(button(label) {
                select(index)
                for (position in 0 until choices.childCount) {
                    (choices.getChildAt(position) as Button).apply {
                        isSelected = position == index
                        setTextColor(if (isSelected) ink else primary)
                        background = rounded(if (isSelected) accent else Color.rgb(31, 40, 35), 12)
                    }
                }
            }.apply {
                isSelected = index == selected
                setTextColor(if (isSelected) ink else primary)
                background = rounded(if (isSelected) accent else Color.rgb(31, 40, 35), 12)
                minWidth = dp(48)
            }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6) })
        }
        panelContent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(choices) })
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(label: String, size: Float, color: Int) = TextView(this).apply {
        text = label; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }
    private fun button(label: String, click: () -> Unit) = object : Button(this) {
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.4f
        }
    }.apply {
        text = label; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = dp(48)
        setTextColor(primary); background = rounded(Color.rgb(24, 33, 28), 14)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Cardboard split view assumes the goggles hold the phone sideways, so request landscape even when the system rotation lock would stay portrait. */
    internal fun applyVrOrientation() {
        requestedOrientation = if (settings.vr) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun changeSettings(next: NvSettings) {
        val resolutionChanged = next.resolution != settings.resolution
        val reanalyze = next.automatic != settings.automatic || next.exposureBias != settings.exposureBias || next.regional != settings.regional || next.digitalGain != settings.digitalGain
        val vrChanged = next.vr != settings.vr
        settings = next
        if (vrChanged) applyVrOrientation()
        if (reanalyze) analysis.execute { controller.reset() }
        renderer.update(settings, enhancement)
        if (resolutionChanged) startSource()
        else {
            val product = if (settings.automatic) exposureProduct else manualExposure()
            camera.update(settings, product)
        }
        preferences.edit().putFloat("fps", settings.fps).putBoolean("automatic", settings.automatic)
            .putFloat("bias", settings.exposureBias).putFloat("gain", settings.digitalGain).putFloat("gamma", settings.gamma)
            .putFloat("denoise", settings.denoise).putInt("stack", settings.stackDepth).putInt("palette", settings.palette.ordinal)
            .putBoolean("regional", settings.regional).putInt("resolution", settings.resolution).putBoolean("vr", settings.vr)
            .putInt("manualIso", settings.manualIso).putLong("manualShutterNs", settings.manualShutterNs)
            .putBoolean("assist", settings.assist).apply()
        applyHudColor()
        updateStatus()
        updateButtons()
    }

    private fun applyAssist(suggestion: ManualAssist.Suggestion) {
        val now = clock()
        if (now - lastManualTouchMs < 3000 || now - lastAssistApplyMs < 1000) return
        if (suggestion.shutterNs == settings.manualShutterNs && suggestion.iso == settings.manualIso) return
        lastAssistApplyMs = now
        changeSettings(settings.copy(manualShutterNs = suggestion.shutterNs, manualIso = suggestion.iso))
        if (settingsDialog?.isShowing == true && selectedTab == 1) showTab(1)
    }

    private fun manualExposure(): Double = settings.manualShutterNs.toDouble() * settings.manualIso

    private fun deviceSummary(): String {
        val info = cameraInfo ?: return "${identity.manufacturer} ${identity.model}\nCamera has not been opened yet."
        val caps = info.caps
        return buildString {
            appendLine("${identity.manufacturer} ${identity.model} · stream ${info.width}×${info.height}")
            cscProbe()?.let { appendLine("CSC · $it · ${cscRegion(it)}") }
            appendLine(if (caps.manualSensor) "Manual sensor · ISO ${caps.isoMin}–${caps.isoMax}" else "No manual sensor · auto-exposure camera")
            appendLine("Auto gain ceiling · ${info.profile.gainCeiling.toInt()}× · Denoise tune · ×${info.profile.denoiseBias} · Detail · ×${info.profile.sharpenBias}")
            appendLine("Temporal stack limit · ${info.profile.maxStackDepth}× · OIS preferred · ${info.profile.preferOis}")
            if (thermalLevel > 0) appendLine("Thermal · ${if (thermalLevel == dev.uninvg.core.ThermalPolicy.HOT) "hot" else "warm"} · render pacing reduced")
        }.trimEnd()
    }

    private val rootPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sd/xbin/su", "/vendor/bin/su")
    private fun rootAvailable(): Boolean = rootPaths.any { runCatching { java.io.File(it).exists() }.getOrDefault(false) }

    private var lastManualTouchMs = 0L
    private var lastAssistApplyMs = 0L
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    private fun manualTouched() { lastManualTouchMs = clock() }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var thermalLevel = 0
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var socProbe: () -> Int? = { socTemperatureTenths() }
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var cscProbe: () -> String? = { cscSalesCode() }
    private var statusLevel = 0
    private var lastThermalPollMs = 0L
    private var lastBatteryTempTenths: Int? = null
    private var thermalStatusListener: Any? = null

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun forcedProfile(): ProfileId? =
        try {
            preferences.getInt("profile", -1).takeIf { it >= 0 }?.let { ProfileId.entries[it] }
        } catch (_: RuntimeException) { null }

    private fun restoreSettings(): NvSettings = try {
        NvSettings(fps = try { preferences.getFloat("fps", 15f) } catch (_: ClassCastException) { preferences.getInt("fps", 15).toFloat() },
            resolution = preferences.getInt("resolution", 720), vr = preferences.getBoolean("vr", false),
            automatic = preferences.getBoolean("automatic", true), exposureBias = preferences.getFloat("bias", 0f),
            digitalGain = preferences.getFloat("gain", 1f), gamma = preferences.getFloat("gamma", 1f),
            denoise = preferences.getFloat("denoise", 0.45f), stackDepth = preferences.getInt("stack", 4),
            palette = Palette.entries[preferences.getInt("palette", 1)], regional = preferences.getBoolean("regional", false),
            manualIso = preferences.getInt("manualIso", 800), manualShutterNs = preferences.getLong("manualShutterNs", 100_000_000),
            assist = preferences.getBoolean("assist", true))
    } catch (error: RuntimeException) { NvSettings() }

    override fun onResume() {
        super.onResume()
        resumed = true
        gl.onResume()
        memory.resume()
        hideSystemBars()
        displayManager.registerDisplayListener(displayListener, main)
        maybeCheckForUpdate()
        startSource()
        refreshCameraOrientation()
    }

    override fun onPause() {
        resumed = false
        memory.pause()
        displayManager.unregisterDisplayListener(displayListener)
        camera.stop()
        invalidateAnalysis()
        gl.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        settingsDialog?.dismiss()
        (thermalStatusListener as? PowerManager.OnThermalStatusChangedListener)?.let { listener ->
            runCatching { (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(listener) }
        }
        camera.release()
        memory.release()
        analysis.shutdownNow()
        io.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        sizeSettings()
        refreshCameraOrientation()
    }

    override fun onSurface(texture: SurfaceTexture) {
        if (isFinishing || isDestroyed) return
        this.texture = texture
        startSource()
    }

    private fun startSource() {
        val surface = texture ?: return
        if (!resumed) return
        invalidateAnalysis()
        memory.camera(null, displayDegrees())
        sourceHasFrame = false
        renderer.resetHistory()
        renderer.update(settings, enhancement)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showPlaceholder("Opening camera…", false)
            camera.start(surface, identity, settings, forcedProfile())
        } else showPlaceholder("See further into the dark", true)
        updateButtons()
    }

    private fun invalidateAnalysis() {
        analysisEpoch++
        captures.clear()
        pendingSample = null
        measured = null
        smoothedFps = 0.0
        lastOutputTimestamp = 0
        lastStatusUpdate = 0
        enhancement = Enhancement()
        analysis.execute { controller.reset() }
    }

    private fun requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { startSource(); return }
        if (preferences.getBoolean("permissionAsked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            preferences.edit().putBoolean("permissionAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    private fun requestSceneLocation() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (requestCode == LOCATION_PERMISSION) memory.locationPermissionChanged()
        if (requestCode == CAMERA_PERMISSION) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSource()
            else showPlaceholder("Camera access is off", true)
        }
    }

    override fun onReady(info: CameraInfo) {
        if (!resumed) return
        cameraInfo = info
        lastCameraError = null
        configureCameraSource(info)
        renderer.setProfile(info.profile.maxStackDepth, info.profile.motionThreshold)
        renderer.setDenoiseBias(info.profile.denoiseBias)
        renderer.setDetailBias(info.profile.sharpenBias)
        if (settings.stackDepth > info.profile.maxStackDepth) settings = settings.copy(stackDepth = info.profile.maxStackDepth)
        exposureProduct = 10_000_000L.coerceIn(info.caps.exposureMinNs, info.caps.exposureMaxNs).toDouble() * info.caps.isoMin
        camera.update(settings, if (settings.automatic) exposureProduct else manualExposure())
        updateButtons()
        if (settingsDialog?.isShowing == true) showTab(selectedTab)
    }

    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun cameraOrientation(info: CameraInfo) = CameraOrientation(info.sensorOrientation, displayDegrees(), info.frontFacing)

    private fun configureCameraSource(info: CameraInfo) {
        val orientation = cameraOrientation(info)
        lastDisplayDegrees = orientation.displayDegrees
        renderer.configureCamera(info.width, info.height, orientation)
        memory.camera(info, orientation.displayDegrees)
    }

    internal fun refreshCameraOrientation() {
        layoutHud()
        if (displayDegrees() == lastDisplayDegrees) return
        cameraInfo?.let {
            invalidateAnalysis()
            configureCameraSource(it)
            renderer.update(settings, enhancement)
        }
    }

    private fun layoutHud() {
        val landscape = displayDegrees() == 90 || displayDegrees() == 270
        val params = hud.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (landscape) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
        params.topMargin = if (landscape) dp(62) else dp(12)
        params.marginStart = if (landscape) 0 else dp(12)
        params.marginEnd = if (landscape) dp(12) else 0
        hud.layoutParams = params
    }

    override fun onCapture(metadata: CaptureMetadata) {
        if (!resumed) return
        captures[metadata.timestampNs] = metadata
        while (captures.size > 120) captures.remove(captures.keys.first())
        measured = metadata
        pendingSample?.takeIf { it.timestampNs == metadata.timestampNs && it.epoch == analysisEpoch }?.let {
            pendingSample = null
            onSample(it.luma, it.width, it.height, it.timestampNs)
        }
    }

    override fun onSample(luma: FloatArray, width: Int, height: Int, timestampNs: Long) {
        val sampleEpoch = analysisEpoch
        main.post {
            if (!resumed || sampleEpoch != analysisEpoch) return@post
            memory.reference(luma, width, height)
            if (cameraInfo?.caps?.manualSensor == true && captures[timestampNs] == null) {
                pendingSample = PendingSample(luma, width, height, timestampNs, sampleEpoch)
                return@post
            }
            if (!analyzing.compareAndSet(false, true)) return@post
            val epoch = analysisEpoch
            val capturedSettings = settings
            val info = cameraInfo
            val metadata = captures[timestampNs]
            val plan = if (info == null) CapturePlan(false, 1, 100, frameBudgetNs(settings.fps), null, settings.fps, false, true)
                else ExposurePlanner.plan(info.caps, settings, exposureProduct)
            val gainCeiling = info?.profile?.gainCeiling ?: 16f
            analysis.execute {
                try {
                    val stats = SceneAnalyzer.analyze(luma, width, height)
                    val decision = controller.update(stats, metadata, plan, capturedSettings, timestampNs, false, gainCeiling)
                    val assistCaps = info?.caps?.takeIf { it.manualSensor }
                    val assist = if (!capturedSettings.automatic && capturedSettings.assist && assistCaps != null)
                        ManualAssist.suggest(stats, assistCaps, capturedSettings, capturedSettings.exposureBias) else null
                    main.post {
                        if (epoch != analysisEpoch || !resumed) return@post
                        scene = stats.kind
                        enhancement = decision.enhancement
                        assist?.let { applyAssist(it) }
                        if (info != null && settings.automatic && metadata != null) {
                            val changed = abs(kotlin.math.ln(decision.exposureProduct / exposureProduct)) > 0.06
                            exposureProduct = decision.exposureProduct
                            if (changed) camera.update(settings, exposureProduct)
                        }
                        renderer.update(settings, enhancement)
                        updateStatus()
                    }
                } catch (error: RuntimeException) {
                    android.util.Log.e("UniNVG.Analysis", "operation=analyze", error)
                    main.post { if (epoch == analysisEpoch) toast("Scene analysis unavailable") }
                } finally { analyzing.set(false) }
            }
        }
    }

    override fun onOutput(timestampNs: Long) {
        if (!resumed) return
        frameReady()
        if (lastOutputTimestamp > 0 && timestampNs > lastOutputTimestamp) {
            val fps = 1_000_000_000.0 / (timestampNs - lastOutputTimestamp)
            smoothedFps = if (smoothedFps == 0.0) fps else smoothedFps * 0.8 + fps * 0.2
        }
        lastOutputTimestamp = timestampNs
        if (timestampNs - lastStatusUpdate >= 500_000_000 || lastStatusUpdate == 0L) {
            lastStatusUpdate = timestampNs
            updateStatus()
        }
    }

    private fun frameReady() {
        sourceHasFrame = true
        placeholder.visibility = View.GONE
    }

    /** The thermal guardian: battery heat from 40 °C, SoC heat (CPU zone, GPU fallback) from 60 °C. Pacing only — exposure never. */
    internal fun refreshThermalGuard(force: Boolean = false) {
        if (!force && clock() - lastThermalPollMs < 5_000) return
        lastThermalPollMs = clock()
        io.execute {
            val soc = runCatching { socProbe() }.getOrNull()
            main.post {
                if (!resumed) return@post
                val level = dev.uninvg.core.ThermalPolicy.effective(statusLevel, lastBatteryTempTenths, soc)
                if (level == thermalLevel) return@post
                thermalLevel = level
                renderer.setThermalLoad(level)
                if (settingsDialog?.isShowing == true && selectedTab == 5) showTab(5)
            }
        }
    }

    /** Highest CPU thermal-zone reading (millidegrees→tenths); GPU zones only when no CPU zone answers. */
    internal fun socTemperatureTenths(): Int? {
        val zones = java.io.File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") } ?: return null
        var cpu: Int? = null
        var gpu: Int? = null
        for (zone in zones) {
            val type = runCatching { java.io.File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: continue
            val milli = runCatching { java.io.File(zone, "temp").readText().trim().toInt() }.getOrNull() ?: continue
            val tenths = milli / 100
            if (tenths !in 100..1_200) continue
            if (type.contains("cpu")) { if (tenths > (cpu ?: Int.MIN_VALUE)) cpu = tenths }
            else if (type.contains("gpu")) { if (tenths > (gpu ?: Int.MIN_VALUE)) gpu = tenths }
        }
        return cpu ?: gpu
    }

    /** Samsung region firmware code (ILO = Israel) from the sales-code system properties. */
    internal fun cscSalesCode(): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        listOf("ro.omc.sales_code", "ro.csc.sales_code", "persist.omc.sales_code")
            .map { (get.invoke(null, it) as String).trim().uppercase(java.util.Locale.ROOT) }
            .firstOrNull { it.length in 2..4 && !it.contains("unknown", ignoreCase = true) }
    } catch (_: Exception) { null }

    private fun cscRegion(code: String): String = mapOf(
        "ILO" to "Israel", "INS" to "India", "DBT" to "Germany", "BTU" to "United Kingdom",
        "XAA" to "USA (unlocked)", "XSA" to "Australia", "KOO" to "South Korea",
        "TGY" to "Hong Kong", "ZTO" to "Brazil", "XME" to "Malaysia"
    ).getOrDefault(code, "region $code")

    private fun updateStatus() {
        val fps = if (smoothedFps > 0) String.format(Locale.US, "%.1f", smoothedFps) else "—"
        val iso = measured?.iso?.toString() ?: "—"
        val exposure = measured?.exposureNs?.let { String.format(Locale.US, "%.1f ms", it / 1_000_000.0) } ?: "—"
        status.text = "$fps fps / ${fpsLabel(settings.fps)} target   ·   ISO $iso   ·   $exposure"
        sceneLabel.text = (if (settings.automatic) scene.label else "Manual") + if (settings.regional) " · REGIONAL" else ""
        refreshDeviceInfo()
        refreshThermalGuard()
    }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun refreshDeviceInfo() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        lastBatteryTempTenths = temperature.takeIf { it != Int.MIN_VALUE }
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val line = HudFormat.batteryLine(percent, if (temperature != Int.MIN_VALUE) temperature else null)
        if (line == null) {
            infoText.visibility = View.GONE
        } else {
            infoText.visibility = View.VISIBLE
            infoText.text = line
        }
    }

    override fun onError(message: String) {
        if (!resumed) return
        sourceHasFrame = false
        lastCameraError = message
        memory.camera(null, displayDegrees())
        showPlaceholder("Camera unavailable", true)
        permissionButton.text = "Retry camera"
        status.text = "Camera unavailable"
    }

    override fun onRenderError(message: String) {
        toast(message)
        if (!sourceHasFrame) showPlaceholder(message, false)
    }

    private fun showPlaceholder(message: String, action: Boolean) {
        placeholder.visibility = View.VISIBLE
        placeholderText.text = message
        permissionButton.text = "Enable camera"
        permissionButton.visibility = if (action) View.VISIBLE else View.GONE
    }

    private fun updateButtons() {
        lightSwitch?.apply {
            isEnabled = cameraInfo?.hasFlash == true
            alpha = if (isEnabled) 1f else 0.4f
            if (isChecked != settings.torch) isChecked = settings.torch
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val info = cameraInfo ?: return
        val orientation = cameraOrientation(info)
        val (width, height) = orientation.outputSize(info.width, info.height)
        val scale = max(gl.width.toFloat() / width, gl.height.toFloat() / height)
        val u = (x - (gl.width - width * scale) / 2) / (width * scale)
        val v = (y - (gl.height - height * scale) / 2) / (height * scale)
        val point = orientation.sensorPoint(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        camera.focus(point.first, point.second)
    }

    private fun showDiagnostics() {
        val details = listOfNotNull(cameraInfo?.diagnostics ?: "${identity.manufacturer} ${identity.model}\nCamera has not been opened.", lastCameraError).joinToString("\n\n")
        val content = text(details, 12f, primary).apply {
            typeface = Typeface.MONOSPACE; setPadding(dp(20), dp(12), dp(20), dp(12)); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("Camera capabilities").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null).setNeutralButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UniNVG capabilities", details))
                toast("Copied")
            }.show()
    }

    private fun installedVersion() = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (error: PackageManager.NameNotFoundException) { "0" }

    private fun updateReady(): AppUpdate.ReleaseInfo? {
        val release = updateRelease ?: return null
        return if (AppUpdate.isNewer(installedVersion(), release.tag)) release else null
    }

    private fun refreshUpdatePanel() {
        gearButton.setColorFilter(if (updateReady() != null) accent else hudColor())
        if (settingsDialog?.isShowing == true && selectedTab == 6) showTab(6)
    }

    private fun maybeCheckForUpdate() {
        if (updateAutoChecked || updateChecking || updateProgress >= 0) return
        updateAutoChecked = true
        main.postDelayed({ if (resumed && !isFinishing && !isDestroyed) autoCheckForUpdate() }, 1500)
    }

    private fun autoCheckForUpdate() {
        if (updateChecking || updateProgress >= 0) return
        io.execute {
            val release = try { fetchUpdate() } catch (error: Exception) { null }
            if (release == null || !AppUpdate.isNewer(installedVersion(), release.tag)) return@execute
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateMessage = "Update v${release.tag} available"
                updateRelease = release
                applyHudColor()
                if (settingsDialog?.isShowing == true && selectedTab == 6) showTab(6)
                toast("UniNVG v${release.tag} is out — open Settings ▸ Update")
            }
        }
    }

    private fun checkForUpdates() {
        if (updateChecking || updateProgress >= 0) return
        updateChecking = true
        updateMessage = "Checking…"
        refreshUpdatePanel()
        io.execute {
            val release = try { fetchUpdate() } catch (error: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateChecking = false
                updateRelease = release
                updateMessage = when {
                    release == null -> "Update check failed — check your connection and try again."
                    AppUpdate.isNewer(installedVersion(), release.tag) -> "Update v${release.tag} is available"
                    else -> "Up to date — v${release.tag} is the latest release"
                }
                refreshUpdatePanel()
            }
        }
    }

    private fun downloadAndInstall(release: AppUpdate.ReleaseInfo) {
        if (updateChecking || updateProgress >= 0) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow UniNVG to install apps, then tap download again")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateProgress = 0
        refreshUpdatePanel()
        val destination = File(cacheDir, "update/UniNVG-${release.tag}.apk")
        io.execute {
            try {
                AppUpdate.download(release.apkUrl, destination) { percent ->
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        updateProgress = percent
                        updateStatus?.text = "Downloading… $percent%"
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateProgress = -1
                    installApk(destination)
                }
            } catch (error: IOException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    updateProgress = -1
                    updateMessage = "Download failed — check your connection and try again."
                    refreshUpdatePanel()
                    toast("Download failed")
                }
            }
        }
    }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    fun debugUpdateState(): String =
        "checking=$updateChecking progress=$updateProgress release=${updateRelease?.tag} message=$updateMessage"

    private fun browseVersions() {
        if (catalogLoading) return
        val known = updateCatalog
        if (known.isNotEmpty()) {
            showVersionPicker(known)
            return
        }
        catalogLoading = true
        refreshUpdatePanel()
        io.execute {
            val releases = try { fetchCatalog() } catch (error: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                catalogLoading = false
                if (releases.isNotEmpty()) updateCatalog = releases
                refreshUpdatePanel()
                if (releases.isEmpty()) toast("Could not load releases — check your connection")
                else showVersionPicker(releases)
            }
        }
    }

    private fun showVersionPicker(releases: List<AppUpdate.ReleaseInfo>) {
        val installed = installedVersion()
        val labels = releases.map { release ->
            val marker = when {
                AppUpdate.isNewer(installed, release.tag) -> " · newer"
                AppUpdate.isNewer(release.tag, installed) -> " · older"
                else -> " · installed"
            }
            "v${release.tag}$marker"
        }
        AlertDialog.Builder(this).setTitle("Install a version")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = releases[which]
                if (!AppUpdate.isNewer(chosen.tag, installed)) downloadAndInstall(chosen)
                else toast("Android blocks downgrades — uninstall UniNVG first to go back to v${chosen.tag}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchUpdate(): AppUpdate.ReleaseInfo? =
        (updateFetcher ?: { AppUpdate.fetchLatest() })()

    private fun fetchCatalog(): List<AppUpdate.ReleaseInfo> =
        (updateCatalogFetcher ?: { AppUpdate.fetchCatalog() })()

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "dev.uninvg.fileprovider", file)
        startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        toast("Opening installer — choose Update to finish")
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val CAMERA_PERMISSION = 10
        private const val LOCATION_PERMISSION = 13
    }
}
