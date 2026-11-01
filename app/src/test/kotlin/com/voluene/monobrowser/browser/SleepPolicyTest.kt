package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepPolicyTest {
    private fun tab(id: Long, lastUsed: Long, active: Boolean = false, protected: Boolean = false) =
        LiveTab(id, lastUsed, active, protected)

    @Test fun nothingToDoUnderTheLimit() {
        assertEquals(emptyList<Long>(), SleepPolicy.pickVictims((1L..5L).map { tab(it, it) }, 5))
    }

    @Test fun leastRecentlyUsedGoFirst() {
        val live = listOf(tab(1, 50), tab(2, 10), tab(3, 30), tab(4, 40), tab(5, 20), tab(6, 60, active = true), tab(7, 5))
        // 7 live, limit 5 -> two victims: oldest are 7 (5) and 2 (10)
        assertEquals(listOf(7L, 2L), SleepPolicy.pickVictims(live, 5))
    }

    @Test fun activeAndProtectedTabsSurvive() {
        val live = listOf(
            tab(1, 1, active = true),
            tab(2, 2, protected = true),
            tab(3, 3),
            tab(4, 4),
        )
        assertEquals(listOf(3L, 4L), SleepPolicy.pickVictims(live, 2))
    }

    @Test fun mayReturnFewerThanNeededWhenEverythingIsProtected() {
        val live = (1L..4L).map { tab(it, it, protected = true) }
        assertEquals(emptyList<Long>(), SleepPolicy.pickVictims(live, 2))
    }

    @Test fun limitIsAtLeastOne() {
        val live = listOf(tab(1, 1, active = true), tab(2, 2))
        assertEquals(listOf(2L), SleepPolicy.pickVictims(live, 0))
    }
}
