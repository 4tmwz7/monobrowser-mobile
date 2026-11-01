package com.voluene.monobrowser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_000_000L * hour

    @Test fun neverCheckedIsDue() = assertTrue(UpdateSchedule.isAutoCheckDue(0, now))

    @Test fun recentCheckIsNotDue() {
        assertFalse(UpdateSchedule.isAutoCheckDue(now - 5 * 60_000, now))
        assertFalse(UpdateSchedule.isAutoCheckDue(now - hour + 1, now))
    }

    @Test fun oldCheckIsDue() {
        assertTrue(UpdateSchedule.isAutoCheckDue(now - hour, now))
        assertTrue(UpdateSchedule.isAutoCheckDue(now - 7 * hour, now))
    }

    @Test fun clockGoingBackwardsIsDue() = assertTrue(UpdateSchedule.isAutoCheckDue(now + hour, now))

    @Test fun periodMatchesDesktop() = assertEquals(6 * hour, UpdateSchedule.PERIOD_MS)

    @Test fun manualChecksOnlySwallowRapidRepeats() {
        assertTrue(UpdateSchedule.isManualCheckAllowed(0, now))
        assertFalse(UpdateSchedule.isManualCheckAllowed(now - 5_000, now))
        assertTrue(UpdateSchedule.isManualCheckAllowed(now - 20_000, now))
    }

    @Test fun firstAutoCheckWaitsForGraceOrRestOfInterval() {
        assertEquals(UpdateSchedule.STARTUP_DELAY_MS, UpdateSchedule.initialDelayMs(0, now))
        assertEquals(UpdateSchedule.STARTUP_DELAY_MS, UpdateSchedule.initialDelayMs(now - 2 * hour, now))
        assertEquals(40 * 60_000L, UpdateSchedule.initialDelayMs(now - 20 * 60_000L, now))
        assertEquals(UpdateSchedule.STARTUP_DELAY_MS, UpdateSchedule.initialDelayMs(now - hour + 1_000, now))
    }
}
