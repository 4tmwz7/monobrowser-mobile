package com.voluene.monobrowser.ui.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.data.ClosedTab
import com.voluene.monobrowser.data.TopSite
import com.voluene.monobrowser.data.TopSites
import com.voluene.monobrowser.util.UrlUtils
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What a blank tab shows instead of an empty page: a clock, a search field (opens the command palette), the most
 * visited sites as monogram tiles (long-press for options) and recently closed tabs. Private tabs get an inverted
 * page that explains what is (not) kept.
 */
@Composable
fun StartPage(
    isPrivate: Boolean,
    topSites: List<TopSite>?,
    showTopSites: Boolean,
    recentlyClosed: List<ClosedTab>,
    readingList: List<com.voluene.monobrowser.reader.SavedArticleInfo> = emptyList(),
    onOpenSaved: (com.voluene.monobrowser.reader.SavedArticleInfo) -> Unit = {},
    onShowReadingList: () -> Unit = {},
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onSiteOptions: (TopSite) -> Unit,
    onReopen: (ClosedTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (isPrivate) scheme.onBackground else scheme.background
    val fg = if (isPrivate) scheme.background else scheme.onBackground
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val columns = if (wide) 6 else 4

    Box(modifier.fillMaxSize().background(bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
        ) {
            Clock(fg)
            Spacer(Modifier.height(24.dp))
            SearchField(fg, onSearch)

            if (isPrivate) {
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.start_private_title).uppercase(), color = fg, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    stringResource(R.string.start_private_text), color = fg.copy(alpha = 0.8f), fontSize = 15.sp,
                    lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp),
                )
                return@Column
            }

            if (showTopSites && topSites != null) {
                SectionTitle(stringResource(R.string.start_top_sites), fg)
                if (topSites.isEmpty()) {
                    Text(stringResource(R.string.start_empty), color = fg.copy(alpha = 0.6f), fontSize = 14.sp)
                } else {
                    TileGrid(topSites, columns, fg, bg, onOpen, onSiteOptions)
                }
            }

            if (readingList.isNotEmpty()) {
                SectionTitle(stringResource(R.string.reading_list), fg)
                Column(Modifier.fillMaxWidth().border(1.dp, fg.copy(alpha = 0.25f), RectangleShape)) {
                    readingList.take(MAX_RECENT).forEachIndexed { index, item ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.12f)))
                        Column(Modifier.fillMaxWidth().clickable { onOpenSaved(item) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(item.title.ifBlank { item.url }, color = fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${item.site}  \u00b7  ${stringResource(R.string.reader_minutes, item.minutes)}",
                                color = fg.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (readingList.size > MAX_RECENT) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.12f)))
                        Text(
                            stringResource(R.string.reading_show_all, readingList.size), color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onShowReadingList).padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            if (recentlyClosed.isNotEmpty()) {
                SectionTitle(stringResource(R.string.start_recently_closed), fg)
                Column(Modifier.fillMaxWidth().border(1.dp, fg.copy(alpha = 0.25f), RectangleShape)) {
                    recentlyClosed.take(MAX_RECENT).forEachIndexed { index, closed ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.12f)))
                        Column(
                            Modifier.fillMaxWidth().clickable { onReopen(closed) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            val label = UrlUtils.shortUrlLabel(closed.url)
                            Text(
                                closed.title.ifBlank { label }, color = fg, fontSize = 15.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(label, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_RECENT = 3

@Composable
private fun Clock(fg: Color) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake up right after the next minute boundary.
            delay(60_000L - now % 60_000L + 50L)
            now = System.currentTimeMillis()
        }
    }
    val locale = Locale.getDefault()
    val zoned = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val time = remember(now, locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(zoned) }
    val date = remember(now, locale) { DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(zoned) }
    Text(time, color = fg, fontSize = 64.sp, fontWeight = FontWeight.Light, lineHeight = 68.sp, letterSpacing = (-1).sp)
    Text(date.replaceFirstChar { it.titlecase(locale) }, color = fg.copy(alpha = 0.65f), fontSize = 16.sp)
}

@Composable
private fun SearchField(fg: Color, onSearch: () -> Unit) {
    val hint = stringResource(R.string.address_hint)
    Row(
        Modifier.fillMaxWidth().height(52.dp).border(2.dp, fg, RectangleShape)
            .clickable(onClickLabel = hint, role = Role.Button, onClick = onSearch).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Text(hint, color = fg.copy(alpha = 0.55f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SectionTitle(text: String, fg: Color) {
    Text(
        text.uppercase(), color = fg.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileGrid(
    sites: List<TopSite>,
    columns: Int,
    fg: Color,
    bg: Color,
    onOpen: (String) -> Unit,
    onOptions: (TopSite) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        sites.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until columns) {
                    val site = row.getOrNull(i)
                    if (site == null) {
                        Box(Modifier.weight(1f))
                        continue
                    }
                    val label = TopSites.label(site.host)
                    Column(
                        Modifier.weight(1f).combinedClickable(
                            onClickLabel = site.title.ifBlank { site.host },
                            onLongClick = { onOptions(site) },
                            onClick = { onOpen(site.url) },
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).background(bg).border(1.dp, fg, RectangleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                monogram(label), color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            label, color = fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One or two letters for a tile: `github` -> `G`, `my-site` -> `MS`. */
internal fun monogram(label: String): String {
    val parts = label.split('-', '_', ' ').filter { it.isNotEmpty() }
    val letters = if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}" else label.take(1)
    return letters.uppercase().ifEmpty { "?" }
}
