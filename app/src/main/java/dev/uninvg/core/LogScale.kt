package dev.uninvg.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class LogScale(val minimum: Double, val maximum: Double, val steps: Int = 1000) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum > 0 && maximum >= minimum)
        require(steps > 0)
    }
    fun value(progress: Int): Double {
        require(progress in 0..steps)
        if (progress == 0) return minimum
        if (progress == steps) return maximum
        return exp(ln(minimum) + progress.toDouble() / steps * (ln(maximum) - ln(minimum))).coerceIn(minimum, maximum)
    }
    fun progress(value: Double): Int {
        require(value.isFinite())
        if (minimum == maximum) return 0
        return ((ln(value.coerceIn(minimum, maximum)) - ln(minimum)) / (ln(maximum) - ln(minimum)) * steps)
            .roundToInt().coerceIn(0, steps)
    }
}
