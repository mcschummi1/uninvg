package dev.uninvg.memory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * AR use cases on top of the earth-anchored pose system:
 * - anchor pins: drop a world-ray marker on a spot (gate, tree, path bend) and re-find it,
 * - edge ticks: point toward anchors outside the view, so turning is guided,
 * - horizon: a level reference in the dark for orientation.
 */
data class ArAnchor(val id: Long, val ray: Vec3, val label: String) {
    init { require(id > 0 && label.isNotBlank() && abs(ray.dot(ray) - 1) < 0.01) }
}

object ArHud {
    const val MAX_ANCHORS = 6

    /** X-mark for a visible anchor; empty when outside or behind. */
    fun marker(anchor: ArAnchor, pose: ViewPose, lens: ViewLens): List<ImageLine> {
        val p = pose.project(anchor.ray, lens) ?: return emptyList()
        if (p.x !in 0.02..0.98 || p.y !in 0.02..0.98) return emptyList()
        val s = 0.02
        return listOf(
            ImageLine(Point2(p.x - s, p.y - s), Point2(p.x + s, p.y + s)),
            ImageLine(Point2(p.x - s, p.y + s), Point2(p.x + s, p.y - s))
        )
    }

    /** Border pointer for an anchor not currently in view: the view-basis direction is the shortest turn. */
    fun edgeTick(anchor: ArAnchor, pose: ViewPose, lens: ViewLens): ImageLine? {
        var dx = pose.right.dot(anchor.ray)
        var dy = pose.up.dot(anchor.ray)
        val length = hypot(dx, dy)
        if (length < 1e-6) return null
        dx /= length; dy /= length
        val tx = if (abs(dx) > 1e-9) 0.5 / abs(dx) else Double.POSITIVE_INFINITY
        val ty = if (abs(dy) > 1e-9) 0.5 / abs(dy) else Double.POSITIVE_INFINITY
        val reach = min(tx, ty) * 0.25
        val inner = 0.04
        return ImageLine(
            Point2(0.5 + dx * (reach - inner), 0.5 + dy * (reach - inner)),
            Point2(0.5 + dx * reach, 0.5 + dy * reach)
        )
    }

    /** Dashed level line: sample the horizontal great circle and keep on-screen spans. */
    fun horizon(pose: ViewPose, lens: ViewLens): List<ImageLine> {
        val segments = mutableListOf<ImageLine>()
        var previous: Point2? = null
        val steps = 48
        for (i in 0..steps) {
            val angle = 2.0 * PI * i / steps
            val p = pose.project(Vec3(cos(angle), sin(angle), 0.0), lens) ?: run { previous = null; continue }
            if (p.x in 0.0..1.0 && p.y in 0.0..1.0) {
                previous?.let { if (hypot(p.x - it.x, p.y - it.y) < 0.35) segments.add(ImageLine(it, p)) }
                previous = p
            } else previous = null
        }
        return segments
    }
}
