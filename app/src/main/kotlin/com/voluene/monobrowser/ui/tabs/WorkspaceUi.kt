package com.voluene.monobrowser.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import com.voluene.monobrowser.R
import com.voluene.monobrowser.data.Workspace
import com.voluene.monobrowser.data.WorkspaceOps
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoDialog
import com.voluene.monobrowser.ui.common.MonoTextField

/** Forces the text (monochrome) presentation so symbols such as the heart never turn into colour emoji. */
fun monoGlyph(icon: String): String = if (icon.endsWith("\uFE0E")) icon else icon + "\uFE0E"

/** Everything the tab switcher needs to show and edit workspaces; names come already localised. */
data class WorkspaceUiState(
    val workspaces: List<Workspace>,
    val currentId: String,
    val names: Map<String, String>,
    val counts: Map<String, Int>,
)

/** Horizontal strip of workspace chips (current one inverted) plus a "+" chip. Long-press = options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceBar(
    state: WorkspaceUiState,
    onSwitch: (String) -> Unit,
    onOptions: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.workspaces.forEach { workspace ->
            val selected = workspace.id == state.currentId
            val bg = if (selected) scheme.onBackground else scheme.background
            val fg = if (selected) scheme.background else scheme.onBackground
            Row(
                Modifier.background(bg).border(1.dp, scheme.outline, RectangleShape)
                    .combinedClickable(onClick = { onSwitch(workspace.id) }, onLongClick = { onOptions(workspace.id) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(monoGlyph(workspace.icon), color = fg, fontSize = 14.sp)
                Text(
                    state.names[workspace.id].orEmpty(), color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp),
                )
                Text("${state.counts[workspace.id] ?: 0}", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
        Box(
            Modifier.border(1.dp, scheme.outline, RectangleShape).clickable(onClick = onCreate).padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("+", color = scheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Create / edit a workspace: name field and an icon picker (square cells, the chosen one inverted). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceEditorDialog(
    title: String,
    initial: Workspace?,
    initialName: String,
    confirmLabel: String,
    onConfirm: (name: String, icon: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initial?.icon ?: WorkspaceOps.ICONS[0]) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    MonoDialog(onDismiss) {
        Text(title, color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        MonoTextField(name, { name = it.take(WorkspaceOps.MAX_NAME_LENGTH) }, stringResource(R.string.workspace_name_hint), focusRequester = focus)
        Text(stringResource(R.string.workspace_icon), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkspaceOps.ICONS.forEach { candidate ->
                val chosen = candidate == icon
                Box(
                    Modifier.size(40.dp).background(if (chosen) scheme.onBackground else scheme.background)
                        .border(1.dp, scheme.outline, RectangleShape).clickable { icon = candidate },
                    contentAlignment = Alignment.Center,
                ) { Text(monoGlyph(candidate), color = if (chosen) scheme.background else scheme.onBackground, fontSize = 18.sp) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.weight(1f))
            MonoButton(confirmLabel, { onConfirm(name, icon); onDismiss() }, Modifier.weight(1f), inverted = true)
        }
    }
}

/** Options of one workspace: edit, reorder, delete (not for the main one). */
@Composable
fun WorkspaceOptionsDialog(
    workspace: Workspace,
    name: String,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MonoDialog(onDismiss) {
        Text("${monoGlyph(workspace.icon)}  $name", color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        MonoButton(stringResource(R.string.workspace_edit), { onDismiss(); onEdit() }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.workspace_move_left), { onMove(-1); onDismiss() }, Modifier.weight(1f), enabled = canMoveLeft)
            MonoButton(stringResource(R.string.workspace_move_right), { onMove(1); onDismiss() }, Modifier.weight(1f), enabled = canMoveRight)
        }
        if (workspace.id != WorkspaceOps.MAIN_ID) {
            MonoButton(stringResource(R.string.workspace_delete), { onDismiss(); onDelete() }, Modifier.fillMaxWidth(), inverted = true)
        }
        MonoButton(stringResource(R.string.action_close), onDismiss, Modifier.fillMaxWidth())
    }
}

/** Delete confirmation; when the workspace has tabs the user chooses what happens to them. */
@Composable
fun WorkspaceDeleteDialog(
    name: String,
    tabCount: Int,
    mainName: String,
    onDelete: (closeTabs: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MonoDialog(onDismiss) {
        Text(stringResource(R.string.workspace_delete_title, name), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (tabCount > 0) {
            Text(stringResource(R.string.workspace_delete_tabs, tabCount), color = scheme.onBackground)
            MonoButton(stringResource(R.string.workspace_delete_move, mainName), { onDelete(false); onDismiss() }, Modifier.fillMaxWidth(), inverted = true)
            MonoButton(stringResource(R.string.workspace_delete_close), { onDelete(true); onDismiss() }, Modifier.fillMaxWidth(), inverted = true)
        } else {
            MonoButton(stringResource(R.string.workspace_delete_confirm), { onDelete(false); onDismiss() }, Modifier.fillMaxWidth(), inverted = true)
        }
        MonoButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.fillMaxWidth())
    }
}
