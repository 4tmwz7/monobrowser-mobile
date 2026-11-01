package com.voluene.monobrowser.ui.bookmarks

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.ActivityHost
import com.voluene.monobrowser.data.Bookmark
import com.voluene.monobrowser.data.BookmarkData
import com.voluene.monobrowser.data.BookmarkFolder
import com.voluene.monobrowser.data.BookmarkOps
import com.voluene.monobrowser.data.ImportResult
import com.voluene.monobrowser.ui.common.ConfirmDialog
import com.voluene.monobrowser.ui.common.HairLine
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoDialog
import com.voluene.monobrowser.ui.common.ScreenScaffold
import com.voluene.monobrowser.ui.common.TextInputDialog
import com.voluene.monobrowser.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Dialog {
    data object NewFolder : Dialog
    data object Export : Dialog
    data class BookmarkMenu(val bookmark: Bookmark) : Dialog
    data class FolderMenu(val folder: BookmarkFolder) : Dialog
    data class EditBookmark(val bookmark: Bookmark) : Dialog
    data class RenameFolder(val folder: BookmarkFolder) : Dialog
    data class Move(val bookmark: Bookmark?, val folder: BookmarkFolder?) : Dialog
    data class DeleteBookmark(val bookmark: Bookmark) : Dialog
    data class DeleteFolder(val folder: BookmarkFolder) : Dialog
}

/** Bookmarks with folders; import/export through the Storage Access Framework (desktop-compatible files). */
@Composable
fun BookmarksScreen(
    data: BookmarkData,
    host: () -> ActivityHost?,
    onEdit: ((BookmarkData) -> BookmarkData) -> Unit,
    onOpen: (url: String, newTab: Boolean) -> Unit,
    onImport: (String) -> Result<ImportResult>,
    onExport: (json: Boolean) -> String,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folderId by remember { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    // A folder that no longer exists (deleted) falls back to the top level.
    val current = BookmarkOps.folder(data, folderId)
    val effectiveFolder = current?.id
    BackHandler(enabled = effectiveFolder != null) { folderId = current?.parentId }

    val folders = BookmarkOps.childFolders(data, effectiveFolder)
    val items = BookmarkOps.childBookmarks(data, effectiveFolder)
    val pathLabel = BookmarkOps.path(data, effectiveFolder).joinToString(" / ")

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    ScreenScaffold(stringResource(R.string.bookmarks_title), onClose) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.bookmarks_new_folder), { dialog = Dialog.NewFolder }, Modifier.weight(1f))
            MonoButton(stringResource(R.string.bookmarks_import), {
                val h = host()
                if (h == null) return@MonoButton
                h.openDocument(arrayOf("*/*")) { uri ->
                    if (uri == null) return@openDocument
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { readText(context, uri) }
                        if (text == null) { toast(context.getString(R.string.bookmarks_import_failed)); return@launch }
                        onImport(text).onSuccess {
                            toast(context.getString(R.string.bookmarks_import_done, it.added, it.skipped))
                        }.onFailure { toast(context.getString(R.string.bookmarks_import_failed)) }
                    }
                }
            }, Modifier.weight(1f))
            MonoButton(stringResource(R.string.bookmarks_export), { dialog = Dialog.Export }, Modifier.weight(1f), enabled = data.bookmarks.isNotEmpty())
        }
        if (effectiveFolder != null) {
            Row(
                Modifier.fillMaxWidth().clickable { folderId = current?.parentId }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.bookmarks_up), color = scheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(pathLabel, color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
            HairLine()
        }
        if (folders.isEmpty() && items.isEmpty()) {
            Text(
                stringResource(if (effectiveFolder == null) R.string.bookmarks_empty else R.string.bookmarks_folder_empty),
                color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            items(folders, key = { "f${it.id}" }) { f ->
                val count = BookmarkOps.childBookmarks(data, f.id).size + BookmarkOps.childFolders(data, f.id).size
                Row(Modifier.fillMaxWidth().clickable { folderId = f.id }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp, 10.dp).border(2.dp, scheme.onBackground, RectangleShape))
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
                        Text(f.name, color = scheme.onBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.bookmarks_folder_count, count), color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp)
                    }
                    IconButton(onClick = { dialog = Dialog.FolderMenu(f) }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.action_menu), tint = scheme.onBackground)
                    }
                }
                HairLine()
            }
            items(items, key = { "b${it.id}" }) { b ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(b.url, false) }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(b.title.ifBlank { UrlUtils.shortUrlLabel(b.url) }, color = scheme.onBackground, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(UrlUtils.shortUrlLabel(b.url), color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { dialog = Dialog.BookmarkMenu(b) }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.action_menu), tint = scheme.onBackground)
                    }
                }
                HairLine()
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewFolder -> TextInputDialog(
            stringResource(R.string.bookmarks_new_folder), "", stringResource(R.string.bookmarks_folder_name),
            stringResource(R.string.dialog_ok), { name -> onEdit { BookmarkOps.addFolder(it, name, effectiveFolder).first } }, { dialog = null },
        )
        Dialog.Export -> MonoDialog({ dialog = null }) {
            Text(stringResource(R.string.bookmarks_export_title), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.bookmarks_export_message), color = scheme.onBackground)
            fun export(json: Boolean) {
                dialog = null
                val name = if (json) "monobrowser-bookmarks.json" else "monobrowser-bookmarks.html"
                host()?.createDocument(name, if (json) "application/json" else "text/html") { uri ->
                    if (uri == null) return@createDocument
                    val text = onExport(json)
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { writeText(context, uri, text) }
                        toast(context.getString(if (ok) R.string.bookmarks_export_done else R.string.bookmarks_export_failed))
                    }
                }
            }
            MonoButton(stringResource(R.string.bookmarks_format_html), { export(false) }, Modifier.fillMaxWidth(), inverted = true)
            MonoButton(stringResource(R.string.bookmarks_format_json), { export(true) }, Modifier.fillMaxWidth())
            MonoButton(stringResource(R.string.dialog_cancel), { dialog = null }, Modifier.fillMaxWidth())
        }
        is Dialog.BookmarkMenu -> MonoDialog({ dialog = null }) {
            Text(d.bookmark.title.ifBlank { d.bookmark.url }, color = scheme.onBackground, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MonoButton(stringResource(R.string.bookmarks_open_new_tab), { dialog = null; onOpen(d.bookmark.url, true) }, Modifier.fillMaxWidth(), inverted = true)
            MonoButton(stringResource(R.string.bookmarks_edit), { dialog = Dialog.EditBookmark(d.bookmark) }, Modifier.fillMaxWidth())
            MonoButton(stringResource(R.string.bookmarks_move), { dialog = Dialog.Move(d.bookmark, null) }, Modifier.fillMaxWidth())
            MonoButton(stringResource(R.string.bookmarks_delete), { dialog = Dialog.DeleteBookmark(d.bookmark) }, Modifier.fillMaxWidth())
        }
        is Dialog.FolderMenu -> MonoDialog({ dialog = null }) {
            Text(d.folder.name, color = scheme.onBackground, fontWeight = FontWeight.Bold)
            MonoButton(stringResource(R.string.bookmarks_rename), { dialog = Dialog.RenameFolder(d.folder) }, Modifier.fillMaxWidth())
            MonoButton(stringResource(R.string.bookmarks_move), { dialog = Dialog.Move(null, d.folder) }, Modifier.fillMaxWidth())
            MonoButton(stringResource(R.string.bookmarks_delete), { dialog = Dialog.DeleteFolder(d.folder) }, Modifier.fillMaxWidth())
        }
        is Dialog.EditBookmark -> EditBookmarkDialog(d.bookmark, { title, url -> onEdit { BookmarkOps.editBookmark(it, d.bookmark.id, title, url) } }, { dialog = null })
        is Dialog.RenameFolder -> TextInputDialog(
            stringResource(R.string.bookmarks_rename), d.folder.name, stringResource(R.string.bookmarks_folder_name),
            stringResource(R.string.dialog_ok), { name -> onEdit { BookmarkOps.renameFolder(it, d.folder.id, name) } }, { dialog = null },
        )
        is Dialog.Move -> {
            val exclude = d.folder?.let { BookmarkOps.descendants(data, it.id) }.orEmpty()
            val choices = BookmarkOps.folderChoices(data, exclude)
            MonoDialog({ dialog = null }) {
                Text(stringResource(R.string.bookmarks_move_to), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoButton(stringResource(R.string.bookmarks_top_level), { moveTo(d, null, onEdit); dialog = null }, Modifier.fillMaxWidth())
                    choices.forEach { (f, label) -> MonoButton(label, { moveTo(d, f.id, onEdit); dialog = null }, Modifier.fillMaxWidth()) }
                }
                MonoButton(stringResource(R.string.dialog_cancel), { dialog = null }, Modifier.fillMaxWidth(), inverted = true)
            }
        }
        is Dialog.DeleteBookmark -> ConfirmDialog(
            stringResource(R.string.bookmarks_delete), d.bookmark.title.ifBlank { d.bookmark.url },
            stringResource(R.string.bookmarks_delete), { onEdit { BookmarkOps.removeBookmark(it, d.bookmark.id) } }, { dialog = null },
        )
        is Dialog.DeleteFolder -> ConfirmDialog(
            stringResource(R.string.bookmarks_delete_folder), stringResource(R.string.bookmarks_delete_folder_message, d.folder.name),
            stringResource(R.string.bookmarks_delete), { onEdit { BookmarkOps.deleteFolder(it, d.folder.id) } }, { dialog = null },
        )
    }
}

private fun moveTo(d: Dialog.Move, target: Long?, onEdit: ((BookmarkData) -> BookmarkData) -> Unit) {
    d.bookmark?.let { b -> onEdit { BookmarkOps.moveBookmark(it, b.id, target) } }
    d.folder?.let { f -> onEdit { BookmarkOps.moveFolder(it, f.id, target) } }
}

@Composable
private fun EditBookmarkDialog(bookmark: Bookmark, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var title by remember { mutableStateOf(bookmark.title) }
    var url by remember { mutableStateOf(bookmark.url) }
    MonoDialog(onDismiss) {
        Text(stringResource(R.string.bookmarks_edit), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        com.voluene.monobrowser.ui.common.MonoTextField(title, { title = it }, stringResource(R.string.bookmarks_field_title))
        com.voluene.monobrowser.ui.common.MonoTextField(url, { url = it }, stringResource(R.string.bookmarks_field_url), keyboardType = KeyboardType.Uri)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.weight(1f))
            MonoButton(stringResource(R.string.dialog_ok), { onSave(title, url); onDismiss() }, Modifier.weight(1f), inverted = true, enabled = UrlUtils.isHttpUrl(url.trim()))
        }
    }
}

private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readBytes()
        if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes, Charsets.UTF_8)
    }
}.getOrNull()

private fun writeText(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
}.getOrDefault(false)

private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
