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
    @Test fun releaseCatalogKeepsEveryVersionInNewestFirstOrder() {
        val json = """
            [
              { "tag_name": "v0.4.5.5", "assets": [ { "name": "UniNVG-0.4.5.5.apk", "browser_download_url": "https://example.com/UniNVG-0.4.5.5.apk" } ] },
              { "tag_name": "v0.4.0", "assets": [ { "name": "UniNVG-0.4.0.apk", "browser_download_url": "https://example.com/UniNVG-0.4.0.apk" } ] },
              { "tag_name": "v0.3.5", "assets": [ { "name": "notes.zip", "browser_download_url": "https://example.com/notes.zip" } ] }
            ]
        """.trimIndent()
        val releases = AppUpdate.parseReleases(json)
        assertEquals(listOf("0.4.5.5", "0.4.0"), releases.map { it.tag })
        assertEquals("https://example.com/UniNVG-0.4.0.apk", releases[1].apkUrl)
    }

    @Test fun releaseBodyBecomesPlainUpdateNotes() {
        val json = """
            {
              "tag_name": "v0.7.0.5",
              "body": "## What's new\n- Faster rendering\n- **50 fps** option\nDocs: [guide](https://example.com)",
              "assets": [ { "browser_download_url": "https://example.com/UniNVG-0.7.0.5.apk" } ]
            }
        """.trimIndent()
        val release = AppUpdate.parseRelease(json)
        assertEquals("0.7.0.5", release?.tag)
        val notes = release?.notes.orEmpty()
        assertTrue(notes, notes.contains("• Faster rendering"))
        assertTrue(notes, notes.contains("• 50 fps option"))
        assertTrue(notes, notes.contains("Docs: guide"))
        assertFalse(notes, notes.contains("#"))
        assertFalse(notes, notes.contains("- "))
    }

    @Test fun everyCatalogVersionCarriesItsOwnUpdateLog() {
        val json = """
            [
              { "tag_name": "v0.7.0", "body": "Thermal guardian\n- Heat pacing", "assets": [ { "browser_download_url": "https://example.com/UniNVG-0.7.0.apk" } ] },
              { "tag_name": "v0.6.5", "body": "Landscape-only cardboard", "assets": [ { "browser_download_url": "https://example.com/UniNVG-0.6.5.apk" } ] }
            ]
        """.trimIndent()
        val releases = AppUpdate.parseReleases(json)
        assertTrue(releases[0].notes.orEmpty().contains("Heat pacing"))
        assertTrue(releases[1].notes.orEmpty().contains("Landscape-only cardboard"))
    }

    @Test fun emptyOrBrokenBodiesYieldNoNotes() {
        assertTrue(AppUpdate.parseRelease("""{"tag_name":"v1","assets":[{"browser_download_url":"a.apk"}],"body":""}""")?.notes == null)
        assertTrue(AppUpdate.parseRelease("""{"tag_name":"v1","assets":[{"browser_download_url":"a.apk"}]}""")?.notes == null)
    }

    @Test fun emptyOrBrokenCatalogsAreEmpty() {
        assertTrue(AppUpdate.parseReleases("").isEmpty())
        assertTrue(AppUpdate.parseReleases("[]").isEmpty())
        assertTrue(AppUpdate.parseReleases("{\"message\":\"Not Found\"}").isEmpty())
    }
}
