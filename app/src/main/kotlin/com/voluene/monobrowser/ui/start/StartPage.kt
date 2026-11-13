package com.voluene.monobrowser.ui.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.voluene.monobrowser.browser.FaviconStore
import com.voluene.monobrowser.data.Bookmark
import com.voluene.monobrowser.data.BookmarkFolder
import com.voluene.monobrowser.data.StartContent
import com.voluene.monobrowser.ui.browser.MenuIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.layout.statusBarsPadding
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
    /** Bookmarks the user put on the start page; null = not shown (setting off). */
    bookmarks: StartBookmarks? = null,
    onBookmarkOptions: (Bookmark) -> Unit = {},
    onFolderOptions: (BookmarkFolder) -> Unit = {},
    onOpenFolder: (BookmarkFolder) -> Unit = {},
    onPickBookmarks: () -> Unit = {},
    icons: StartIcons = StartIcons(),
    background: StartBackgroundImage? = null,
    /** The page reaches under the status bar (a picture is drawn there): the content keeps clear of it itself. */
    underStatusBar: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (isPrivate) scheme.onBackground else scheme.background
    val fg = if (isPrivate) scheme.background else scheme.onBackground
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val columns = if (wide) 6 else 4

    Box(modifier.fillMaxSize().background(bg), contentAlignment = Alignment.TopCenter) {
        // The user's own picture (never on private tabs): in the theme's colours when greyscale, dimmed by a bg-coloured veil.
        if (background != null && !isPrivate) {
            Image(
                background.image, contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = if (background.grayscale) GreyScale else null, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(bg.copy(alpha = background.dim)))
        }
        Column(
            Modifier.then(if (underStatusBar) Modifier.statusBarsPadding() else Modifier).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
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

            if (bookmarks != null) {
                val content = bookmarks.content
                if (content.isEmpty) {
                    if (bookmarks.anyBookmarks) {
                        Text(
                            stringResource(R.string.start_pick_bookmarks), color = fg.copy(alpha = 0.7f), fontSize = 13.sp,
                            modifier = Modifier.padding(top = 24.dp).fillMaxWidth().border(1.dp, fg.copy(alpha = 0.25f), RectangleShape)
                                .background(bg.copy(alpha = 0.6f)).clickable(role = Role.Button, onClick = onPickBookmarks).padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    val editLabel = stringResource(R.string.start_bookmarks_edit)
                    if (content.bookmarks.isNotEmpty()) {
                        SectionTitle(stringResource(R.string.start_bookmarks), fg, action = editLabel, onAction = onPickBookmarks)
                        TileGrid(
                            content.bookmarks.map { b -> bookmarkTile(b, onOpen) { onBookmarkOptions(b) } },
                            columns, fg, bg, icons,
                        )
                    }
                    content.folders.forEachIndexed { index, section ->
                        // Only folders chosen: no empty "Bookmarks" header, "Edit" sits with the first folder.
                        val first = content.bookmarks.isEmpty() && index == 0
                        SectionTitle(
                            section.folder.name, fg, action = if (first) editLabel else null, onAction = onPickBookmarks,
                            onLongClick = { onFolderOptions(section.folder) }, topPadding = if (first) 32.dp else 24.dp,
                        )
                        if (section.bookmarks.isEmpty() && section.subfolders.isEmpty()) {
                            Text(stringResource(R.string.start_folder_empty), color = fg.copy(alpha = 0.6f), fontSize = 14.sp)
                        } else {
                            TileGrid(
                                section.subfolders.map { f -> StartTile("f${f.id}", f.name, null, TileKind.FOLDER, { onOpenFolder(f) }, { onFolderOptions(f) }) } +
                                    section.bookmarks.map { b -> bookmarkTile(b, onOpen) { onBookmarkOptions(b) } },
                                columns, fg, bg, icons,
                            )
                        }
                    }
                }
            }

            if (showTopSites && topSites != null) {
                SectionTitle(stringResource(R.string.start_top_sites), fg)
                if (topSites.isEmpty()) {
                    Text(stringResource(R.string.start_empty), color = fg.copy(alpha = 0.6f), fontSize = 14.sp)
                } else {
                    TileGrid(
                        topSites.map { site ->
                            StartTile(
                                "s${site.host}", TopSites.label(site.host), site.host, if (site.pinned) TileKind.PINNED else TileKind.SITE,
                                { onOpen(site.url) }, { onSiteOptions(site) }, description = site.title.ifBlank { site.host },
                            )
                        },
                        columns, fg, bg, icons,
                    )
                }
            }

            if (readingList.isNotEmpty()) {
                SectionTitle(stringResource(R.string.reading_list), fg)
                Column(Modifier.fillMaxWidth().border(1.dp, fg.copy(alpha = 0.25f), RectangleShape).background(bg.copy(alpha = 0.7f))) {
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
                Column(Modifier.fillMaxWidth().border(1.dp, fg.copy(alpha = 0.25f), RectangleShape).background(bg.copy(alpha = 0.7f))) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionTitle(
    text: String, fg: Color, action: String? = null, onAction: () -> Unit = {}, onLongClick: (() -> Unit)? = null, topPadding: androidx.compose.ui.unit.Dp = 32.dp,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = topPadding, bottom = 12.dp)
            .then(if (onLongClick != null) Modifier.combinedClickable(onLongClick = onLongClick, onClick = {}) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(), color = fg.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action.uppercase(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1,
                modifier = Modifier.padding(start = 12.dp).clickable(role = Role.Button, onClick = onAction).padding(vertical = 4.dp),
            )
        }
    }
}

/** What a tile stands for: a site (top sites), a pinned site, a bookmark or a folder. */
internal enum class TileKind { SITE, PINNED, BOOKMARK, FOLDER }

/** One tile of the start page: the same look for top sites, pinned sites, bookmarks and folders. */
internal data class StartTile(
    val key: String,
    val label: String,
    /** Host key of the favicon (null = none, e.g. folders). */
    val host: String?,
    val kind: TileKind,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    val description: String = label,
)

private fun bookmarkTile(b: Bookmark, onOpen: (String) -> Unit, onOptions: () -> Unit): StartTile {
    val host = TopSites.hostKey(b.url)
    val label = b.title.ifBlank { host?.let(TopSites::label) ?: UrlUtils.shortUrlLabel(b.url) }
    return StartTile("b${b.id}", label, host, TileKind.BOOKMARK, { onOpen(b.url) }, onOptions)
}

/** Bookmarks for the start page: the chosen content, and whether any bookmark exists (for the "choose" hint). */
data class StartBookmarks(val content: StartContent, val anyBookmarks: Boolean)

/** How tiles draw site icons: [store] null or [show] false = letters only. */
class StartIcons(val store: FaviconStore? = null, val show: Boolean = false, val grayscale: Boolean = true)

/** The user's start page picture, decoded once; [dim] is the opacity of the veil in the theme's background colour. */
class StartBackgroundImage(val image: ImageBitmap, val dim: Float, val grayscale: Boolean)

private val GreyScale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileGrid(tiles: List<StartTile>, columns: Int, fg: Color, bg: Color, icons: StartIcons) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until columns) {
                    val tile = row.getOrNull(i)
                    if (tile == null) {
                        Box(Modifier.weight(1f))
                        continue
                    }
                    Column(
                        Modifier.weight(1f).combinedClickable(onClickLabel = tile.description, onLongClick = tile.onLongClick, onClick = tile.onClick),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val filled = tile.kind == TileKind.PINNED
                        // A constant square: the icon appears in it without moving anything (letters meanwhile).
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).background(if (filled) fg else bg).border(1.dp, fg, RectangleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            val icon = if (tile.host != null && icons.show) rememberFavicon(icons.store, tile.host) else null
                            when {
                                tile.kind == TileKind.FOLDER -> Icon(MenuIcons.Folder, contentDescription = null, tint = fg, modifier = Modifier.fillMaxWidth(0.5f).aspectRatio(1f))
                                icon != null -> Box(
                                    Modifier.fillMaxWidth(0.62f).aspectRatio(1f).background(bg), contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        icon, contentDescription = null, contentScale = ContentScale.Fit,
                                        colorFilter = if (icons.grayscale) GreyScale else null,
                                        modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f),
                                    )
                                }
                                else -> Text(
                                    monogram(tile.label), color = if (filled) bg else fg, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Text(
                            tile.label, color = fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The stored icon of [host], read off the main thread and reloaded when the cache changes. */
@Composable
private fun rememberFavicon(store: FaviconStore?, host: String): ImageBitmap? {
    if (store == null) return null
    val version by store.version.collectAsState()
    val icon by produceState(store.peek(host), host, version) { value = withContext(Dispatchers.IO) { store.load(host) } }
    return icon
}

/** One or two letters for a tile: `github` -> `G`, `my-site` -> `MS`. */
internal fun monogram(label: String): String {
    val parts = label.split('-', '_', ' ').filter { it.isNotEmpty() }
    val letters = if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}" else label.take(1)
    return letters.uppercase().ifEmpty { "?" }
}
