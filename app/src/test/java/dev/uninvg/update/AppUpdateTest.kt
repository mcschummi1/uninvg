package dev.uninvg.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test fun newerPatchMinorAndMajorVersionsAreDetected() {
        assertTrue(AppUpdate.isNewer("0.3.0", "0.3.1"))
        assertTrue(AppUpdate.isNewer("0.3.0", "0.4.0"))
        assertTrue(AppUpdate.isNewer("0.3.0", "1.0.0"))
        assertTrue(AppUpdate.isNewer("0.9.9", "1.0.0"))
    }

    @Test fun equalAndOlderVersionsAreNotNewer() {
        assertFalse(AppUpdate.isNewer("0.3.0", "0.3.0"))
        assertFalse(AppUpdate.isNewer("1.2.3", "1.2.3"))
        assertFalse(AppUpdate.isNewer("0.4.0", "0.3.0"))
        assertFalse(AppUpdate.isNewer("2.0.0", "1.9.9"))
    }

    @Test fun tagPrefixesAndGapsAreTolerated() {
        assertTrue(AppUpdate.isNewer("v0.3", "0.4.0"))
        assertTrue(AppUpdate.isNewer("0.3.0", "v0.3.1"))
        assertFalse(AppUpdate.isNewer("0.3.0", "v0.3"))
        assertTrue(AppUpdate.isNewer("0.3.0", "0.10"))
        assertFalse(AppUpdate.isNewer("0.10.0", "0.9.2"))
    }

    @Test fun preReleaseSuffixesCompareAgainstTheirNumericPrefix() {
        assertTrue(AppUpdate.isNewer("0.10.0", "0.11.0-beta"))
        assertFalse(AppUpdate.isNewer("0.10.5", "0.10.0"))
    }

    @Test fun latestReleaseJsonIsParsedIntoTagAndApkAsset() {
        val json = """
            {
              "url": "https://api.github.com/repos/mcschummi1/uninvg/releases/42",
              "tag_name": "v0.4.0",
              "name": "UniNVG 0.4.0",
              "assets": [
                { "name": "UniNVG-0.4.0.zip", "browser_download_url": "https://github.com/mcschummi1/uninvg/releases/download/v0.4.0/UniNVG-0.4.0.zip" },
                { "name": "UniNVG-0.4.0.apk", "browser_download_url": "https://github.com/mcschummi1/uninvg/releases/download/v0.4.0/UniNVG-0.4.0.apk" }
              ]
            }
        """.trimIndent()
        val release = AppUpdate.parseRelease(json)
        assertEquals("0.4.0", release?.tag)
        assertEquals("https://github.com/mcschummi1/uninvg/releases/download/v0.4.0/UniNVG-0.4.0.apk", release?.apkUrl)
    }

    @Test fun malformedResponsesParseToNull() {
        assertNull(AppUpdate.parseRelease(""))
        assertNull(AppUpdate.parseRelease("{\"message\":\"Not Found\"}"))
        assertNull(AppUpdate.parseRelease("""{"tag_name":"v0.4.0","assets":[]}"""))
    }
}
