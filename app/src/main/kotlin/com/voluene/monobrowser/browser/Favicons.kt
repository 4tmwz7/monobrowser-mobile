package com.voluene.monobrowser.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.voluene.monobrowser.util.writeBytesAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

/** Pure rules of the favicon cache: file names, size and how many files are kept. Unit-tested. */
object FaviconPolicy {
    /** Longest side of a stored icon in px. */
    const val MAX_SIZE = 96
    const val MAX_FILES = 150
    /** An icon saved less than this long ago is not rewritten (a page reports its icon on every load). */
    const val REFRESH_MS = 24L * 60 * 60 * 1000

    class Entry(val name: String, val lastModified: Long)

    /** `png` file name for a normalized host (see `TopSites.hostKey`); null for anything that is not a plain host. */
    fun fileName(host: String): String? {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.length > 200) return null
        if (!h.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        if (h.startsWith(".") || h.contains("..")) return null
        return "$h.png"
    }

    /** Size that fits [width] x [height] into [max] on the longer side (never enlarges). */
    fun scaledSize(width: Int, height: Int, max: Int = MAX_SIZE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val factor = max.toFloat() / longest
        return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
    }

    /** Names to delete so that at most [maxFiles] remain; the oldest go first. */
    fun toPrune(entries: List<Entry>, maxFiles: Int = MAX_FILES): List<String> =
        if (entries.size <= maxFiles) emptyList() else entries.sortedBy { it.lastModified }.take(entries.size - maxFiles).map { it.name }

    fun isFresh(lastModified: Long, now: Long): Boolean = lastModified > 0 && now - lastModified < REFRESH_MS
}

/**
 * Favicons the WebView hands over anyway (no network request of our own), kept as small PNGs per host in
 * `files/favicons`. Writes are atomic and happen off the main thread; reads are cached in memory. Private tabs
 * never reach [save] (the caller checks).
 */
class FaviconStore(private val dir: File) {
    private val memory = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 64
    }
    private val _version = MutableStateFlow(0)
    /** Bumped when an icon was saved or the cache was cleared (tiles reload). */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** True when the icon of [host] was saved recently (or [host] cannot be stored): nothing to do. Cheap (one stat). */
    fun isFresh(host: String): Boolean {
        val name = FaviconPolicy.fileName(host) ?: return true
        return FaviconPolicy.isFresh(File(dir, name).lastModified(), System.currentTimeMillis())
    }

    /**
     * Blocking; call on a background dispatcher. Scales [icon] to the stored size and writes it; the caller keeps
     * ownership of [icon] (the scaled temporary is recycled here).
     */
    @Synchronized // one writer: two tabs of the same site would otherwise share the temp file
    fun save(host: String, icon: Bitmap) {
        val name = FaviconPolicy.fileName(host) ?: return
        val file = File(dir, name)
        if (FaviconPolicy.isFresh(file.lastModified(), System.currentTimeMillis())) return
        val (w, h) = FaviconPolicy.scaledSize(icon.width, icon.height)
        if (w <= 0 || h <= 0) return
        val scaled = if (w == icon.width && h == icon.height) icon else Bitmap.createScaledBitmap(icon, w, h, true)
        try {
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            runCatching { writeBytesAtomically(file, bytes) }.onFailure { return }
        } finally {
            if (scaled !== icon) scaled.recycle()
        }
        synchronized(memory) { memory.remove(name) }
        prune()
        _version.value++
    }

    /** The icon if it is already in memory (no disk access, safe on the main thread). */
    fun peek(host: String): ImageBitmap? {
        val name = FaviconPolicy.fileName(host) ?: return null
        synchronized(memory) { return memory[name] }
    }

    /** Blocking; call on a background dispatcher. */
    fun load(host: String): ImageBitmap? {
        val name = FaviconPolicy.fileName(host) ?: return null
        synchronized(memory) { memory[name]?.let { return it } }
        val file = File(dir, name)
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.asImageBitmap() ?: return null
        synchronized(memory) { memory[name] = bitmap }
        return bitmap
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        synchronized(memory) { memory.clear() }
        _version.value++
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        FaviconPolicy.toPrune(files.map { FaviconPolicy.Entry(it.name, it.lastModified()) }).forEach { name ->
            File(dir, name).delete()
            synchronized(memory) { memory.remove(name) } // the in-memory copy goes with the file
        }
    }
}
