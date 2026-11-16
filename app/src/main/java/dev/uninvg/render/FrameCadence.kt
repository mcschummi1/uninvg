package dev.uninvg.render

class FrameCadence {
    private var nextNs = 0L
    private var previousNs = 0L
    private var currentFps = 0f

    fun reset() { nextNs = 0; previousNs = 0; currentFps = 0f }

    fun accept(timestampNs: Long, fps: Float): Boolean {
        require(fps > 0 && timestampNs >= 0)
        val interval = (1_000_000_000.0 / fps).toLong()
        if (fps != currentFps || timestampNs < previousNs || nextNs == 0L) {
            currentFps = fps
            previousNs = timestampNs
            nextNs = timestampNs + interval
            return true
        }
        if (timestampNs == previousNs) return false
        previousNs = timestampNs
        if (timestampNs + minOf(1_000_000L, interval / 20) < nextNs) return false
        nextNs += interval
        if (nextNs <= timestampNs) nextNs = timestampNs + interval
        return true
    }
}
