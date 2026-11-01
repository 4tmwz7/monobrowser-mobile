package com.voluene.monobrowser.update

/**
 * When to ask GitHub. Desktop parity: check right after start and every [PERIOD_MS] while running;
 * additionally never more often than [MIN_AUTO_INTERVAL_MS] (unauthenticated API: 60 requests/hour per IP).
 */
object UpdateSchedule {
    const val STARTUP_DELAY_MS = 4_000L
    const val PERIOD_MS = 6L * 60 * 60 * 1000
    const val MIN_AUTO_INTERVAL_MS = 60L * 60 * 1000
    const val MIN_MANUAL_INTERVAL_MS = 15_000L

    /** An automatic check is due when none ran yet, the clock went backwards, or the last one is older than the minimum. */
    fun isAutoCheckDue(lastCheckMs: Long, nowMs: Long, minIntervalMs: Long = MIN_AUTO_INTERVAL_MS): Boolean =
        lastCheckMs <= 0 || lastCheckMs > nowMs || nowMs - lastCheckMs >= minIntervalMs

    /** Manual checks are always welcome; only rapid repeats are swallowed. */
    fun isManualCheckAllowed(lastCheckMs: Long, nowMs: Long): Boolean =
        isAutoCheckDue(lastCheckMs, nowMs, MIN_MANUAL_INTERVAL_MS)

    /** Delay before the first automatic check: wait out the rest of the minimum interval, never less than the startup grace. */
    fun initialDelayMs(lastCheckMs: Long, nowMs: Long): Long =
        if (isAutoCheckDue(lastCheckMs, nowMs)) STARTUP_DELAY_MS
        else maxOf(STARTUP_DELAY_MS, MIN_AUTO_INTERVAL_MS - (nowMs - lastCheckMs))
}
