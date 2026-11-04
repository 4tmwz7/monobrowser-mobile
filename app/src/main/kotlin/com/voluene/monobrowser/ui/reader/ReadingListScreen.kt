package com.voluene.monobrowser.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.reader.SavedArticleInfo
import com.voluene.monobrowser.ui.common.HairLine
import com.voluene.monobrowser.ui.common.ScreenScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Articles saved from reader view; they open offline. */
@Composable
fun ReadingListScreen(items: List<SavedArticleInfo>, onOpen: (SavedArticleInfo) -> Unit, onRemove: (SavedArticleInfo) -> Unit, onClose: () -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    ScreenScaffold(stringResource(R.string.reading_list), onClose) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.reading_empty), color = fg.copy(alpha = 0.7f), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(20.dp))
            return@ScreenScaffold
        }
        val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title.ifBlank { item.url }, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(item.site, stringResource(R.string.reader_minutes, item.minutes), dates.format(Instant.ofEpochMilli(item.savedAt)))
                                .filter { it.isNotBlank() }.joinToString("  ·  "),
                            color = fg.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    IconButton(onClick = { onRemove(item) }) { Icon(Icons.Filled.Delete, stringResource(R.string.reading_remove), tint = fg) }
                }
                HairLine()
            }
        }
    }
}
