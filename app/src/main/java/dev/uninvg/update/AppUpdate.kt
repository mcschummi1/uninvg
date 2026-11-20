package dev.uninvg.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AppUpdate {
    const val REPO = "mcschummi1/uninvg"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"

    data class ReleaseInfo(val tag: String, val apkUrl: String)

    fun normalize(tag: String) = tag.trim().removePrefix("v")

    fun isNewer(current: String, latest: String): Boolean {
        val installed = segments(current)
        val released = segments(latest)
        for (index in 0 until maxOf(installed.size, released.size)) {
            val mine = installed.getOrElse(index) { 0L }
            val theirs = released.getOrElse(index) { 0L }
            if (mine != theirs) return mine < theirs
        }
        return false
    }

    fun parseRelease(json: String): ReleaseInfo? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(json)
            ?.groupValues?.get(1) ?: return null
        return ReleaseInfo(normalize(tag), apk)
    }

    fun fetchLatest(timeoutMs: Int = 8_000): ReleaseInfo? = parseRelease(fetchJson(LATEST_RELEASE_URL, timeoutMs))

    fun fetchCatalog(timeoutMs: Int = 8_000): List<ReleaseInfo> {
        val releases = parseReleases(fetchJson(RELEASES_URL, timeoutMs))
        val latest = parseRelease(fetchJson(LATEST_RELEASE_URL, timeoutMs))
        return if (latest != null && releases.none { it.tag == latest.tag }) releases + latest else releases
    }

    fun parseReleases(json: String): List<ReleaseInfo> {
        val pattern = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"|\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"")
        val releases = mutableListOf<ReleaseInfo>()
        var tag: String? = null
        var apk: String? = null
        fun flush() {
            val currentTag = tag
            val currentApk = apk
            if (currentTag != null && currentApk != null) releases.add(ReleaseInfo(normalize(currentTag), currentApk))
            tag = null
            apk = null
        }
        for (match in pattern.findAll(json)) {
            val tagValue = match.groupValues[1]
            val apkValue = match.groupValues[2]
            if (tagValue.isNotEmpty()) {
                flush()
                tag = tagValue
            } else if (apkValue.isNotEmpty() && apk == null) apk = apkValue
        }
        flush()
        return releases
    }

    private fun fetchJson(url: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "UniNVG-updater")
            if (connection.responseCode != 200) throw IOException("GitHub API responded ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun download(apkUrl: String, destination: File, onProgress: (Int) -> Unit) {
        destination.parentFile?.mkdirs()
        val staged = File(destination.parentFile, "${destination.name}.part")
        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val total = connection.contentLengthLong
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            var reported = -1
            connection.inputStream.use { input ->
                staged.outputStream().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != reported) {
                                reported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (destination.exists()) destination.delete()
            if (!staged.renameTo(destination)) throw IOException("Could not stage the downloaded update")
        } finally {
            connection.disconnect()
        }
    }

    private fun segments(version: String) = version.trim().removePrefix("v")
        .takeWhile { it.isDigit() || it == '.' }
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }
}
