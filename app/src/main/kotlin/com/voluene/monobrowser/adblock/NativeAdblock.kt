package com.voluene.monobrowser.adblock

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Raw JNI bindings to libmonoadblock.so (native/adblock). Prefer [NativeEngine]. */
internal object NativeAdblock {
    /** False when the library is missing (e.g. built with -Padblock.skipNative=true) or failed to load. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("monoadblock")
            nativeInit()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "native adblock engine unavailable", t)
            false
        }
    }

    @JvmStatic external fun nativeInit()

    /** [paths]: newline-separated list files. Returns 0 if none could be read. */
    @JvmStatic external fun nativeCreateFromFiles(paths: String): Long

    /** Returns 0 when the cache is missing, corrupt or from another engine version. */
    @JvmStatic external fun nativeLoad(path: String): Long

    @JvmStatic external fun nativeSave(handle: Long, path: String): Boolean

    @JvmStatic external fun nativeCheck(handle: Long, url: String, sourceUrl: String, requestType: String): Boolean

    @JvmStatic external fun nativeCosmeticCss(handle: Long, pageUrl: String): String

    /** [classes] / [ids]: newline-separated. */
    @JvmStatic external fun nativeGenericCss(handle: Long, pageUrl: String, classes: String, ids: String): String

    @JvmStatic external fun nativeFree(handle: Long)

    const val TAG = "MonoAdblock"
}

/**
 * Owns one native engine handle. All calls take the read lock; [close] takes the write lock, so the
 * handle is never freed while a WebView thread is inside [check].
 */
internal class NativeEngine private constructor(private var handle: Long) {
    private val lock = ReentrantReadWriteLock()

    fun check(url: String, sourceUrl: String, requestType: String): Boolean = lock.read {
        handle != 0L && NativeAdblock.nativeCheck(handle, url, sourceUrl, requestType)
    }

    fun cosmeticCss(pageUrl: String): String = lock.read {
        if (handle == 0L) "" else NativeAdblock.nativeCosmeticCss(handle, pageUrl)
    }

    fun genericCss(pageUrl: String, classes: String, ids: String): String = lock.read {
        if (handle == 0L) "" else NativeAdblock.nativeGenericCss(handle, pageUrl, classes, ids)
    }

    fun save(path: String): Boolean = lock.read { handle != 0L && NativeAdblock.nativeSave(handle, path) }

    fun close() = lock.write {
        if (handle != 0L) NativeAdblock.nativeFree(handle)
        handle = 0L
    }

    companion object {
        fun fromFiles(paths: List<String>): NativeEngine? = wrap(NativeAdblock.nativeCreateFromFiles(paths.joinToString("\n")))

        fun load(path: String): NativeEngine? = wrap(NativeAdblock.nativeLoad(path))

        private fun wrap(handle: Long) = if (handle == 0L) null else NativeEngine(handle)
    }
}
