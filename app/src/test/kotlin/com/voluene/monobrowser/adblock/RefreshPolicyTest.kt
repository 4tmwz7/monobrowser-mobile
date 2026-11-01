package com.voluene.monobrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    @Test fun neverBuiltIsStale() = assertTrue(RefreshPolicy.isStale(0, now))

    @Test fun freshCacheIsNotStale() {
        assertFalse(RefreshPolicy.isStale(now - day, now))
        assertFalse(RefreshPolicy.isStale(now - 4 * day, now))
    }

    @Test fun olderThanFourDaysIsStale() = assertTrue(RefreshPolicy.isStale(now - 4 * day - 1, now))

    @Test fun futureTimestampIsStale() = assertTrue(RefreshPolicy.isStale(now + day, now))

    @Test fun failureRetriesAfterTenMinutes() {
        assertEquals(10 * 60_000L, RefreshPolicy.nextDelayMs(now - day, now, lastAttemptFailed = true))
        assertEquals(10 * 60_000L, RefreshPolicy.nextDelayMs(0, now, lastAttemptFailed = false))
    }

    @Test fun checksHourlyWhileFarFromStale() =
        assertEquals(60 * 60_000L, RefreshPolicy.nextDelayMs(now - day, now, false))

    @Test fun wakesWhenAboutToGoStale() {
        assertEquals(30 * 60_000L, RefreshPolicy.nextDelayMs(now - 4 * day + 30 * 60_000L, now, false))
        // already overdue -> minimum delay, never zero / negative
        assertEquals(60_000L, RefreshPolicy.nextDelayMs(now - 5 * day, now, false))
    }

    @Test fun listSetMatchesDesktop() {
        val ids = FilterLists.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("easylist-easylist", "easylist-easyprivacy", "easylistpolish", "ublock-origin-annoyances-cookies")))
        assertTrue(FilterLists.ALL.all { it.url.startsWith("https://") })
    }
}

class RetryBackoffTest {
    @Test fun growsThenCaps() {
        val delays = (1..8).map { RetryBackoff.delayMs(it) }
        assertEquals(listOf(5_000L, 15_000L, 45_000L, 120_000L, 300_000L, 600_000L, 600_000L, 600_000L), delays)
        assertEquals(delays, delays.sorted())
    }

    @Test fun firstRetryIsQuickBecauseNetworkIsOftenJustNotReady() = assertTrue(RetryBackoff.delayMs(1) <= 10_000L)

    @Test fun zeroOrNegativeFailuresAreTreatedAsFirst() = assertEquals(5_000L, RetryBackoff.delayMs(0))
}
