package com.voluene.monobrowser.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.voluene.monobrowser.data.TopSites
import com.voluene.monobrowser.ui.start.monogram
import java.net.HttpURLConnection
import java.net.URL

/** Cover art for media notifications. */
object Artwork {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val SIZE = 512

    /** Downloads and downsizes an image (blocking: call on Dispatchers.IO); null on any failure. */
    fun download(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private val YOUTUBE = Regex("^https://i\\.ytimg\\.com/vi(_webp)?/([\\w-]{6,})/[a-z]*default\\.(jpg|webp)(\\?.*)?$")

    /**
     * Cover for [url]; YouTube thumbnails are tried from the sharpest one down (maxres is missing for some videos)
     * and the 4:3 ones lose their black bars.
     */
    fun cover(url: String): Bitmap? {
        for (candidate in candidates(url)) {
            val bitmap = download(candidate) ?: continue
            return if (candidate.endsWith("/maxresdefault.jpg")) bitmap else cropLetterbox(bitmap)
        }
        return null
    }

    /** URLs to try for a cover, best first (pure). */
    fun candidates(url: String): List<String> {
        val id = YOUTUBE.matchEntire(url)?.groupValues?.get(2) ?: return listOf(url)
        return listOf("maxresdefault", "sddefault", "hqdefault").map { "https://i.ytimg.com/vi/$id/$it.jpg" }
    }

    /** 4:3 thumbnails of 16:9 videos carry black bars at the top and bottom: keep the 16:9 middle. */
    private fun cropLetterbox(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || kotlin.math.abs(w * 3 - h * 4) > w / 20) return bitmap
        val target = w * 9 / 16
        return runCatching { Bitmap.createBitmap(bitmap, 0, (h - target) / 2, w, target) }.getOrDefault(bitmap)
    }

    private val cache = HashMap<String, Bitmap>()

    /** The app's look as a cover: the site's monogram, white on black (read aloud, pages without artwork). */
    @Synchronized
    fun monogram(context: Context, site: String): Bitmap = cache.getOrPut(site) {
        val key = TopSites.hostKey("https://$site") ?: site
        val letters = if (key.isBlank()) "M" else monogram(TopSites.label(key))
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = SIZE * (if (letters.length > 1) 0.34f else 0.42f)
            textAlign = Paint.Align.CENTER
        }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = SIZE * 0.025f }
        canvas.drawRect(SIZE * 0.12f, SIZE * 0.12f, SIZE * 0.88f, SIZE * 0.88f, frame)
        val y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letters, SIZE / 2f, y, paint)
        bitmap
    }
}
