package dev.uninvg.render

data class CameraOrientation(val sensorDegrees: Int, val displayDegrees: Int, val frontFacing: Boolean) {
    init {
        require(sensorDegrees in listOf(0, 90, 180, 270))
        require(displayDegrees in listOf(0, 90, 180, 270))
    }

    val relativeDegrees: Int get() = (sensorDegrees + (if (frontFacing) displayDegrees else -displayDegrees) + 360) % 360

    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        return if (relativeDegrees % 180 == 0) width to height else height to width
    }

    fun sensorPoint(x: Float, y: Float): Pair<Float, Float> {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        val u = if (frontFacing) 1f - x else x
        return when (relativeDegrees) {
            90 -> y to (1f - u)
            180 -> (1f - u) to (1f - y)
            270 -> (1f - y) to u
            else -> u to y
        }
    }

    fun textureMatrix(producer: FloatArray): FloatArray {
        require(producer.size == 16 && producer.all { it.isFinite() })
        val (cosine, sine) = when (displayDegrees) {
            90 -> 0f to -1f
            180 -> -1f to 0f
            270 -> 0f to 1f
            else -> 1f to 0f
        }
        val display = floatArrayOf(
            cosine, sine, 0f, 0f, -sine, cosine, 0f, 0f,
            0f, 0f, 1f, 0f, (1f - cosine + sine) / 2f, (1f - sine - cosine) / 2f, 0f, 1f
        )
        // SurfaceTexture already contains Camera2 sensor rotation, mirroring and crop.
        return FloatArray(16) { index ->
            val row = index % 4
            val column = index / 4
            (0..3).sumOf { k -> (producer[k * 4 + row] * display[column * 4 + k]).toDouble() }.toFloat()
        }
    }
}
