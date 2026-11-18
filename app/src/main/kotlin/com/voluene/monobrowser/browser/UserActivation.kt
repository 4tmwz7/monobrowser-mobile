package com.voluene.monobrowser.browser

/**
 * Short-lived "the user just touched this page" mark of one tab (like Chromium's transient user activation).
 * Some sites (Reddit's "Open app" interstitial) start the app from a timer a second after the tap, so that
 * navigation no longer carries a gesture. [now] is a monotonic clock in ms, passed in so the logic is testable.
 * The mark is consumed by the first thing it allows (dialog, launch, fallback): a script cannot chain requests.
 */
class UserActivation {
    private var at = NONE

    /** The user tapped the page, or the tab was just opened by a user action. */
    fun touch(now: Long) {
        at = now
    }

    fun isActive(now: Long): Boolean = at != NONE && now >= at && now - at < WINDOW_MS

    /** Spends the mark: it is not active again until the next [touch]. */
    fun consume() {
        at = NONE
    }

    companion object {
        const val WINDOW_MS = 5_000L
        private const val NONE = Long.MIN_VALUE
    }
}
