package dev.uninvg.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import androidx.exifinterface.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MediaSource(
    private val context: Context,
    private val listener: Listener,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) {
    interface Listener {
        fun onPhoto(bitmap: Bitmap)
        fun onVideo(width: Int, height: Int)
        fun onMediaError(message: String)
    }
    private val main = Handler(Looper.getMainLooper())
    private var openTimeout: Runnable? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var generation = 0
    var playing = false
        private set

    fun open(uri: Uri, texture: SurfaceTexture) {
        stop()
        val token = generation
        openTimeout = Runnable { report(token, IOException("Opening media timed out")) }.also { main.postDelayed(it, 30_000) }
        try {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) {
                worker.execute {
                    try {
                        val bitmap = decode(uri)
                        main.post {
                            if (token == generation) {
                                clearOpenTimeout()
                                try { listener.onPhoto(bitmap) }
                                catch (error: RuntimeException) { bitmap.recycle(); report(token, error) }
                            } else bitmap.recycle()
                        }
                    } catch (error: Exception) { report(token, error) }
                    catch (error: OutOfMemoryError) { report(token, IOException("Image exceeds available memory", error)) }
                }
            } else if (mime.startsWith("video/")) {
                surface = Surface(texture)
                player = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setSurface(surface)
                    setVolume(0f, 0f)
                    isLooping = true
                    setOnPreparedListener { media ->
                        if (token != generation) return@setOnPreparedListener
                        if (media.videoWidth <= 0 || media.videoHeight <= 0) {
                            report(token, IOException("No video track found"))
                            return@setOnPreparedListener
                        }
                        try {
                            clearOpenTimeout()
                            listener.onVideo(media.videoWidth, media.videoHeight)
                            media.start()
                            playing = true
                        } catch (error: RuntimeException) { report(token, error) }
                    }
                    setOnErrorListener { _, what, extra ->
                        report(token, IOException("Video decoder error $what/$extra")); true
                    }
                    prepareAsync()
                }
            } else report(token, IOException("Choose a photo or a video"))
        } catch (error: Exception) { report(token, error) }
    }

    fun togglePlayback(): Boolean {
        val media = player ?: return false
        try {
            if (playing) media.pause() else media.start()
            playing = !playing
        } catch (error: IllegalStateException) { report(generation, error) }
        return playing
    }

    private fun clearOpenTimeout() {
        openTimeout?.let(main::removeCallbacks)
        openTimeout = null
    }

    fun stop() {
        clearOpenTimeout()
        generation++
        playing = false
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    fun release() { stop(); worker.shutdownNow() }

    private fun decode(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = (1280f / max(info.size.width, info.size.height)).coerceAtMost(1f)
                decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Photo could not be decoded")
        options.inSampleSize = 1
        while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1280) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Photo could not be decoded")
        val orientation = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun report(token: Int, error: Exception) {
        Log.e("UniNVG.Media", "operation=load", error)
        main.post { if (token == generation) { stop(); listener.onMediaError(error.message ?: "Unable to open file") } }
    }
}
