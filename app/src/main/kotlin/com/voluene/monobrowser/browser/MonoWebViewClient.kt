package com.voluene.monobrowser.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.voluene.monobrowser.adblock.AdBlocker
import com.voluene.monobrowser.adblock.ResourceType
import com.voluene.monobrowser.util.Network
import com.voluene.monobrowser.adblock.CosmeticScript
import com.voluene.monobrowser.adblock.ResourceTypes
import java.io.ByteArrayInputStream

/** State callbacks from a tab's WebView; implemented by [TabManager]. */
interface TabEvents {
    /** A main-frame navigation to [url] is about to start (before it loads): the page theme of its site must already apply. */
    fun onNavigationRequest(tabId: Long, url: String)
    fun onPageStarted(tabId: Long, url: String)
    fun onPageFinished(tabId: Long, url: String)
    fun onNavState(tabId: Long, url: String?, canGoBack: Boolean, canGoForward: Boolean)
    fun onTitle(tabId: Long, title: String)
    fun onProgress(tabId: Long, progress: Int)
    fun onBlocked(tabId: Long)
    fun onScrolled(tabId: Long, dy: Int, scrollY: Int)
    fun onFindResult(tabId: Long, activeOrdinal: Int, matches: Int)
}

class MonoWebViewClient(
    private val tabId: Long,
    private val adBlocker: AdBlocker,
    private val events: TabEvents,
    private val ui: TabUi,
) : WebViewClient() {
    /** shouldInterceptRequest runs off the main thread, where WebView.getUrl() must not be called. */
    @Volatile private var sourceUrl: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (url == ErrorPageHtml.RETRY_URL) {
            ui.retryFailedLoad(tabId)
            return true
        }
        val action = ExternalLinks.classify(url)
        if (request.isForMainFrame && action == LinkAction.Load) events.onNavigationRequest(tabId, url)
        if (action == LinkAction.Load) {
            // Only taps on links (not redirects, not form posts): drop click trackers from the address.
            return request.isForMainFrame && request.hasGesture() && !request.isRedirect && request.method.equals("GET", true) &&
                ui.cleanNavigation(tabId, url)
        }
        // Frames inside a page never get to start apps.
        if (!request.isForMainFrame) return true
        return ui.openExternalLink(tabId, action, request.hasGesture() || request.isRedirect)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val type = ResourceTypes.guess(request.isForMainFrame, request.url.path, request.requestHeaders.orEmpty())
        if (!request.isForMainFrame && adBlocker.shouldBlock(url, sourceUrl, type)) {
            events.onBlocked(tabId)
            return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        sourceUrl = url
        injectCosmetic(view, url) // adblock: cosmetic
        events.onPageStarted(tabId, url)
        events.onNavState(tabId, url, view.canGoBack(), view.canGoForward())
    }

    // adblock: cosmetic (fallback when the document-start script is unavailable; otherwise a no-op)
    override fun onPageCommitVisible(view: WebView, url: String) {
        injectCosmetic(view, url)
    }

    // adblock: cosmetic
    private fun injectCosmetic(view: WebView, url: String) {
        if (adBlocker.cosmeticAtDocumentStart) return
        adBlocker.cosmeticCss(url)?.let { view.evaluateJavascript(CosmeticScript.inject(it), null) }
    }

    override fun onPageFinished(view: WebView, url: String) {
        events.onPageFinished(tabId, url)
        events.onNavState(tabId, url, view.canGoBack(), view.canGoForward())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        sourceUrl = url
        events.onNavigationRequest(tabId, url) // history navigations (back/forward, bfcache) may not report a page start
        events.onNavState(tabId, url, view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        // Navigations replaced by a download or an external app are reported as aborted; not an error.
        if (error.description?.contains("ERR_ABORTED") == true) return
        val kind = ErrorKind.fromWebViewCode(error.errorCode, Network.isOnline(view.context))
        ui.onLoadError(tabId, request.url.toString(), kind)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel() // No "proceed anyway" for now (desktop has a per-host exception interstitial; TODO).
        if (error.url == sourceUrl || error.url == view.url) ui.onLoadError(tabId, error.url, ErrorKind.CERT)
    }
}

class MonoWebChromeClient(
    private val tabId: Long,
    private val events: TabEvents,
    private val ui: TabUi,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) = events.onProgress(tabId, newProgress)

    override fun onReceivedTitle(view: WebView, title: String?) = events.onTitle(tabId, title.orEmpty())

    /** Camera/microphone are prompted per origin; every other resource (DRM, MIDI) stays denied. */
    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = SitePermissionStore.originOf(request.origin.toString())
        val mapped = request.resources.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermission.CAMERA to it
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermission.MICROPHONE to it
                else -> null
            }
        }
        if (origin == null || mapped.isEmpty()) {
            request.deny()
            return
        }
        ui.requestSitePermissions(tabId, origin, mapped.map { it.first }) { granted ->
            val resources = mapped.filter { it.first in granted }.map { it.second }
            if (resources.isEmpty()) request.deny() else request.grant(resources.toTypedArray())
        }
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        val site = SitePermissionStore.originOf(origin)
        if (site == null) {
            callback.invoke(origin, false, false)
            return
        }
        ui.requestSitePermissions(tabId, site, listOf(SitePermission.LOCATION)) { granted ->
            callback.invoke(origin, SitePermission.LOCATION in granted, false)
        }
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        ui.showAlert(tabId, originLabel(url), message) { result.confirm() }
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        ui.showConfirm(tabId, originLabel(url), message) { if (it) result.confirm() else result.cancel() }
        return true
    }

    override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
        ui.showPrompt(tabId, originLabel(url), message, defaultValue.orEmpty()) { if (it != null) result.confirm(it) else result.cancel() }
        return true
    }

    override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        if (!ui.chooseFiles(filePathCallback, params)) filePathCallback.onReceiveValue(null)
        return true
    }

    override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) = ui.showCustomView(tabId, view, callback)

    override fun onHideCustomView() = ui.hideCustomView()

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
        ui.createWindow(tabId, isUserGesture, resultMsg)

    override fun onCloseWindow(window: WebView) = ui.closeWindow(tabId)

    private fun originLabel(url: String): String =
        SitePermissionStore.originOf(url)?.let(SitePermissionStore::hostOf) ?: url
}
