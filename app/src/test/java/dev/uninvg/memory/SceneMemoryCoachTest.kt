package dev.uninvg.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneMemoryCoachTest {
    private fun snap(modify: (SceneMemoryCoach.Snap) -> SceneMemoryCoach.Snap = { it }) = SceneMemoryCoach.Snap(
        capturing = true, poseAvailable = true, stable = true, lit = true,
        outlines = 40, views = 2, freshReference = true, repeatedAim = false
    ).let(modify)

    @Test fun idleWhenNotCapturing() {
        val verdict = SceneMemoryCoach.advise(snap { it.copy(capturing = false) })
        assertEquals("", verdict.message)
        assertFalse(verdict.readyForCapture)
        assertFalse(verdict.captureComplete)
    }

    @Test fun steadyingOrdersBeforeAnythingElse() {
        val lifted = SceneMemoryCoach.advise(snap { it.copy(poseAvailable = false) })
        assertTrue(lifted.message.contains("phone"))
        assertFalse(lifted.readyForCapture)

        val settling = SceneMemoryCoach.advise(snap { it.copy(stable = false) })
        assertTrue(settling.message.contains("still"))
        assertFalse(settling.readyForCapture)
    }

    @Test fun completeOnceEightViewsAreHeld() {
        val verdict = SceneMemoryCoach.advise(snap { it.copy(views = SceneMemoryCoach.TARGET_VIEWS) })
        assertTrue(verdict.captureComplete)
        assertEquals(SceneMemoryCoach.TARGET_VIEWS, 8)
    }

    @Test fun darknessAndSparseOutlinesGetTheirOwnAdvice() {
        val dark = SceneMemoryCoach.advise(snap { it.copy(lit = false) })
        assertTrue(dark.message.contains("gain") || dark.message.contains("bias"))
        assertFalse(dark.readyForCapture)

        val sparse = SceneMemoryCoach.advise(snap { it.copy(outlines = 4) })
        assertTrue(sparse.message.contains("outlines"))
        assertFalse(sparse.readyForCapture)
    }

    @Test fun repeatedAimAsksForATurn() {
        val verdict = SceneMemoryCoach.advise(snap { it.copy(repeatedAim = true, freshReference = true) })
        assertTrue(verdict.message.contains("Turn"))
        assertFalse(verdict.readyForCapture)
    }

    @Test fun aFreshSteadyLitNewViewIsCapturedAutomatically() {
        val verdict = SceneMemoryCoach.advise(snap())
        assertTrue(verdict.readyForCapture)
        assertFalse(verdict.captureComplete)
    }

    @Test fun staleReferenceWaitsInsteadOfCapturing() {
        val verdict = SceneMemoryCoach.advise(snap { it.copy(freshReference = false) })
        assertFalse(verdict.readyForCapture)
        assertTrue(verdict.message.contains("refresh"))
    }
}
