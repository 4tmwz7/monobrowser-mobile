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

    /** The user tapped the page (finger lifted close to where it went down): starts a user-activation window. */
    var onUserTap: () -> Unit = {}
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            android.view.MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop) onUserTap()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER) onUserTap()
        return super.dispatchKeyEvent(event)
    }

    /** When the user last asked to play (notification, Now Bar, lock screen): it starts before the page reports it. */
    private var playRequestedAt = 0L

    private fun keepVisible(): Boolean =
        keepPlayingInBackground() || android.os.SystemClock.elapsedRealtime() - playRequestedAt < PLAY_GRACE_MS

    /**
     * Play was pressed outside the app (e.g. after the floating window was closed): Chromium must see the page as
     * visible before playback starts, or it pauses it again right away.
     */
    fun onPlayRequested() {
        playRequestedAt = android.os.SystemClock.elapsedRealtime()
        super.onWindowVisibilityChanged(android.view.View.VISIBLE)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != android.view.View.VISIBLE && keepVisible()) {
            super.onWindowVisibilityChanged(android.view.View.VISIBLE)
            return
        }
        super.onWindowVisibilityChanged(visibility)
    }

    /** Playback stopped while the app is in the background: let Chromium know the page is hidden after all. */
    fun applyRealVisibility() {
        if (!keepVisible()) super.onWindowVisibilityChanged(windowVisibility)
    }

    private companion object {
        const val PLAY_GRACE_MS = 5_000L
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) pulledPastTop = true
    }
}
