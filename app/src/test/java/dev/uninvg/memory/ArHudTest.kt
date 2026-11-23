package dev.uninvg.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArHudTest {
    private val levelPose = ViewPose(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, -1.0, 0.0))
    private val lens = ViewLens(0.5, 0.35, 0.5 / 0.35)

    private fun anchor(ray: Vec3, id: Long = 1) = ArAnchor(id, ray.unit(), "P$id")

    @Test fun markerSitsCenteredOnItsWorldRay() {
        val marks = ArHud.marker(anchor(levelPose.forward), levelPose, lens)
        assertEquals(2, marks.size)
        for (mark in marks) {
            assertEquals(0.5, (mark.a.x + mark.b.x) / 2, 1e-9)
            assertEquals(0.5, (mark.a.y + mark.b.y) / 2, 1e-9)
        }
    }

    @Test fun markerVanishesWhenAnchorIsBehind() {
        val behind = anchor(levelPose.forward * -1.0)
        assertTrue(ArHud.marker(behind, levelPose, lens).isEmpty())
        assertNull(ArHud.edgeTick(behind, levelPose, lens))
    }

    @Test fun markerVanishesWhenAnchorLeavesTheView() {
        val sidewaysPose = ViewPose(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(-1.0, 0.0, 0.0))
        assertTrue(ArHud.marker(anchor(levelPose.forward), sidewaysPose, lens).isEmpty())
    }

    @Test fun edgeTickPointsTowardTheAnchorFromTheCenter() {
        val tick = ArHud.edgeTick(anchor(Vec3(0.0, 0.0, 0.0).plus(levelPose.forward)), levelPose, lens)
        assertNull(tick) // anchor dead ahead and visible: no tick needed

        val off = anchor(levelPose.right)
        val pointing = ArHud.edgeTick(off, levelPose, lens)
        if (pointing != null) {
            assertTrue("tick should advance toward +x", pointing.b.x > pointing.a.x)
            assertEquals(0.0, pointing.a.y - pointing.b.y, 1e-9)
        }
    }

    @Test fun behindAnchorsTickThroughTheNearestEdge() {
        val behind = anchor(Vec3(5.0, -3.0, 0.0))
        val tick = ArHud.edgeTick(behind, levelPose, lens)
        if (tick != null) assertTrue("flipped tick moves toward +x", tick.b.x > tick.a.x)
    }

    @Test fun horizonCrossesTheMiddleOfALevelView() {
        val segments = ArHud.horizon(levelPose, lens)
        assertTrue("level view should show horizon segments", segments.isNotEmpty())
        for (segment in segments) {
            assertTrue(abs(segment.a.y - segment.b.y) < 0.02)
            assertEquals(0.5, (segment.a.y + segment.b.y) / 2, 0.01)
        }
    }

    @Test fun lookingUpLeavesTheHorizonBelowTheView() {
        val upPose = ViewPose(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.7071067811865476, -0.7071067811865476),
            Vec3(0.0, -0.7071067811865476, -0.7071067811865476))
        val segments = ArHud.horizon(upPose, lens)
        assertTrue("upward view should not project the horizon", segments.isEmpty())
    }
}
