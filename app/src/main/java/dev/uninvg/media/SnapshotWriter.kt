package dev.uninvg.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

object SnapshotWriter {
    fun fileName(): String = "UniNVG_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())}.png"

    fun save(context: Context, bitmap: Bitmap, destination: Uri? = null): Uri {
        val resolver = context.contentResolver
        var inserted: Uri? = null
        try {
            val uri = destination ?: if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName())
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UniNVG")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also { inserted = it }
                    ?: throw IOException("Unable to create image")
            } else throw IOException("Choose a save location")
            resolver.openOutputStream(uri, "w").use { stream ->
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Image could not be written")
            }
            if (Build.VERSION.SDK_INT >= 29 && inserted != null) {
                val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                if (updated != 1) throw IOException("Image could not be published")
            }
            return uri
        } catch (error: Exception) {
            inserted?.let { uri ->
                try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }
}
