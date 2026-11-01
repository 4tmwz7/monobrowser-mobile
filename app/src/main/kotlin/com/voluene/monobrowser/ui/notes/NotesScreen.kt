package com.voluene.monobrowser.ui.notes

import android.content.Intent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.notes.NoteMeta
import com.voluene.monobrowser.notes.NotesRepository
import com.voluene.monobrowser.ui.common.ConfirmDialog
import com.voluene.monobrowser.ui.common.HairLine
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.ScreenScaffold
import java.text.DateFormat
import java.util.Date

/**
 * Notes: a list of Markdown notes and an editor with an Edit / Preview toggle. [initialId] opens that note
 * straight away (a freshly created one). Edits are saved as you type; a note left blank is discarded.
 */
@Composable
fun NotesScreen(
    repository: NotesRepository,
    initialId: String?,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    var openId by remember { mutableStateOf(initialId) }
    DisposableEffect(repository) { onDispose { repository.flush() } }
    val id = openId
    if (id == null) {
        NotesList(
            repository = repository,
            onOpen = { openId = it },
            onNew = { openId = repository.create() },
            onClose = onClose,
        )
    } else {
        NoteEditor(
            repository = repository,
            id = id,
            onOpenUrl = onOpenUrl,
            onBack = {
                repository.flush()
                repository.discardIfBlank(id)
                openId = null
            },
        )
    }
}

@Composable
private fun NotesList(repository: NotesRepository, onOpen: (String) -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val notes by repository.notes.collectAsState()
    ScreenScaffold(
        title = stringResource(R.string.notes_title),
        onClose = onClose,
        actions = { MonoButton(stringResource(R.string.notes_new), onNew, Modifier.padding(end = 8.dp), inverted = true) },
    ) {
        if (notes.isEmpty()) {
            Text(stringResource(R.string.notes_empty), color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(notes, key = NoteMeta::id) { note ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(note.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            note.title.ifBlank { stringResource(R.string.notes_untitled) },
                            color = scheme.onBackground, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (note.preview.isNotBlank()) {
                            Text(note.preview, color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.modified)),
                            color = scheme.onBackground.copy(alpha = 0.5f), fontSize = 11.sp,
                        )
                    }
                    HairLine()
                }
            }
        }
    }
}

@Composable
private fun NoteEditor(repository: NotesRepository, id: String, onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var text by remember(id) { mutableStateOf(repository.read(id)) }
    var preview by remember(id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(id) { if (text.isBlank()) focus.requestFocus() }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.notes_back), tint = scheme.onBackground)
            }
            Text(
                stringResource(R.string.notes_title), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            MonoButton(stringResource(if (preview) R.string.notes_edit else R.string.notes_preview), { preview = !preview }, inverted = preview)
        }
        HairLine()
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (preview) {
                MarkdownView(text, onLink = onOpenUrl)
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; repository.save(id, it) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.onBackground),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(stringResource(R.string.notes_hint), color = scheme.onBackground.copy(alpha = 0.5f), fontSize = 16.sp)
                        inner()
                    },
                )
            }
        }
        HairLine()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(
                stringResource(R.string.notes_share),
                {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.notes_share)))
                },
                Modifier.weight(1f), enabled = text.isNotBlank(),
            )
            MonoButton(stringResource(R.string.notes_delete), { confirmDelete = true }, Modifier.weight(1f))
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.notes_delete),
            message = stringResource(R.string.notes_delete_message),
            confirmLabel = stringResource(R.string.notes_delete),
            onConfirm = { repository.delete(id); onBack() },
            onDismiss = { confirmDelete = false },
        )
    }
}
