package com.voluene.monobrowser.adblock

import android.webkit.WebView

enum class ResourceType { DOCUMENT, SUBDOCUMENT, SCRIPT, STYLESHEET, IMAGE, MEDIA, FONT, XHR, OTHER }

/**
 * Request filter. The real engine is adblock-rust called via JNI ([RustAdBlocker]); it is fast and
 * thread-safe, because it is called from WebView's IO threads.
 */
interface AdBlocker {
    /** [sourceUrl] is the URL of the top-level page the request belongs to (null when unknown). */
    fun shouldBlock(url: String, sourceUrl: String?, resourceType: ResourceType): Boolean

    /** False when blocking is globally off or [host] (page host) is on the per-site exception list. */
    fun isEnabledFor(host: String?): Boolean = true

    /** Hostname-specific cosmetic CSS for [pageUrl], or null when there is none / blocking is off for it. */
    fun cosmeticCss(pageUrl: String): String? = null

    /**
     * Called once for every new WebView: installs the cosmetic-filtering bridge and document-start script.
     * Implementations set [cosmeticAtDocumentStart] when the script is active.
     */
    fun attach(webView: WebView) {}

    /** True when cosmetic filtering is injected at document start by [attach] (no per-navigation injection needed). */
    val cosmeticAtDocumentStart: Boolean get() = false
}

object NoOpAdBlocker : AdBlocker {
    override fun shouldBlock(url: String, sourceUrl: String?, resourceType: ResourceType): Boolean = false
}
