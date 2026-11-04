package com.voluene.monobrowser.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.data.HistoryDay
import com.voluene.monobrowser.data.HistoryDb
import com.voluene.monobrowser.data.HistoryEntry
import com.voluene.monobrowser.data.groupHistoryByDay
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoDialog
import com.voluene.monobrowser.ui.common.ScreenScaffold
import com.voluene.monobrowser.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(history: HistoryDb, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun change(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { runCatching(block) }
        version++
    }

    LaunchedEffect(query, version) {
        if (query.isNotEmpty()) delay(150) // debounce typing
        entries = withContext(Dispatchers.IO) { runCatching { history.search(query) }.getOrDefault(emptyList()) }
    }
    val groups = remember(entries) { groupHistoryByDay(entries.orEmpty()) }

    ScreenScaffold(
        title = stringResource(R.string.history_title),
        onClose = onClose,
        actions = {
            MonoButton(stringResource(R.string.history_clear_all), { confirmClear = true }, Modifier.padding(end = 8.dp), enabled = !entries.isNullOrEmpty())
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(12.dp).height(40.dp).border(1.dp, scheme.outline, RectangleShape).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onBackground),
                cursorBrush = SolidColor(scheme.onBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.history_search_hint), color = scheme.onBackground.copy(alpha = 0.5f), fontSize = 14.sp)
                    inner()
                },
            )
        }
        if (entries != null && groups.isEmpty()) {
            Text(
                stringResource(if (query.isEmpty()) R.string.history_empty else R.string.history_no_results),
                color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            groups.forEach { group ->
                item(key = "day-${group.day}") { DayHeader(group) }
                items(group.entries, key = { it.id }) { entry ->
                    HistoryRow(entry, onClick = { onOpen(entry.url) }, onDelete = { change { history.delete(entry.id) } })
                }
            }
        }
    }

    if (confirmClear) {
        MonoDialog(onDismiss = { confirmClear = false }) {
            Text(stringResource(R.string.history_clear_title), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.history_clear_message), color = scheme.onBackground)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoButton(stringResource(R.string.dialog_cancel), { confirmClear = false }, Modifier.weight(1f))
                MonoButton(stringResource(R.string.history_clear_all), {
                    confirmClear = false
                    change { history.clear() }
                }, Modifier.weight(1f), inverted = true)
            }
        }
    }
}

@Composable
private fun DayHeader(group: HistoryDay) {
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val label = when (group.day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> group.day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }
    Text(
        label.uppercase(),
        color = scheme.background, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(scheme.onBackground).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val time = remember(entry.visitedAt) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(entry.visitedAt).atZone(ZoneId.systemDefault()))
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.voluene.monobrowser.ui.common.SiteMonogram(entry.url)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Text(
                entry.title.ifBlank { UrlUtils.shortUrlLabel(entry.url) },
                color = scheme.onBackground, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$time  \u00b7  ${UrlUtils.shortUrlLabel(entry.url)}", color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, stringResource(R.string.history_delete), tint = scheme.onBackground)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
}
