package com.voluene.monobrowser.browser

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** UI-level requests from a tab's WebView clients; implemented by [TabManager]. */
interface TabUi {
    /** Camera/microphone/location. [onResult] receives the permissions that ended up granted. */
    fun requestSitePermissions(tabId: Long, origin: String, wanted: List<SitePermission>, onResult: (Set<SitePermission>) -> Unit)

    fun showAlert(tabId: Long, origin: String, message: String, onClose: () -> Unit)
    fun showConfirm(tabId: Long, origin: String, message: String, onResult: (Boolean) -> Unit)
    fun showPrompt(tabId: Long, origin: String, message: String, default: String, onResult: (String?) -> Unit)

    /** `<input type=file>`; returns false when no Activity is available. */
    fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean

    /** Non-web link (mailto:, intent: ...). Returns true when handled (the WebView must not load it). */
    fun openExternalLink(tabId: Long, action: LinkAction, hasGesture: Boolean): Boolean

    fun startDownload(tabId: Long, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?)

    /** Main-frame load failure: the manager replaces the page with an error page. */
    fun onLoadError(tabId: Long, failedUrl: String, kind: ErrorKind)

    /** The error page's retry button. */
    fun retryFailedLoad(tabId: Long)
}
