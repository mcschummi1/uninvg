package dev.uninvg.core

/**
 * Turns thermal pressure into smoothness-first render limits.
 * Pure pacing/quality trade-offs only — exposure and every light-affecting control stay adaptive.
 */
object ThermalPolicy {
    const val NORMAL = 0
    const val WARM = 1
    const val HOT = 2

    /** PowerManager thermal statuses: 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN. */
    fun levelForStatus(status: Int): Int = when {
        status >= 3 -> HOT
        status == 2 -> WARM
        else -> NORMAL
    }

    /** Cap the enhancement/present rate, never above what the user picked. */
    fun renderFpsCap(level: Int, fps: Float): Float = when (level) {
        WARM -> min(fps, 30f)
        HOT -> min(fps, 15f)
        else -> fps
    }

    /** Shrink the temporal stack on heated silicon; the stack rebuilds automatically once it cools. */
    fun stackCap(level: Int, depth: Int): Int = when (level) {
        WARM -> min(depth, 8)
        HOT -> min(depth, 2)
        else -> depth
    }

    fun sharpenScale(level: Int): Float = when (level) {
        WARM -> 0.7f
        HOT -> 0.4f
        else -> 1f
    }

    /**
     * Heat-driven level: battery temperature starts guarding at 40 °C (hot at 48 °C),
     * SoC temperature (first CPU, else GPU zone) at 60 °C (hot at 70 °C). Inputs are tenths of °C.
     */
    fun heatLevel(batteryTenths: Int?, siliconTenths: Int?): Int = maxOf(
        batteryOf(batteryTenths),
        siliconOf(siliconTenths)
    )

    private fun batteryOf(batteryTenths: Int?): Int = when {
        batteryTenths == null || batteryTenths < 100 || batteryTenths > 1_000 -> NORMAL
        batteryTenths >= 480 -> HOT
        batteryTenths >= 400 -> WARM
        else -> NORMAL
    }

    private fun siliconOf(siliconTenths: Int?): Int = when {
        siliconTenths == null || siliconTenths < 100 || siliconTenths > 1_200 -> NORMAL
        siliconTenths >= 700 -> HOT
        siliconTenths >= 600 -> WARM
        else -> NORMAL
    }

    /** The strongest signal of the three wins: system thermal status, battery heat, SoC heat. */
    fun effective(statusLevel: Int, batteryTenths: Int?, siliconTenths: Int?): Int =
        maxOf(levelForStatus(statusLevel), heatLevel(batteryTenths, siliconTenths))
}
