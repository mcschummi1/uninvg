package dev.uninvg

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.os.Looper
import dev.uninvg.update.AppUpdate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [MainActivityTest.QuietGlView::class])
class MainActivityTest {
    private lateinit var lifecycle: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before fun open() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("controls", 0).edit().clear().commit()
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity = lifecycle.get()
        activity.updateFetcher = { null }
        activity.updateCatalogFetcher = { emptyList() }
    }

    @After fun close() { if (::lifecycle.isInitialized) lifecycle.pause().stop().destroy() }

    @Test fun fullScreenViewfinderIsTheBackgroundSurface() {
        val preview = views().filterIsInstance<GLSurfaceView>().single()
        val params = preview.layoutParams
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(activity.window.decorView.width, preview.rootView.width)
        assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
    }

    @Test fun onlySmallGearOpensSettingsFromMainHud() {
        val gear = gear()
        assertEquals("Settings", gear.contentDescription)
        assertEquals(40, (gear.layoutParams.width / activity.resources.displayMetrics.density).toInt())
        assertTrue(views().filterIsInstance<Button>().none { it.text == "Settings" })
        gear.performClick()
        assertTrue(dialog().isShowing)
    }

    @Test fun settingsAreGroupedIntoFourTabs() {
        gear().performClick()
        listOf("Image", "Capture", "Color", "Display").forEach { assertNotNull(button(it)) }
        assertEquals(4, listOf("Image", "Capture", "Color", "Display").count { button(it).isShown })
        assertEquals(4, views().filterIsInstance<SeekBar>().size)
        assertEquals(2, views().filterIsInstance<Switch>().size)
        button("Capture").performClick()
        assertTrue(views().filterIsInstance<SeekBar>().isEmpty())
        assertNotNull(button("120"))
        button("Display").performClick()
        assertEquals(1, views().filterIsInstance<Switch>().count { it.text == "Cardboard split view" })
    }

    @Test fun everyRequestedFrameRatePersistsIncludingFractionalCadence() {
        gear().performClick(); button("Capture").performClick()
        val labels = listOf("120", "90", "60", "40", "30", "25", "24", "15", "10", "5", "1", "1/2", "1/4")
        val values = listOf(120f, 90f, 60f, 40f, 30f, 25f, 24f, 15f, 10f, 5f, 1f, 0.5f, 0.25f)
        labels.forEachIndexed { index, label ->
            button(label).performClick()
            assertEquals(values[index], preferences().getFloat("fps", 0f), 0f)
            assertEquals(1, labels.count { button(it).isSelected })
        }
    }

    @Test fun everyResolutionTargetPersists() {
        gear().performClick(); button("Display").performClick()
        val labels = listOf("360p", "480p", "720p", "1080p", "1440p", "4K")
        val values = listOf(360, 480, 720, 1080, 1440, 2160)
        labels.forEachIndexed { index, label ->
            button(label).performClick()
            assertEquals(values[index], preferences().getInt("resolution", 0))
            assertEquals(1, labels.count { button(it).isSelected })
        }
    }

    @Test fun cardboardSplitCanBeEnabledAndPersists() {
        gear().performClick(); button("Display").performClick()
        val toggle = views().filterIsInstance<Switch>().single { it.text == "Cardboard split view" }
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertTrue(preferences().getBoolean("vr", false))
    }

    @Test fun hudContrastTracksEveryPhosphorChoice() {
        val hud = views().filterIsInstance<TextView>().single { it.text == "LOW-LIGHT OPTICS" }
        gear().performClick(); button("Color").performClick()
        val expected = mapOf(
            "Natural" to Color.rgb(126, 232, 130), "Green" to Color.rgb(255, 92, 80),
            "White" to Color.rgb(126, 232, 130), "Red" to Color.rgb(126, 232, 130),
            "Ice" to Color.rgb(255, 164, 64)
        )
        expected.forEach { (label, color) -> button(label).performClick(); assertEquals(color, hud.currentTextColor) }
    }

    @Test fun allFivePalettesPersistAndRemainSingleSelection() {
        gear().performClick(); button("Color").performClick()
        val options = listOf("Natural", "Green", "White", "Red", "Ice")
        options.forEachIndexed { index, name ->
            button(name).performClick()
            assertEquals(index, preferences().getInt("palette", -1))
            assertEquals(1, options.count { button(it).isSelected })
        }
    }

    @Test fun cameraPermissionAndUnavailableTorchRemainActionable() {
        assertNotNull(button("Enable camera"))
        gear().performClick(); button("Capture").performClick()
        assertFalse(views().filterIsInstance<Switch>().single { it.text == "Light" }.isEnabled)
    }

    @Test fun viewfinderExposesOnlyTheGearInsteadOfMediaActions() {
        val labels = views().filterIsInstance<Button>().map { it.text.toString() }
        assertTrue("Import" !in labels)
        assertTrue("Save frame" !in labels)
        assertTrue(views().filterIsInstance<ImageButton>().any { it.contentDescription == "Settings" })
    }

    @Test fun hudShowsBatteryAndTemperatureReadout() {
        activity.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 87)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_TEMPERATURE, 314)
        })
        activity.refreshDeviceInfo()
        val info = views().filterIsInstance<TextView>().single { it.text.toString().startsWith("Battery") }
        assertEquals("Battery 87%  \u00b7  Temp 31.4\u00b0C", info.text.toString())
    }

    @Test fun hudRelocatesToTheRightEdgeWhenHeldLandscape() {
        val hud = views().filterIsInstance<TextView>().single { it.text == "LOW-LIGHT OPTICS" }.parent as LinearLayout
        assertEquals(Gravity.TOP or Gravity.START, (hud.layoutParams as FrameLayout.LayoutParams).gravity)
        shadowOf(activity.windowManager.defaultDisplay).setRotation(Surface.ROTATION_90)
        activity.refreshCameraOrientation()
        assertTrue((hud.layoutParams as FrameLayout.LayoutParams).gravity and Gravity.END != 0)
    }

    @Test fun closingAndReopeningPreservesSelectedTabAndValues() {
        gear().performClick(); button("Display").performClick(); button("1080p").performClick()
        val first = dialog(); button("Done").performClick(); assertFalse(first.isShowing)
        gear().performClick()
        assertTrue(button("Display").isSelected)
        assertTrue(button("1080p").isSelected)
    }

    @Test fun corruptSavedControlsResetToSafeDefaults() {
        preferences().edit().putFloat("gain", Float.NaN).commit()
        lifecycle.pause().stop().destroy()
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity = lifecycle.get()
        gear().performClick(); button("Capture").performClick()
        assertTrue(button("15").isSelected)
    }

    @Test fun onlyUpdaterPermissionsWereAddedOnTopOfCameraAndLocation() {
        val info = activity.packageManager.getPackageInfo(activity.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertEquals(setOf(android.Manifest.permission.INTERNET, android.Manifest.permission.REQUEST_INSTALL_PACKAGES,
            "${activity.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            requested - setOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test fun updateTabChecksGitHubAndOffersTheNewerReleaseDownload() {
        activity.updateFetcher = { AppUpdate.ReleaseInfo("9.9.9", "https://github.com/mcschummi1/uninvg/releases/download/v9.9.9/UniNVG-v9.9.9.apk") }
        gear().performClick()
        button("Update").performClick()
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains("Installed · v") })
        assertFalse(views().filterIsInstance<TextView>().any { it.text.toString().contains("v9.9.9") })
        button("Check for updates").performClick()
        eventually { views().filterIsInstance<TextView>().any { it.text.toString().contains("Update v9.9.9 is available") } }
        assertNotNull(button("Download v9.9.9 & install"))
    }

    @Test fun versionPickerLetsTheUserBrowseAndPickEarlierReleases() {
        activity.updateCatalogFetcher = { listOf(
            AppUpdate.ReleaseInfo("0.4.5.5", "https://example.com/UniNVG-0.4.5.5.apk"),
            AppUpdate.ReleaseInfo("0.3.0", "https://example.com/UniNVG-0.3.0.apk")) }
        gear().performClick()
        button("Browse versions").performClick()
        val items = views().filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(items.any { it.startsWith("v0.4.5.5") && it.contains("installed") })
        assertTrue(items.any { it.startsWith("v0.3.0") && it.contains("older") })
    }

    @Test fun launchAutoCheckSurfacesANewerReleaseWithoutUserAction() {
        activity.updateFetcher = { AppUpdate.ReleaseInfo("9.9.9", "https://github.com/mcschummi1/uninvg/releases/download/v9.9.9/UniNVG-v9.9.9.apk") }
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        gear().performClick()
        button("Update").performClick()
        eventually { views().filterIsInstance<Button>().any { it.text == "Download v9.9.9 & install" } }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun portraitHudAndTabsRender() {
        layout(activity.window.decorView, 1080, 2400)
        saveScreenshot("portrait", activity.window.decorView)
        gear().performClick(); button("Display").performClick()
        renderSettings("portrait-settings")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun landscapeHudAndTabsRender() {
        layout(activity.window.decorView, 2400, 1080)
        assertTrue(views().filterIsInstance<GLSurfaceView>().single().width > 0)
        saveScreenshot("landscape", activity.window.decorView)
        gear().performClick(); button("Capture").performClick()
        renderSettings("landscape-settings")
    }

    @Test fun manualSlidersAppearOnlyForAUsableLiveSensor() {
        activity.onReady(cameraInfo(true))
        gear().performClick()
        views().filterIsInstance<Switch>().single { it.text == "Automatic" }.performClick()
        button("Capture").performClick()
        assertEquals(setOf("ISO", "Shutter"), views().filterIsInstance<SeekBar>().map { it.contentDescription }.toSet())
        button("Image").performClick()
        views().filterIsInstance<Switch>().single { it.text == "Automatic" }.performClick()
        button("Capture").performClick()
        assertTrue(views().filterIsInstance<SeekBar>().isEmpty())
    }

    @Test fun unsupportedCameraDoesNotOfferManualIsoOrShutter() {
        activity.onReady(cameraInfo(false))
        gear().performClick()
        views().filterIsInstance<Switch>().single { it.text == "Automatic" }.performClick()
        button("Capture").performClick()
        assertTrue(views().filterIsInstance<SeekBar>().isEmpty())
        assertTrue(views().filterIsInstance<TextView>().any { it.text == "Manual sensor controls unavailable" })
    }

    @Test fun storedManualValuesSurviveRecreation() {
        preferences().edit().putBoolean("automatic", false).putInt("manualIso", 1200).putLong("manualShutterNs", 20_000_000).commit()
        lifecycle.pause().stop().destroy()
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity = lifecycle.get()
        activity.onReady(cameraInfo(true))
        gear().performClick(); button("Capture").performClick()
        assertTrue(views().filterIsInstance<TextView>().any { it.text == "1200" })
        assertTrue(views().filterIsInstance<TextView>().any { it.text == "20.00 ms" })
    }

    @Test fun memoryIsAccessibleWithoutRequestingLocationAtStartup() {
        gear().performClick(); button("Memory").performClick()
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains("Scene Memory needs gyro") })
        assertFalse(activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun manualSensorPanelRendersWithCapabilityBounds() {
        layout(activity.window.decorView, 1080, 2400)
        activity.onReady(cameraInfo(true))
        gear().performClick()
        views().filterIsInstance<Switch>().single { it.text == "Automatic" }.performClick()
        button("Capture").performClick()
        renderSettings("manual-sensor-settings")
        button("Memory").performClick()
        renderSettings("memory-settings")
    }

    private fun cameraInfo(manual: Boolean): dev.uninvg.camera.CameraInfo {
        val caps = dev.uninvg.core.CameraCaps(manual, 37, 3200, 800, 100_000, 2_000_000_000, 2_000_000_000, 8_000_000)
        val profile = dev.uninvg.core.ProfileSelector.select(dev.uninvg.core.DeviceIdentity("samsung", "SM-S921B", "Exynos 2400"), caps)
        return dev.uninvg.camera.CameraInfo("0", profile, caps, 1280, 720, 90, false, true, "Test capabilities", 0.7, 0.5)
    }

    private fun renderSettings(name: String) {
        val window = dialog().window!!
        layout(window.decorView, window.attributes.width, window.attributes.height)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        saveScreenshot(name, window.decorView)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun saveScreenshot(name: String, decor: View) {
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(bitmap))
        val folder = java.io.File("build/reports/ui").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun preferences() = activity.getSharedPreferences("controls", 0)
    private fun dialog(): Dialog = ShadowDialog.getLatestDialog()
    private fun gear(): ImageButton = views().filterIsInstance<ImageButton>().single { it.contentDescription == "Settings" }
    private fun button(label: String): Button = views().filterIsInstance<Button>().single { it.text == label }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("update state: ${activity.debugUpdateState()}", condition())
    }
    private fun views(): List<View> {
        fun collect(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { collect(view.getChildAt(it)) } else emptyList()
        return collect(activity.window.decorView) + (ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { collect(it) } ?: emptyList())
    }

    @Implements(GLSurfaceView::class)
    class QuietGlView : org.robolectric.shadows.ShadowSurfaceView() {
        @Implementation fun setRenderer(renderer: GLSurfaceView.Renderer) = Unit
        @Implementation fun setRenderMode(mode: Int) = Unit
        @Implementation fun getRenderMode(): Int = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        @Implementation fun onResume() = Unit
        @Implementation fun onPause() = Unit
        @Implementation fun requestRender() = Unit
        @Implementation fun queueEvent(event: Runnable) = Unit
        @Implementation override fun onAttachedToWindow() = Unit
        @Implementation fun onDetachedFromWindow() = Unit
    }
}
