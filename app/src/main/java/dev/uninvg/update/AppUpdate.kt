package dev.uninvg.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AppUpdate {
    const val REPO = "mcschummi1/uninvg"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

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

    fun fetchLatest(timeoutMs: Int = 8_000): ReleaseInfo? {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "UniNVG-updater")
            if (connection.responseCode != 200) throw IOException("GitHub API responded ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(body)
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
