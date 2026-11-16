package dev.uninvg.core

import java.util.Locale

object ProfileSelector {
    private val universal = DeviceProfile(ProfileId.UNIVERSAL, "Universal", 960, 8, 0.05f, false)
    private val s24 = DeviceProfile(ProfileId.S24_EXYNOS, "Galaxy S24 · Exynos", 1280, 16, 0.075f, true)
    private val s22 = DeviceProfile(ProfileId.S22_EXYNOS, "Galaxy S22 · Exynos", 1280, 8, 0.06f, true)
    private val a71 = DeviceProfile(ProfileId.A71, "Galaxy A71", 960, 8, 0.035f, false)
    private val j3 = DeviceProfile(ProfileId.J3_2017, "Galaxy J3 (2017)", 640, 4, 0.045f, false)
    private val snapdragon = Regex("snapdragon|qualcomm|qcom|sm[0-9]{4}|sdm[0-9]+|msm[0-9]+")

    fun select(identity: DeviceIdentity, caps: CameraCaps): DeviceProfile {
        if (!identity.manufacturer.trim().equals("samsung", ignoreCase = true)) return universal
        val model = identity.model.trim().uppercase(Locale.ROOT).substringBefore('/')
        val soc = identity.soc.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return when {
            model == "SM-S921B" && caps.manualSensor && matchesSoc(soc, "exynos2400", "s5e9945") -> s24
            model == "SM-S901B" && caps.manualSensor && matchesSoc(soc, "exynos2200", "s5e9925") -> s22
            model.startsWith("SM-A715") -> a71
            model.startsWith("SM-J330") -> j3
            else -> universal
        }
    }

    private fun matchesSoc(soc: String, name: String, code: String): Boolean =
        soc.isEmpty() || (!snapdragon.containsMatchIn(soc) && (name in soc || code in soc))
}
