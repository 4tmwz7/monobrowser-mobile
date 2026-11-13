package com.voluene.monobrowser.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.data.BookmarkData
import com.voluene.monobrowser.data.BookmarkOps
import com.voluene.monobrowser.ui.common.HairLine
import com.voluene.monobrowser.ui.common.ScreenScaffold
import com.voluene.monobrowser.util.UrlUtils

/**
 * Picks what the start page shows: a tree of folders and bookmarks with square checkboxes. A ticked bookmark is a
 * tile in the "Bookmarks" section; a ticked folder becomes a section of its own (its bookmarks and sub-folders).
 * Tapping a folder's name goes into it, the checkbox ticks it; the order of ticking is the order on the page.
 */
@Composable
fun StartBookmarksPicker(
    data: BookmarkData,
    onEdit: ((BookmarkData) -> BookmarkData) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var folderId by remember { mutableStateOf<Long?>(null) }
    // A folder that no longer exists falls back to the top level.
    val current = BookmarkOps.folder(data, folderId)
    val here = current?.id
    BackHandler(enabled = here != null) { folderId = current?.parentId }
    val folders = remember(data, here) { BookmarkOps.childFolders(data, here) }
    val bookmarks = remember(data, here) { BookmarkOps.childBookmarks(data, here) }
    // Counted once per change of the data, not on every recomposition of a row.
    val chosenCount = remember(data) { BookmarkOps.startContent(data).let { it.bookmarks.size + it.folders.size } }
    val itemCounts = remember(data, here) { folders.associate { it.id to BookmarkOps.childBookmarks(data, it.id).size + BookmarkOps.childFolders(data, it.id).size } }

    ScreenScaffold(stringResource(R.string.start_picker_title), onClose) {
        Text(
            stringResource(R.string.start_picker_hint, chosenCount),
            color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (here != null) {
            Row(
                Modifier.fillMaxWidth().clickable { folderId = current?.parentId }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.bookmarks_up), color = scheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    BookmarkOps.path(data, here).joinToString(" / "), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                )
            }
            HairLine()
        }
        if (folders.isEmpty() && bookmarks.isEmpty()) {
            Text(
                stringResource(if (here == null) R.string.bookmarks_empty else R.string.bookmarks_folder_empty),
                color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            items(folders, key = { "f${it.id}" }) { f ->
                val selected = BookmarkOps.isOnStart(data, f.id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Check(selected, f.name) { onEdit { BookmarkOps.toggleStart(it, f.id) } }
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clickable { folderId = f.id }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp, 10.dp).border(2.dp, scheme.onBackground, RectangleShape))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(f.name, color = scheme.onBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                androidx.compose.ui.res.pluralStringResource(R.plurals.bookmarks_folder_count, itemCounts[f.id] ?: 0, itemCounts[f.id] ?: 0),
                                color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp,
                            )
                        }
                        Text(">", color = scheme.onBackground, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                HairLine()
            }
            items(bookmarks, key = { "b${it.id}" }) { b ->
                val selected = BookmarkOps.isOnStart(data, b.id)
                Row(
                    Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox) { onEdit { BookmarkOps.toggleStart(it, b.id) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) { Box22(selected) }
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(b.title.ifBlank { UrlUtils.shortUrlLabel(b.url) }, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(UrlUtils.shortUrlLabel(b.url), color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HairLine()
            }
        }
    }
}

@Composable
private fun Check(selected: Boolean, label: String, onToggle: () -> Unit) {
    Box(
        Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() }).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) { Box22(selected) }
}

/** Square checkbox (the look of `MonoCheckbox`, without its label and row). */
@Composable
private fun Box22(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(22.dp).border(2.dp, scheme.outline, RectangleShape).padding(4.dp)) {
        if (checked) Box(Modifier.fillMaxSize().background(scheme.onBackground))
    }
}
