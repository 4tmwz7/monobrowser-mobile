package com.voluene.monobrowser.browser

/** A tab that currently owns a live WebView. */
data class LiveTab(
    val id: Long,
    /** Last time the tab was shown/used (monotonic ms). */
    val lastUsed: Long,
    val isActive: Boolean,
    /** "Never sleep" flag. Private tabs also count as protected: their state cannot be saved to disk. */
    val protected: Boolean,
)

object SleepPolicy {
    const val DEFAULT_MAX_LIVE = 5

    /**
     * Tabs whose WebView should be destroyed so that at most [maxLive] stay alive:
     * least-recently-used first, never the active or a protected tab. May return fewer than needed
     * when too many tabs are protected.
     */
    fun pickVictims(live: List<LiveTab>, maxLive: Int = DEFAULT_MAX_LIVE): List<Long> {
        val excess = live.size - maxLive.coerceAtLeast(1)
        if (excess <= 0) return emptyList()
        return live.filter { !it.isActive && !it.protected }
            .sortedBy { it.lastUsed }
            .take(excess)
            .map { it.id }
    }
}
