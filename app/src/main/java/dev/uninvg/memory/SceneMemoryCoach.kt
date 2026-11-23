package dev.uninvg.memory

/** Rule-based capture coach: reads the live snapshot and says the next move. */
object SceneMemoryCoach {
    const val TARGET_VIEWS = 8

    data class Snap(
        val capturing: Boolean,
        val poseAvailable: Boolean,
        val stable: Boolean,
        val lit: Boolean,
        val outlines: Int,
        val views: Int,
        val freshReference: Boolean,
        val repeatedAim: Boolean
    )

    data class Verdict(val message: String, val readyForCapture: Boolean, val captureComplete: Boolean)

    private val NOTHING = Verdict("", false, false)

    fun advise(snap: Snap): Verdict = when {
        !snap.capturing -> NOTHING
        !snap.poseAvailable -> Verdict("Lift the phone flat, then hold it until the view steadies", false, false)
        !snap.stable -> Verdict("Hold still — gyro is settling", false, false)
        snap.views >= TARGET_VIEWS -> Verdict("Enough views — finish and name the scene", false, true)
        !snap.lit -> Verdict("Slightly dark for clean outlines — raise gain or bias", false, false)
        snap.outlines < 8 -> Verdict("Sweep slowly across the scene until outlines appear", false, false)
        !snap.freshReference -> Verdict("Keep the phone still, outlines are refreshing", false, false)
        snap.repeatedAim -> Verdict("Turn a little — capture a new view of the same spot", false, false)
        else -> Verdict("Good. Stay put — capturing this view", true, false)
    }
}
