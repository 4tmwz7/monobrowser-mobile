package com.voluene.monobrowser.browser

import kotlin.math.abs

/**
 * Decides when the bottom bar hides/shows from WebView scroll deltas.
 * Hiding the bar resizes the page, which can itself produce scroll events; those are ignored for [settleMs].
 */
class BarScrollTracker(
    private val threshold: Int = 24,
    private val settleMs: Long = 350,
) {
    private var accumulated = 0
    private var lastToggle = Long.MIN_VALUE / 2

    /** Returns the new "hidden" state when it changes, otherwise null. */
    fun onScroll(dy: Int, scrollY: Int, hidden: Boolean, now: Long): Boolean? {
        if (scrollY <= 0) {
            accumulated = 0
            return if (hidden) toggled(now, false) else null
        }
        if (now - lastToggle < settleMs) return null
        // A reversal resets the accumulated distance, so jitter does not flip the bar.
        if (accumulated != 0 && (accumulated > 0) != (dy > 0)) accumulated = 0
        accumulated += dy
        return when {
            accumulated >= threshold && !hidden -> toggled(now, true)
            accumulated <= -threshold && hidden -> toggled(now, false)
            abs(accumulated) >= threshold -> { accumulated = 0; null }
            else -> null
        }
    }

    fun reset() { accumulated = 0 }

    private fun toggled(now: Long, hidden: Boolean): Boolean {
        accumulated = 0
        lastToggle = now
        return hidden
    }
}
