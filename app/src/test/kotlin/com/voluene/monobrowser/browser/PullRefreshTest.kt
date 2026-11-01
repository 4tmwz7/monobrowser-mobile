package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRefreshTest {
    @Test fun pullStartsOnlyAtTopWithMostlyVerticalDownwardDrag() {
        assertTrue(PullRefresh.shouldStartPull(dy = 30f, dx = 5f, slop = 8f, atTop = true))
        assertFalse(PullRefresh.shouldStartPull(30f, 5f, 8f, atTop = false))
        assertFalse(PullRefresh.shouldStartPull(dy = 4f, dx = 0f, slop = 8f, atTop = true)) // under the slop
        assertFalse(PullRefresh.shouldStartPull(dy = -30f, dx = 0f, slop = 8f, atTop = true)) // upwards
        assertFalse(PullRefresh.shouldStartPull(dy = 30f, dx = 40f, slop = 8f, atTop = true)) // horizontal swipe
    }

    @Test fun progressIsDampedAndClamped() {
        assertEquals(0f, PullRefresh.progress(0f, 100f), 0f)
        assertEquals(0.5f, PullRefresh.progress(100f, 100f), 0.0001f)
        assertEquals(1f, PullRefresh.progress(1000f, 100f), 0f)
        assertEquals(0f, PullRefresh.progress(-50f, 100f), 0f)
        assertEquals(0f, PullRefresh.progress(50f, 0f), 0f)
    }

    @Test fun refreshOnlyWhenTheLineIsFull() {
        assertFalse(PullRefresh.shouldRefresh(0.99f))
        assertTrue(PullRefresh.shouldRefresh(1f))
    }
}
