package com.voluene.monobrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryGroupingTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(date: String, time: String): Long =
        java.time.LocalDateTime.parse("${date}T$time").toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun entry(id: Long, date: String, time: String) = HistoryEntry(id, "https://e.example/$id", "t$id", at(date, time))

    @Test fun emptyInputGivesNoGroups() {
        assertEquals(emptyList<HistoryDay>(), groupHistoryByDay(emptyList(), utc))
    }

    @Test fun groupsByDayNewestFirst() {
        val groups = groupHistoryByDay(
            listOf(
                entry(1, "2026-10-30", "09:00:00"),
                entry(2, "2026-11-01", "08:00:00"),
                entry(3, "2026-10-30", "23:59:59"),
                entry(4, "2026-11-01", "20:00:00"),
                entry(5, "2026-10-31", "12:00:00"),
            ),
            utc,
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 30)), groups.map { it.day })
        assertEquals(listOf(4L, 2L), groups[0].entries.map { it.id })
        assertEquals(listOf(3L, 1L), groups[2].entries.map { it.id })
    }

    @Test fun dayBoundaryFollowsTheTimeZone() {
        val e = entry(1, "2026-10-31", "23:30:00")
        assertEquals(LocalDate.of(2026, 10, 31), groupHistoryByDay(listOf(e), utc).single().day)
        assertEquals(LocalDate.of(2026, 11, 1), groupHistoryByDay(listOf(e), ZoneOffset.ofHours(2)).single().day)
    }
}
