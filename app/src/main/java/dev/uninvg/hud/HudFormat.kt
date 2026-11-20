package dev.uninvg.hud

import java.util.Locale

object HudFormat {
    fun batteryLine(percent: Int?, temperatureTenths: Int?): String? {
        val parts = buildList {
            percent?.let { add("Battery ${it.coerceIn(0, 100)}%") }
            temperatureTenths?.let { add(String.format(Locale.US, "Temp %.1f\u00b0C", it / 10.0)) }
        }
        return if (parts.isEmpty()) null else parts.joinToString("  \u00b7  ")
    }
}
