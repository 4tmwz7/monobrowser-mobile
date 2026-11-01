package com.voluene.monobrowser.adblock

/** A downloadable filter list; [id] doubles as the on-disk file name (`<id>.txt`). */
data class FilterList(val id: String, val url: String)

/**
 * Same lists as the desktop build: @ghostery/adblocker `fullLists` (EasyList, Peter Lowe, uBlock Origin
 * filters/badware/privacy/unbreak/quick-fixes/resource-abuse, EasyPrivacy, EasyList Cookie, uBO annoyances)
 * plus EasyList Polish.
 */
object FilterLists {
    private const val GHOSTERY =
        "https://raw.githubusercontent.com/ghostery/adblocker/master/packages/adblocker/assets"

    /** Bump when the list set / parse options change: invalidates the serialized engine cache. */
    const val SET_VERSION = 1

    val ALL: List<FilterList> = listOf(
        "easylist/easylist",
        "peter-lowe/serverlist",
        "ublock-origin/badware",
        "ublock-origin/filters-2020",
        "ublock-origin/filters-2021",
        "ublock-origin/filters-2022",
        "ublock-origin/filters-2023",
        "ublock-origin/filters-2024",
        "ublock-origin/filters",
        "ublock-origin/quick-fixes",
        "ublock-origin/resource-abuse",
        "ublock-origin/unbreak",
        "easylist/easyprivacy",
        "ublock-origin/privacy",
        "easylist/easylist-cookie",
        "ublock-origin/annoyances-others",
        "ublock-origin/annoyances-cookies",
    ).map { FilterList(it.replace('/', '-'), "$GHOSTERY/$it.txt") } +
        FilterList("easylistpolish", "https://easylist-downloads.adblockplus.org/easylistpolish.txt")
}

/** List refresh timing (desktop: refresh every 4 days, hourly check, retry after 10 minutes). Pure. */
object RefreshPolicy {
    const val REFRESH_INTERVAL_MS = 4L * 24 * 60 * 60 * 1000
    const val CHECK_INTERVAL_MS = 60L * 60 * 1000
    const val RETRY_MS = 10L * 60 * 1000
    private const val MIN_DELAY_MS = 60_000L

    /** [lastBuiltMs] <= 0 means "never built". A timestamp in the future (clock changed) counts as stale. */
    fun isStale(lastBuiltMs: Long, nowMs: Long, intervalMs: Long = REFRESH_INTERVAL_MS): Boolean =
        lastBuiltMs <= 0 || lastBuiltMs > nowMs || nowMs - lastBuiltMs > intervalMs

    /** Delay until the next refresh check. */
    fun nextDelayMs(lastBuiltMs: Long, nowMs: Long, lastAttemptFailed: Boolean): Long {
        if (lastAttemptFailed || lastBuiltMs <= 0) return RETRY_MS
        val untilStale = lastBuiltMs + REFRESH_INTERVAL_MS - nowMs
        return untilStale.coerceIn(MIN_DELAY_MS, CHECK_INTERVAL_MS)
    }
}

/** Retry delays after failed list updates: quick at first (the network is often just not ready yet), then relaxed. */
object RetryBackoff {
    private val STEPS_MS = longArrayOf(5_000, 15_000, 45_000, 2 * 60_000, 5 * 60_000, 10 * 60_000)

    /** [failures] = consecutive failed rounds so far (>= 1). */
    fun delayMs(failures: Int): Long = STEPS_MS[(failures - 1).coerceIn(0, STEPS_MS.lastIndex)]

    /** How long to wait before looking at connectivity again while offline / waiting for Wi-Fi. */
    const val NETWORK_RECHECK_MS = 60_000L

    /** Minimum gap between two update rounds started by connectivity callbacks (they can fire in bursts). */
    const val MIN_CALLBACK_GAP_MS = 3_000L
}
