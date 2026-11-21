package dev.uninvg.core

import org.junit.Assert.*
import org.junit.Test

class ProfileSelectorTest {
    private val caps = CameraCaps(manualSensor = true)

    @Test
    fun exynosFlagshipsAcceptSocAliasesAndBlankRegionalSoc() {
        for (soc in listOf("Exynos2400", "Samsung Exynos 2400", "s5e9945", "")) {
            val profile = ProfileSelector.select(DeviceIdentity("sAmSuNg", "sm-s921b/DS", soc), caps)
            assertEquals(ProfileId.S24_EXYNOS, profile.id)
            assertEquals(1280, profile.previewLongEdge)
            assertEquals(16, profile.maxStackDepth)
            assertTrue(profile.preferOis)
        }
        for (soc in listOf("Exynos2200", "Samsung Exynos 2200", "S5E9925", "  ")) {
            val profile = ProfileSelector.select(DeviceIdentity("Samsung", "SM-S901B", soc), caps)
            assertEquals(ProfileId.S22_EXYNOS, profile.id)
            assertEquals(1280, profile.previewLongEdge)
            assertEquals(8, profile.maxStackDepth)
        }
    }

    @Test
    fun flagshipsRejectConflictsWrongRegionsAndMissingManualControl() {
        for (model in listOf("SM-S921B", "SM-S901B")) {
            for (soc in listOf("Snapdragon 8 Gen 3", "Qualcomm", "qcom", "SM8650", "SM8450", "sdm845", "msm8998", "Exynos 990", "unknown", "Exynos2400 Qualcomm")) {
                assertEquals(soc, ProfileId.UNIVERSAL, ProfileSelector.select(DeviceIdentity("Samsung", model, soc), caps).id)
            }
            assertEquals(ProfileId.UNIVERSAL, ProfileSelector.select(DeviceIdentity("Samsung", model, ""), caps.copy(manualSensor = false)).id)
        }
        for (identity in listOf(
            DeviceIdentity("Other", "SM-S921B", "Exynos2400"),
            DeviceIdentity("Samsung", "SM-S921U", "Exynos2400"),
            DeviceIdentity("Samsung", "SM-S901B", "Exynos2400"),
            DeviceIdentity("Samsung", "SM-S921B", "Exynos2200"),
            DeviceIdentity("Samsung", "SM-S921BFAKE", "")
        )) assertEquals(ProfileId.UNIVERSAL, ProfileSelector.select(identity, caps).id)
    }

    @Test
    fun legacyProfilesAcceptVariantsAndHaveConservativeCaps() {
        for (model in listOf("SM-A715F", "SM-A715W", "sm-a715fn/DS")) {
            val a71 = ProfileSelector.select(DeviceIdentity("samsung", model, "Snapdragon 730"), caps.copy(manualSensor = false))
            assertEquals(ProfileId.A71, a71.id)
            assertEquals(960, a71.previewLongEdge)
            assertEquals(8, a71.maxStackDepth)
            assertFalse(a71.preferOis)
            assertTrue(a71.motionThreshold < ProfileSelector.select(DeviceIdentity("", "", ""), caps).motionThreshold)
        }
        val j3 = ProfileSelector.select(DeviceIdentity("SAMSUNG", "SM-J330FN", "Exynos 7570"), caps)
        assertEquals(ProfileId.J3_2017, j3.id)
        assertEquals(640, j3.previewLongEdge)
        assertEquals(4, j3.maxStackDepth)
        assertTrue(j3.denoiseBias > ProfileSelector.byId(ProfileId.UNIVERSAL).denoiseBias)
    }

    @Test
    fun forcedProfileOverridesDetectionWhateverTheHardwareReports() {
        for (id in ProfileId.entries) {
            assertEquals(id, ProfileSelector.select(DeviceIdentity("", "", ""), caps.copy(manualSensor = false), id).id)
        }
        val forced = ProfileSelector.select(DeviceIdentity("Samsung", "SM-S901B", "Exynos2200"), caps, ProfileId.A71)
        assertEquals(ProfileId.A71, forced.id)
    }

    @Test
    fun tuningStaysAdaptiveBoundariesGrowCleanerTowardFlagships() {
        val s24 = ProfileSelector.byId(ProfileId.S24_EXYNOS)
        val s22 = ProfileSelector.byId(ProfileId.S22_EXYNOS)
        val a71 = ProfileSelector.byId(ProfileId.A71)
        val j3 = ProfileSelector.byId(ProfileId.J3_2017)
        for (profile in listOf(s24, s22, a71, j3, ProfileSelector.byId(ProfileId.UNIVERSAL))) {
            assertTrue(profile.gainCeiling in 8f..16f)
            assertTrue(profile.denoiseBias in 0.5f..2f)
        }
        assertTrue(j3.denoiseBias > s24.denoiseBias)
        assertTrue(j3.gainCeiling < s24.gainCeiling && a71.gainCeiling <= s22.gainCeiling)
    }

    @Test
    fun universalFallbackDoesNotAssumeSamsungOrSensorLimits() {
        for (identity in listOf(DeviceIdentity("", "", ""), DeviceIdentity("Other", "SM-A715F", ""), DeviceIdentity("Samsung", "SM-A716B", ""))) {
            val profile = ProfileSelector.select(identity, caps)
            assertEquals(ProfileId.UNIVERSAL, profile.id)
            assertEquals(960, profile.previewLongEdge)
            assertEquals(8, profile.maxStackDepth)
            assertFalse(profile.preferOis)
        }
    }
}
