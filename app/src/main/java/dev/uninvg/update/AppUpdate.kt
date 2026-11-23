package dev.uninvg.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AppUpdate {
    const val REPO = "mcschummi1/uninvg"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"

    data class ReleaseInfo(val tag: String, val apkUrl: String, val notes: String? = null)

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
        return ReleaseInfo(normalize(tag), apk, releaseNotes(json))
    }

    /** Release body of the response returned as plain text; markdown stripped, capped before it turns into a wall. */
    fun releaseNotes(json: String, maxChars: Int = 260): String? {
        val raw = Regex("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1) ?: return null
        return summarizeNotes(raw, maxChars)
    }

    private fun summarizeNotes(raw: String, maxChars: Int): String? {
        if (raw.isBlank()) return null
        val unescaped = raw
            .replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", " ")
            .replace("\\\"", "\"").replace("\\\\", "\\")
        val plain = unescaped.lines()
            .mapNotNull { line ->
                val stripped = line
                    .replace(Regex("^\\s*#{1,6}\\s*"), "")
                    .replace(Regex("^\\s*[-*+]\\s+"), "• ")
                    .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
                    .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                    .replace("*", "").replace("`", "")
                    .trim()
                stripped.ifEmpty { null }
            }
            .joinToString("\n")
        val excerpt = plain.take(maxChars).trimEnd()
        if (excerpt.isBlank()) return null
        return if (plain.length > maxChars) "$excerpt…" else excerpt
    }

    fun fetchLatest(timeoutMs: Int = 8_000): ReleaseInfo? = parseRelease(fetchJson(LATEST_RELEASE_URL, timeoutMs))

    fun fetchCatalog(timeoutMs: Int = 8_000): List<ReleaseInfo> {
        val releases = parseReleases(fetchJson(RELEASES_URL, timeoutMs))
        val latest = parseRelease(fetchJson(LATEST_RELEASE_URL, timeoutMs))
        return if (latest != null && releases.none { it.tag == latest.tag }) releases + latest else releases
    }

    fun parseReleases(json: String): List<ReleaseInfo> {
        val pattern = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"|\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"|\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val releases = mutableListOf<ReleaseInfo>()
        var tag: String? = null
        var apk: String? = null
        var body: String? = null
        fun flush() {
            val currentTag = tag
            val currentApk = apk
            val currentBody = body ?: ""
            if (currentTag != null && currentApk != null) releases.add(ReleaseInfo(normalize(currentTag), currentApk, summarizeNotes(currentBody, 260)))
            tag = null
            apk = null
            body = null
        }
        for (match in pattern.findAll(json)) {
            val tagValue = match.groupValues[1]
            val apkValue = match.groupValues[2]
            val bodyValue = match.groupValues[3]
            if (tagValue.isNotEmpty()) {
                flush()
                tag = tagValue
            } else if (apkValue.isNotEmpty() && apk == null) apk = apkValue
            else if (bodyValue.isNotEmpty() && body == null) body = bodyValue
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
