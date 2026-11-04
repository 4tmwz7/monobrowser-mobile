package com.voluene.monobrowser.browser

import android.content.Context
import android.webkit.WebView

/**
 * WebView that tells pull-to-refresh when the page itself is pulled past its top. Chromium reports an overscroll
 * only for the root scroller and only for the part of a gesture nobody consumed: scrolling a list inside the page
 * up to its top (gesture latched to that list), or a page with `overscroll-behavior: none`, never reports one.
 * That is what Chrome's own pull-to-refresh is based on.
 */
class MonoWebView(context: Context) : WebView(context) {
    /** The current gesture ran into the top of the page (reset by the pull-to-refresh layout on each touch down). */
    var pulledPastTop = false

    /**
     * True while this tab plays media (or is the floating video): the app going to the background must not hide
     * the page from Chromium, which would stop the playback itself (whatever the page thinks of its visibility).
     */
    var keepPlayingInBackground: () -> Boolean = { false }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != android.view.View.VISIBLE && keepPlayingInBackground()) {
            super.onWindowVisibilityChanged(android.view.View.VISIBLE)
            return
        }
        super.onWindowVisibilityChanged(visibility)
    }

    /** Playback stopped while the app is in the background: let Chromium know the page is hidden after all. */
    fun applyRealVisibility() {
        if (!keepPlayingInBackground()) super.onWindowVisibilityChanged(windowVisibility)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) pulledPastTop = true
    }
}
