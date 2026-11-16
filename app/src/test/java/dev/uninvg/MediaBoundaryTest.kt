package dev.uninvg

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dev.uninvg.media.MediaSource
import dev.uninvg.media.SnapshotWriter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaBoundaryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var provider: MediaProvider
    private lateinit var worker: ControlledExecutor
    private lateinit var source: MediaSource
    private lateinit var texture: SurfaceTexture
    private val photos = mutableListOf<Bitmap>()
    private val errors = mutableListOf<String>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        provider = MediaProvider(temp.root)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        worker = ControlledExecutor()
        source = MediaSource(context, object : MediaSource.Listener {
            override fun onPhoto(bitmap: Bitmap) { photos += bitmap }
            override fun onVideo(width: Int, height: Int) { fail("Expected photo") }
            override fun onMediaError(message: String) { errors += message }
        }, worker)
        texture = SurfaceTexture(0)
    }

    @After fun teardown() {
        source.release()
        texture.release()
        photos.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test fun largePhotoDecodesAtBoundedSizeWithoutChangingItsAspect() {
        val uri = image(3200, 1600)
        source.open(uri, texture)
        completeDecode()
        assertTrue(errors.isEmpty())
        assertEquals(1280, photos.single().width)
        assertEquals(640, photos.single().height)
        assertEquals(Color.RED, photos.single().getPixel(100, 100))
    }

    @Test fun corruptPhotoReportsOneFailureAndNoImage() {
        val file = temp.newFile("corrupt.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        source.open(provider.register(file, "image/jpeg"), texture)
        completeDecode()
        assertTrue(photos.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun unsupportedFileDoesNotReachTheDecoder() {
        val file = temp.newFile("file.txt").apply { writeText("not media") }
        source.open(provider.register(file, "text/plain"), texture)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Choose a photo or a video"), errors)
        assertEquals(0, worker.pendingCount)
    }

    @Test fun canceledDecodeCannotReplaceTheNextSource() {
        val uri = image(20, 10)
        source.open(uri, texture)
        source.stop()
        completeDecode()
        assertTrue(photos.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun rapidFileSwitchDeliversOnlyTheLastPhoto() {
        source.open(image(20, 10), texture)
        source.open(image(30, 20), texture)
        completeDecode()
        assertEquals(1, photos.size)
        assertEquals(30, photos.single().width)
        assertEquals(20, photos.single().height)
    }

    @Test fun openingTimeoutInvalidatesTheOutstandingDecode() {
        source.open(image(20, 10), texture)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(listOf("Opening media timed out"), errors)
        completeDecode()
        assertTrue(photos.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun successfulPhotoCancelsOpeningTimeout() {
        source.open(image(20, 10), texture)
        completeDecode()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(1, photos.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    @Config(sdk = [27])
    fun legacyDecoderHonorsExifRotation() {
        val file = temp.newFile("rotated.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        source.open(provider.register(file, "image/jpeg"), texture)
        completeDecode()
        assertEquals(20, photos.single().width)
        assertEquals(40, photos.single().height)
    }

    @Test
    @Config(sdk = [29])
    fun galleryExportPublishesOnlyAfterWritingAValidPng() {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val uri = SnapshotWriter.save(context, bitmap)
        bitmap.recycle()
        assertEquals(1, provider.inserted.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, provider.updated.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
        val decoded = BitmapFactory.decodeFile(provider.files.getValue(uri).absolutePath)
        assertEquals(12, decoded.width)
        assertEquals(8, decoded.height)
        assertEquals(Color.RED, decoded.getPixel(0, 0))
        decoded.recycle()
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun failedPublicationRemovesOnlyTheNewGalleryRow() {
        provider.rejectPublish = true
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertThrows(IOException::class.java) { SnapshotWriter.save(context, bitmap) }
        bitmap.recycle()
        assertEquals(1, provider.inserted.size)
        assertEquals(1, provider.deleted.size)
    }

    @Test
    @Config(sdk = [29])
    fun failedWriteDoesNotLeaveAPendingGalleryItem() {
        provider.rejectWrite = true
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertThrows(IOException::class.java) { SnapshotWriter.save(context, bitmap) }
        bitmap.recycle()
        assertEquals(1, provider.deleted.size)
        assertTrue(provider.updated.isEmpty())
    }

    @Test fun legacyExportRequiresAUserSelectedDestination() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertThrows(IOException::class.java) { SnapshotWriter.save(context, bitmap) }
        assertTrue(provider.inserted.isEmpty())
        val uri = provider.register(temp.newFile("saved.png"), "image/png")
        assertEquals(uri, SnapshotWriter.save(context, bitmap, uri))
        bitmap.recycle()
        assertTrue(provider.files.getValue(uri).length() > 0)
    }

    private fun completeDecode() { worker.runPending(); shadowOf(Looper.getMainLooper()).idle() }
    private fun image(width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val file = temp.newFile()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return provider.register(file, "image/png")
    }

    private class ControlledExecutor : AbstractExecutorService() {
        private val work = ArrayDeque<Runnable>()
        private var stopped = false
        val pendingCount get() = work.size
        override fun execute(command: Runnable) { check(!stopped); work.addLast(command) }
        fun runPending() { while (work.isNotEmpty()) work.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return work.toMutableList().also { work.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && work.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class MediaProvider(private val directory: File) : ContentProvider() {
        val files = linkedMapOf<Uri, File>()
        private val types = mutableMapOf<Uri, String>()
        val inserted = mutableListOf<ContentValues>()
        val updated = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()
        var rejectPublish = false
        var rejectWrite = false
        fun register(file: File, type: String): Uri {
            val uri = Uri.parse("content://media/test/${files.size + 1}")
            files[uri] = file; types[uri] = type; return uri
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = types[uri]
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted += ContentValues(requireNotNull(values))
            return register(File(directory, "export-${files.size}.png"), "image/png")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (rejectPublish) return 0
            updated += ContentValues(requireNotNull(values)); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            files.remove(uri)?.delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (rejectWrite && mode != "r") throw FileNotFoundException("Simulated write failure")
            val file = files[uri] ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
                else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
}
