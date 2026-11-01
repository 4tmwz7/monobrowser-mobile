package com.voluene.monobrowser.browser

import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView

/** Serialises `WebView.saveState` bundles (back/forward list, scroll) so sleeping tabs survive process death. */
object WebViewState {
    fun capture(webView: WebView): ByteArray? {
        val bundle = Bundle()
        if (webView.saveState(bundle) == null) return null
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun toBundle(bytes: ByteArray): Bundle? {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(WebViewState::class.java.classLoader)
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }
}
