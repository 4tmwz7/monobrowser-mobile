package com.voluene.monobrowser.browser

import kotlin.math.abs

/** Pure maths of the pull-to-refresh gesture (the view only feeds it touch deltas). */
object PullRefresh {
    /** The line grows at half the finger speed, so a pull needs a deliberate drag. */
    private const val RESISTANCE = 0.5f

    /** A pull starts when the page is at the top and the finger moves down, mostly vertically, past the slop. */
    fun shouldStartPull(dy: Float, dx: Float, slop: Float, atTop: Boolean): Boolean =
        atTop && dy > slop && dy > abs(dx)

    /** 0..1 progress of the indicator for a drag of [distance] pixels; [threshold] is the full length. */
    fun progress(distance: Float, threshold: Float): Float =
        if (threshold <= 0f) 0f else (distance * RESISTANCE / threshold).coerceIn(0f, 1f)

    /** The page refreshes when the finger is released with a full line. */
    fun shouldRefresh(progress: Float): Boolean = progress >= 1f
}
