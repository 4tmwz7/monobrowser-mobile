package com.voluene.monobrowser.browser

import android.net.Uri
import android.webkit.WebChromeClient

/**
 * Things only an Activity can do. WebViews live on the application context (they outlive the Activity),
 * so the Activity registers itself on the [TabManager] and requests are routed through this interface.
 */
interface ActivityHost {
    /** Shows the system file picker for `<input type=file>`; [onResult] gets null when cancelled. */
    fun pickFiles(params: WebChromeClient.FileChooserParams, onResult: (Array<Uri>?) -> Unit)

    /** Requests Android runtime permissions; [onResult] maps each permission to granted/denied. */
    fun requestPermissions(permissions: List<String>, onResult: (Map<String, Boolean>) -> Unit)

    /** System "open document" picker (Storage Access Framework); [onResult] gets null when cancelled. */
    fun openDocument(mimeTypes: Array<String>, onResult: (Uri?) -> Unit)

    /** System "create document" picker; [onResult] gets the new document's Uri or null when cancelled. */
    fun createDocument(suggestedName: String, mimeType: String, onResult: (Uri?) -> Unit)
}
