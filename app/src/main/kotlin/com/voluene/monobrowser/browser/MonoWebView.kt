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

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) pulledPastTop = true
    }
}
