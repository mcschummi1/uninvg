package dev.uninvg.memory

import kotlin.math.*

data class Vec3(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun unit(): Vec3 {
        val length = sqrt(dot(this))
        require(length.isFinite() && length > 1e-9)
        return this * (1.0 / length)
    }
}

data class ViewPose(val right: Vec3, val up: Vec3, val forward: Vec3) {
    init {
        require(listOf(right, up, forward).all { abs(it.dot(it) - 1) < 0.01 })
        require(abs(right.dot(up)) < 0.01 && abs(right.dot(forward)) < 0.01 && abs(up.dot(forward)) < 0.01)
    }
    fun ray(x: Double, y: Double, lens: ViewLens): Vec3 =
        (forward + right * ((2 * x - 1) * lens.tanX) + up * ((2 * y - 1) * lens.tanY)).unit()

    fun project(ray: Vec3, lens: ViewLens): Point2? {
        val depth = forward.dot(ray)
        if (depth <= 0.05) return null
        return Point2(0.5 + right.dot(ray) / depth / lens.tanX / 2, 0.5 + up.dot(ray) / depth / lens.tanY / 2)
    }

    companion object {
        fun fromDeviceMatrix(matrix: FloatArray, displayDegrees: Int, frontFacing: Boolean): ViewPose {
            require(matrix.size == 9 && matrix.all { it.isFinite() })
            require(displayDegrees in listOf(0, 90, 180, 270))
            val x = Vec3(matrix[0].toDouble(), matrix[3].toDouble(), matrix[6].toDouble())
            val y = Vec3(matrix[1].toDouble(), matrix[4].toDouble(), matrix[7].toDouble())
            val z = Vec3(matrix[2].toDouble(), matrix[5].toDouble(), matrix[8].toDouble())
            require(listOf(x, y, z).all { abs(it.dot(it) - 1) < 0.01 })
            val determinant = x.x * (y.y * z.z - y.z * z.y) - y.x * (x.y * z.z - x.z * z.y) + z.x * (x.y * y.z - x.z * y.y)
            require(abs(determinant - 1) < 0.01)
            val (right, up) = when (displayDegrees) {
                90 -> y to (x * -1.0)
                180 -> (x * -1.0) to (y * -1.0)
                270 -> (y * -1.0) to x
                else -> x to y
            }
            return ViewPose(right, up, z * if (frontFacing) 1.0 else -1.0)
        }
    }
}

data class Point2(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}
data class ImageLine(val a: Point2, val b: Point2)
data class WorldLine(val a: Vec3, val b: Vec3) {
    init { require(abs(a.dot(a) - 1) < 0.01 && abs(b.dot(b) - 1) < 0.01) }
}
data class ViewLens(val tanX: Double, val tanY: Double, val aspect: Double) {
    init { require(tanX.isFinite() && tanY.isFinite() && aspect.isFinite() && tanX in 0.02..5.0 && tanY in 0.02..5.0 && aspect in 0.1..10.0) }
}
data class PlaceFix(val latitude: Double, val longitude: Double, val accuracyM: Float, val timeMs: Long) {
    init { require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) }
    init { require(accuracyM.isFinite() && accuracyM >= 0 && timeMs > 0) }
    fun distanceTo(other: PlaceFix): Double {
        val lat = Math.toRadians(other.latitude - latitude)
        val lon = Math.toRadians(other.longitude - longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(lon / 2).pow(2)
        return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun couldBeSamePlace(other: PlaceFix): Boolean = accuracyM <= 100 && other.accuracyM <= 100 &&
        distanceTo(other) <= max(25.0, accuracyM.toDouble() + other.accuracyM)
}
data class ScenePatch(val forward: Vec3, val lines: List<WorldLine>) {
    init { require(abs(forward.dot(forward) - 1) < 0.01 && lines.size in 8..240) }
}
data class SavedScene(val id: String, val name: String, val createdMs: Long, val place: PlaceFix?, val patches: List<ScenePatch>) {
    init {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        require(name.isNotBlank() && name.length <= 64 && createdMs > 0)
        require(patches.size in 1..24)
    }
}

object SceneEdges {
    fun extract(luma: FloatArray, width: Int, height: Int): List<ImageLine> {
        require(width in 8..256 && height in 8..256 && luma.size == width * height)
        require(luma.all { it.isFinite() && it in 0f..1f })
        val gradients = FloatArray(luma.size)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val index = y * width + x
            val gx = abs(luma[index + 1] - luma[index - 1])
            val gy = abs(luma[index + width] - luma[index - width])
            gradients[index] = max(gx, gy)
        }
        val sorted = gradients.sorted()
        val threshold = max(0.03f, sorted[sorted.size / 2] * 4f)
        val lines = mutableListOf<ImageLine>()
        fun scan(horizontal: Boolean, fixed: Int, length: Int) {
            var start = -1
            for (i in 1 until length) {
                val x = if (horizontal) i else fixed
                val y = if (horizontal) fixed else i
                val edge = i < length - 1 && gradients[y * width + x] > threshold
                if (edge && start == -1) start = i
                if (!edge && start != -1) {
                    if (i - start >= 3 && lines.size < 240) {
                        fun point(position: Int) = if (horizontal) Point2(position.toDouble() / (width - 1), fixed.toDouble() / (height - 1))
                            else Point2(fixed.toDouble() / (width - 1), position.toDouble() / (height - 1))
                        lines += ImageLine(point(start), point(i - 1))
                    }
                    start = -1
                }
            }
        }
        for (y in 1 until height - 1 step 2) scan(true, y, width)
        for (x in 1 until width - 1 step 2) scan(false, x, height)
        return lines
    }

    fun patch(lines: List<ImageLine>, pose: ViewPose, lens: ViewLens): ScenePatch =
        ScenePatch(pose.forward, lines.map { WorldLine(pose.ray(it.a.x, it.a.y, lens), pose.ray(it.b.x, it.b.y, lens)) })

    fun projected(patches: List<ScenePatch>, pose: ViewPose, lens: ViewLens): List<ImageLine> = patches
        .filter { it.forward.dot(pose.forward) > 0.3 }.sortedByDescending { it.forward.dot(pose.forward) }.take(3).flatMap { patch ->
        patch.lines.mapNotNull { line ->
            val a = pose.project(line.a, lens) ?: return@mapNotNull null
            val b = pose.project(line.b, lens) ?: return@mapNotNull null
            if (listOf(a.x, a.y, b.x, b.y).any { it !in 0.0..1.0 }) null else ImageLine(a, b)
        }
    }

    fun resemblance(saved: List<ImageLine>, live: List<ImageLine>): Boolean {
        if (saved.size < 16 || live.size < 16) return false
        fun close(a: Point2, b: Point2) = hypot(a.x - b.x, a.y - b.y) < 0.025
        val probes = saved.take(240)
        val matched = probes.filter { a -> live.any { b ->
            (close(a.a, b.a) && close(a.b, b.b)) || (close(a.a, b.b) && close(a.b, b.a))
        } }
        val tiles = matched.flatMap { listOf(it.a, it.b) }.map {
            (it.x * 4).toInt().coerceIn(0, 3) to (it.y * 4).toInt().coerceIn(0, 3)
        }.distinct()
        return matched.size >= 12 && matched.size.toDouble() / probes.size > 0.75 && tiles.size >= 4
    }
}
