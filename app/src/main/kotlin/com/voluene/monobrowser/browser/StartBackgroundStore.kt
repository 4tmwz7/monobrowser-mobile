package com.voluene.monobrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.voluene.monobrowser.settings.StartBackground
import com.voluene.monobrowser.util.writeBytesAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of setting a background. */
enum class BackgroundResult { OK, INVALID_URL, DOWNLOAD_FAILED, TOO_LARGE, NOT_AN_IMAGE }

/**
 * The start page's own copy of the chosen image (`files/start-background.jpg`, not the cache: the system may clear
 * that). Whatever the source (a photo of the device or a URL), the bytes are streamed to a temporary file in the
 * cache (never held whole in memory), decoded with a sample size so the result stays below twice the target side,
 * rotated by their EXIF orientation, scaled down, flattened onto white (transparent PNGs) and written as JPEG
 * atomically, so the old background stays until the new one is saved, and it works offline after the original is
 * gone. No URI permission is kept. Imports and removal never run at the same time ([mutex]).
 */
class StartBackgroundStore(private val file: File, private val cacheDir: File) {
    @Volatile private var cached: Pair<Long, ImageBitmap>? = null
    private val mutex = Mutex()

    /** The image if it is already decoded (no disk access, safe on the main thread). */
    fun peek(version: Long): ImageBitmap? = cached?.takeIf { it.first == version && version > 0L }?.second

    /** The image for [version] (the settings stamp), decoded once and kept; null when there is no (readable) file. Blocking. */
    fun load(version: Long): ImageBitmap? {
        if (version <= 0L) return null
        cached?.takeIf { it.first == version }?.let { return it.second }
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.asImageBitmap() ?: return null
        cached = version to bitmap
        return bitmap
    }

    suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) {
            cached = null
            file.delete()
        }
    }

    suspend fun importFromUri(context: Context, uri: Uri): BackgroundResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            withTemp { temp ->
                val copied = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { copyLimited(it, temp, StartBackground.MAX_DEVICE_BYTES) }
                }.getOrNull() ?: return@withTemp BackgroundResult.DOWNLOAD_FAILED
                if (copied == COPY_TOO_LARGE) BackgroundResult.TOO_LARGE else store(temp)
            }
        }
    }

    suspend fun importFromUrl(url: String): BackgroundResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!StartBackground.isValidUrl(url)) return@withContext BackgroundResult.INVALID_URL
            withTemp { temp ->
                when (download(url.trim(), temp)) {
                    COPY_FAILED -> BackgroundResult.DOWNLOAD_FAILED
                    COPY_TOO_LARGE -> BackgroundResult.TOO_LARGE
                    else -> store(temp)
                }
            }
        }
    }

    private inline fun <T> withTemp(block: (File) -> T): T {
        cacheDir.mkdirs()
        val temp = File.createTempFile("bg-import", ".tmp", cacheDir)
        try {
            return block(temp)
        } finally {
            temp.delete()
        }
    }

    /** Copies at most [limit] bytes; returns the size, [COPY_TOO_LARGE] when the source is bigger, [COPY_FAILED] on a timeout. */
    private fun copyLimited(input: InputStream, target: File, limit: Long, deadline: Long = Long.MAX_VALUE): Long {
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                if (System.currentTimeMillis() > deadline) return COPY_FAILED
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return COPY_TOO_LARGE
                out.write(buffer, 0, n)
            }
        }
        return total
    }

    /** Downloads [url] into [target]; a size, [COPY_TOO_LARGE] or [COPY_FAILED]. */
    private fun download(url: String, target: File): Long {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return COPY_FAILED
        connection.apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) return COPY_FAILED
            if (connection.contentLengthLong > StartBackground.MAX_DOWNLOAD_BYTES) return COPY_TOO_LARGE
            connection.inputStream.use { copyLimited(it, target, StartBackground.MAX_DOWNLOAD_BYTES, System.currentTimeMillis() + 30_000) }
        } catch (_: Exception) {
            COPY_FAILED
        } finally {
            connection.disconnect()
        }
    }

    private fun store(source: File): BackgroundResult {
        val bitmap = decode(source) ?: return BackgroundResult.NOT_AN_IMAGE
        return try {
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, StartBackground.JPEG_QUALITY, it) }.toByteArray()
            writeBytesAtomically(file, jpeg)
            cached = null
            BackgroundResult.OK
        } catch (_: Exception) {
            BackgroundResult.DOWNLOAD_FAILED
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Sampled decode from the file (no out-of-memory on big photos; the decoded side stays below twice
     * [StartBackground.MAX_SIDE]), EXIF rotation applied, longer side at most [StartBackground.MAX_SIDE], transparency
     * flattened onto white. The result is the caller's to recycle.
     */
    private fun decode(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = StartBackground.sampleSize(bounds.outWidth, bounds.outHeight) }
        var bitmap = runCatching { BitmapFactory.decodeFile(source.path, options) }.getOrNull() ?: return null
        fun replace(next: Bitmap?) {
            if (next != null && next !== bitmap) { bitmap.recycle(); bitmap = next }
        }
        val orientation = runCatching {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        if (!matrix.isIdentity) replace(runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }.getOrNull())
        val (w, h) = StartBackground.targetSize(bitmap.width, bitmap.height)
        if (w > 0 && (w != bitmap.width || h != bitmap.height)) replace(runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }.getOrNull())
        if (bitmap.hasAlpha()) {
            // JPEG has no transparency: a transparent PNG would turn black.
            val flat = runCatching { Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (flat != null) {
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                replace(flat)
            }
        }
        return bitmap
    }

    private companion object {
        const val COPY_TOO_LARGE = -1L
        const val COPY_FAILED = -2L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 MonoBrowser"
    }
}
