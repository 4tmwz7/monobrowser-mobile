package com.voluene.monobrowser.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistoryEntry(val id: Long, val url: String, val title: String, val visitedAt: Long)

data class HistoryDay(val day: LocalDate, val entries: List<HistoryEntry>)

/** Groups entries by local calendar day, newest day first and newest entry first within a day. */
fun groupHistoryByDay(entries: List<HistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<HistoryDay> =
    entries.sortedByDescending { it.visitedAt }
        .groupBy { Instant.ofEpochMilli(it.visitedAt).atZone(zone).toLocalDate() }
        .map { (day, list) -> HistoryDay(day, list) }
        .sortedByDescending { it.day }
