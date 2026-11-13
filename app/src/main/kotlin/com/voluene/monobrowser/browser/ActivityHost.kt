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

    /** System photo picker (no storage permission needed); [onResult] gets the picked image's Uri or null when cancelled. */
    fun pickImage(onResult: (Uri?) -> Unit)

    /** System "create document" picker; [onResult] gets the new document's Uri or null when cancelled. */
    fun createDocument(suggestedName: String, mimeType: String, onResult: (Uri?) -> Unit)

    /**
     * Copies the window area [source] (window coordinates) into [target] (scaled to its size), asynchronously from the
     * GPU (PixelCopy): nothing is redrawn on the main thread. [onResult] runs on the main thread, false on failure.
     */
    fun copyFromScreen(source: android.graphics.Rect, target: android.graphics.Bitmap, onResult: (Boolean) -> Unit)

    /** Shrinks the app into a floating window showing the playing video; false when not possible. */
    fun enterPictureInPicture(): Boolean = false
}
