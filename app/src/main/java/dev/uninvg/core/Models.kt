package dev.uninvg.core

import kotlin.math.max

val SUPPORTED_FPS = listOf(120f, 90f, 60f, 50f, 40f, 30f, 25f, 24f, 15f, 10f, 5f, 1f, 0.5f, 0.25f)
val SUPPORTED_RESOLUTIONS = listOf(360, 480, 720, 1080, 1440, 2160)

fun fpsLabel(fps: Float): String = if (fps < 1f) "1/${(1f / fps).toInt()}" else fps.toInt().toString()

enum class Palette(val label: String) {
    NATURAL("Natural"), GREEN("Green"), WHITE("White"), RED("Red"), ICE("Ice")
}

data class NvSettings(
    val fps: Float = 15f,
    val resolution: Int = 720,
    val vr: Boolean = false,
    val automatic: Boolean = true,
    val exposureBias: Float = 0f,
    val manualShutterNs: Long = 100_000_000,
    val manualIso: Int = 800,
    val digitalGain: Float = 1f,
    val gamma: Float = 1f,
    val denoise: Float = 0.45f,
    val stackDepth: Int = 4,
    val palette: Palette = Palette.GREEN,
    val regional: Boolean = false,
    val torch: Boolean = false,
    val assist: Boolean = true
) {
    init {
        require(fps in SUPPORTED_FPS)
        require(resolution in SUPPORTED_RESOLUTIONS)
        require(exposureBias.isFinite() && exposureBias in -2f..2f)
        require(manualShutterNs > 0)
        require(manualIso > 0)
        require(digitalGain.isFinite() && digitalGain in 1f..16f)
        require(gamma.isFinite() && gamma in 0.5f..2.5f)
        require(denoise.isFinite() && denoise in 0f..1f)
        require(stackDepth in listOf(1, 2, 4, 8, 16))
    }
}

data class DeviceIdentity(val manufacturer: String, val model: String, val soc: String)
data class FpsRange(val lower: Int, val upper: Int) {
    init { require(lower > 0 && upper >= lower) }
}

data class CameraCaps(
    val manualSensor: Boolean,
    val isoMin: Int = 100,
    val isoMax: Int = 100,
    val maxAnalogIso: Int? = null,
    val exposureMinNs: Long = 1_000_000,
    val exposureMaxNs: Long = 33_333_333,
    val maxFrameDurationNs: Long = 33_333_333,
    val minFrameDurationNs: Long = 0,
    val aeFpsRanges: List<FpsRange> = emptyList()
) {
    init {
        require(isoMin > 0 && isoMax >= isoMin)
        require(exposureMinNs > 0 && exposureMaxNs >= exposureMinNs)
        require(maxFrameDurationNs >= exposureMinNs && minFrameDurationNs >= 0)
    }
    val analogCeiling: Int get() = (maxAnalogIso ?: isoMax).coerceIn(isoMin, isoMax)
}

enum class ProfileId { S24_EXYNOS, S22_EXYNOS, A71, J3_2017, UNIVERSAL }
data class DeviceProfile(
    val id: ProfileId,
    val label: String,
    val previewLongEdge: Int,
    val maxStackDepth: Int,
    val motionThreshold: Float,
    val preferOis: Boolean,
    val gainCeiling: Float = 16f,
    val denoiseBias: Float = 1f,
    val sharpenBias: Float = 1f
) {
    init {
        require(gainCeiling.isFinite() && gainCeiling in 1f..16f)
        require(denoiseBias.isFinite() && denoiseBias in 0.5f..2f)
        require(sharpenBias.isFinite() && sharpenBias in 0.5f..2f)
    }
}

data class CapturePlan(
    val manual: Boolean,
    val exposureNs: Long,
    val iso: Int,
    val frameDurationNs: Long,
    val aeRange: FpsRange?,
    val requestedFps: Float,
    val cadenceLimited: Boolean,
    val sensorGainExhausted: Boolean
)

data class CaptureMetadata(
    val timestampNs: Long,
    val exposureNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?
) {
    val exposureProduct: Double? get() = exposureNs?.let { exposure ->
        iso?.takeIf { it > 0 && exposure > 0 }?.let { exposure.toDouble() * it }
    }
}

enum class SceneKind(val label: String) { LIT("Balanced"), DARK("Low light"), PHOTON_STARVED("Very low light") }
data class SceneStats(
    val mean: Float,
    val p10: Float,
    val p50: Float,
    val p90: Float,
    val clippedFraction: Float,
    val noise: Float,
    val edgeEnergy: Float,
    val tiles: List<Float>,
    val kind: SceneKind
)

data class Enhancement(
    val gain: Float = 1f,
    val gamma: Float = 1f,
    val regionalGains: List<Float> = List(16) { 1f },
    val noise: Float = 0.01f
)

data class AutoDecision(val exposureProduct: Double, val enhancement: Enhancement)

fun frameBudgetNs(fps: Float): Long {
    require(fps in SUPPORTED_FPS)
    return max(1L, (1_000_000_000.0 / fps).toLong())
}
