package com.voluene.monobrowser.adblock

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * [AdBlocker] backed by adblock-rust (JNI). Thread-safe: [shouldBlock] runs on WebView IO threads.
 * Returns "allow" until the first engine is ready (cache load or list download), so app start never blocks.
 */
class RustAdBlocker internal constructor(
    dir: File,
    gate: NetworkGate,
    wifiOnlyUpdates: Boolean = false,
    enabled: Boolean = true,
    /** Per-site allowlist; toggle from UI via `exceptions.toggle(host)`. */
    val exceptions: AdblockExceptions = AdblockExceptions(File(dir, "exceptions.json")),
) : AdBlocker {
    @Volatile private var engine: NativeEngine? = null

    /** Global switch (desktop: "adblockEnabled"); persisted by the settings store, mirrored here by `TabManager`. */
    @Volatile var enabled: Boolean = enabled

    @Volatile private var documentStartScript = false
    private val blocked = AtomicLong()
    private val manager = FilterListManager(dir, gate = gate) { fresh ->
        val old = engine
        engine = fresh
        old?.close()
    }.also { it.wifiOnly = wifiOnlyUpdates }

    /** Total requests blocked since process start. */
    val blockedTotal: Long get() = blocked.get()

    val ready: Boolean get() = engine != null

    /** IDLE, LOADING, READY or ERROR (see FilterListManager.State). */
    val status: String get() = manager.state.name

    /** Update progress for the settings screen. */
    val listsStatus get() = manager.status

    var wifiOnlyUpdates: Boolean
        get() = manager.wifiOnly
        set(value) {
            if (manager.wifiOnly != value) {
                manager.wifiOnly = value
                manager.settingsChanged()
            }
        }

    /** Downloads all lists now (ignores staleness and the Wi-Fi-only switch). */
    fun updateListsNow() = manager.updateNow()

    internal fun start() = manager.start()

    override fun shouldBlock(url: String, sourceUrl: String?, resourceType: ResourceType): Boolean {
        if (!enabled) return false
        val e = engine ?: return false
        if (!url.startsWith("http", ignoreCase = true) && !url.startsWith("ws", ignoreCase = true)) return false
        if (exceptions.contains(sourceUrl)) return false
        val hit = try {
            e.check(url, sourceUrl.orEmpty(), ResourceTypes.engineName(resourceType))
        } catch (t: Throwable) {
            Log.e(NativeAdblock.TAG, "check failed", t)
            false
        }
        // Debug builds only: in release this ran (string building + a logcat write) for every blocked request.
        if (hit && com.voluene.monobrowser.BuildConfig.DEBUG) Log.d(NativeAdblock.TAG, "blocked #${blocked.incrementAndGet()} [${resourceType.name}] $url")
        return hit
    }

    override fun isEnabledFor(host: String?): Boolean = enabled && !exceptions.contains(host)

    override fun cosmeticCss(pageUrl: String): String? {
        if (!isEnabledFor(HostUtils.hostOfUrl(pageUrl))) return null
        val e = engine ?: return null
        return try {
            e.cosmeticCss(pageUrl).ifEmpty { null }
        } catch (t: Throwable) {
            Log.e(NativeAdblock.TAG, "cosmetic failed", t)
            null
        }
    }

    override val cosmeticAtDocumentStart: Boolean get() = documentStartScript

    override fun attach(webView: WebView) {
        if (!NativeAdblock.available) return
        webView.addJavascriptInterface(Bridge(), CosmeticScript.BRIDGE_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, CosmeticScript.DOCUMENT_START, setOf("*"))
            documentStartScript = true
        }
    }

    /** Exposed to page JS as `window.MonoAdblock`; pure functions of the page URL, no side effects. */
    private inner class Bridge {
        @JavascriptInterface
        fun cosmeticCss(pageUrl: String?): String {
            val css = pageUrl?.let { this@RustAdBlocker.cosmeticCss(it) }.orEmpty()
            if (css.isNotEmpty()) Log.d(NativeAdblock.TAG, "cosmetic css ${css.length} chars for $pageUrl")
            return css
        }

        @JavascriptInterface
        fun genericCss(pageUrl: String?, classes: String?, ids: String?): String {
            if (pageUrl == null || !isEnabledFor(HostUtils.hostOfUrl(pageUrl))) return ""
            val e = engine ?: return ""
            return try {
                e.genericCss(pageUrl, classes.orEmpty().take(MAX_BRIDGE_ARG), ids.orEmpty().take(MAX_BRIDGE_ARG)).also {
                    if (it.isNotEmpty()) Log.d(NativeAdblock.TAG, "generic css ${it.length} chars for $pageUrl")
                }
            } catch (t: Throwable) {
                Log.e(NativeAdblock.TAG, "generic cosmetic failed", t)
                ""
            }
        }
    }

    private companion object {
        const val MAX_BRIDGE_ARG = 200_000
    }
}

/** Process-wide [RustAdBlocker] (one engine shared by all tabs). */
object Adblock {
    @Volatile private var instance: RustAdBlocker? = null

    /** The initial values only matter for the first call (they must be known before the list updater starts). */
    fun get(context: Context, wifiOnlyUpdates: Boolean = false, enabled: Boolean = true): RustAdBlocker =
        instance ?: synchronized(this) {
            instance ?: RustAdBlocker(
                File(context.applicationContext.filesDir, "adblock"), AndroidNetworkGate(context), wifiOnlyUpdates, enabled,
            ).also {
                instance = it
                it.start()
            }
        }
}
