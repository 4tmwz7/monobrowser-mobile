package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarScrollTrackerTest {
    private val t = BarScrollTracker(threshold = 20, settleMs = 300)

    @Test fun hidesAfterEnoughDownwardScroll() {
        assertNull(t.onScroll(8, 100, hidden = false, now = 1000))
        assertNull(t.onScroll(8, 108, hidden = false, now = 1010))
        assertEquals(true, t.onScroll(8, 116, hidden = false, now = 1020))
    }

    @Test fun showsAfterUpwardScroll() {
        assertEquals(true, t.onScroll(30, 300, hidden = false, now = 0))
        assertEquals(false, t.onScroll(-25, 275, hidden = true, now = 1000))
    }

    @Test fun eventsRightAfterAToggleAreIgnored() {
        assertEquals(true, t.onScroll(30, 300, hidden = false, now = 1000))
        // layout change caused by the resize fires scroll events in the opposite direction
        assertNull(t.onScroll(-40, 260, hidden = true, now = 1100))
        assertEquals(false, t.onScroll(-40, 220, hidden = true, now = 1400))
    }

    @Test fun reachingTheTopAlwaysShowsTheBar() {
        assertEquals(false, t.onScroll(-5, 0, hidden = true, now = 5))
        assertNull(t.onScroll(-5, 0, hidden = false, now = 6))
    }

    @Test fun jitterDoesNotToggle() {
        var now = 1000L
        repeat(10) {
            assertNull(t.onScroll(6, 200, hidden = false, now = now++))
            assertNull(t.onScroll(-6, 194, hidden = false, now = now++))
        }
    }
}
