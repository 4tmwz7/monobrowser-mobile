package com.voluene.monobrowser.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.Tab
import com.voluene.monobrowser.data.WorkspaceOps
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.util.UrlUtils

/**
 * Full-screen tab list of the current workspace, with a workspace selector on top (tap = switch, long-press =
 * edit / reorder / delete, "+" = create). Private tabs are inverted and badged. A long press on a tab shows its
 * options ("Never sleep", "Move to workspace"); sleeping tabs are marked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabSwitcher(
    tabs: List<Tab>,
    activeId: Long,
    canReopen: Boolean,
    onSwitch: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onReopen: () -> Unit,
    onSetNeverSleep: (Long, Boolean) -> Unit,
    workspaces: WorkspaceUiState,
    onSwitchWorkspace: (String) -> Unit,
    onCreateWorkspace: (name: String, icon: String) -> Unit,
    onEditWorkspace: (id: String, name: String, icon: String) -> Unit,
    onMoveWorkspace: (id: String, delta: Int) -> Unit,
    onDeleteWorkspace: (id: String, closeTabs: Boolean) -> Unit,
    onMoveTabToWorkspace: (tabId: Long, workspaceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var optionsFor by remember { mutableStateOf<Long?>(null) }
    var workspaceOptions by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    fun byId(id: String?) = workspaces.workspaces.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tabs_title), color = scheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.tabs_close_switcher), tint = scheme.onBackground)
            }
        }
        WorkspaceBar(
            workspaces,
            onSwitch = onSwitchWorkspace,
            onOptions = { workspaceOptions = it },
            onCreate = { creating = true },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(tabs, key = { it.id }) { tab ->
                val bg = if (tab.isPrivate) scheme.onBackground else scheme.background
                val fg = if (tab.isPrivate) scheme.background else scheme.onBackground
                val borderWidth = if (tab.id == activeId) 3.dp else 1.dp
                Column(Modifier.fillMaxWidth().background(bg).border(borderWidth, scheme.outline, RectangleShape)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onSwitch(tab.id) }, onLongClick = { optionsFor = if (optionsFor == tab.id) null else tab.id })
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            val badges = buildList {
                                if (tab.isPrivate) add(stringResource(R.string.tab_private_badge))
                                if (tab.neverSleep) add(stringResource(R.string.tab_never_sleep_badge))
                                if (tab.sleeping) add(stringResource(R.string.tab_sleeping_badge))
                            }
                            if (badges.isNotEmpty()) {
                                Text(badges.joinToString("  ·  "), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                tab.title.ifBlank { UrlUtils.shortUrlLabel(tab.url).ifBlank { stringResource(R.string.tab_untitled) } },
                                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                            )
                            if (tab.url.isNotBlank() && !UrlUtils.isAboutBlankUrl(tab.url)) {
                                Text(UrlUtils.shortUrlLabel(tab.url), color = fg.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (tab.blockedCount > 0) {
                                Text(stringResource(R.string.tab_blocked_count, tab.blockedCount), color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { onClose(tab.id) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.tab_close), tint = fg)
                        }
                    }
                    if (optionsFor == tab.id) {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Private tabs never sleep (their state cannot be saved), so the toggle is not offered.
                            if (!tab.isPrivate) {
                                MonoButton(
                                    stringResource(if (tab.neverSleep) R.string.tab_never_sleep_on else R.string.tab_never_sleep_off),
                                    { onSetNeverSleep(tab.id, !tab.neverSleep) },
                                    Modifier.fillMaxWidth(), inverted = !tab.neverSleep,
                                )
                            }
                            workspaces.workspaces.filter { it.id != tab.workspaceId }.forEach { target ->
                                MonoButton(
                                    stringResource(R.string.workspace_move_tab, "${monoGlyph(target.icon)} ${workspaces.names[target.id].orEmpty()}"),
                                    { onMoveTabToWorkspace(tab.id, target.id); optionsFor = null },
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoButton(stringResource(R.string.tab_new), onNewTab, Modifier.weight(1f))
                MonoButton(stringResource(R.string.tab_new_private), onNewPrivateTab, Modifier.weight(1f), inverted = true)
            }
            MonoButton(stringResource(R.string.tab_reopen_closed), onReopen, Modifier.fillMaxWidth(), enabled = canReopen)
        }
    }

    workspaceOptions?.let { id ->
        byId(id)?.let { workspace ->
            val index = workspaces.workspaces.indexOf(workspace)
            WorkspaceOptionsDialog(
                workspace = workspace,
                name = workspaces.names[id].orEmpty(),
                canMoveLeft = index > 0,
                canMoveRight = index < workspaces.workspaces.lastIndex,
                onEdit = { editing = id },
                onMove = { onMoveWorkspace(id, it) },
                onDelete = { deleting = id },
                onDismiss = { workspaceOptions = null },
            )
        }
    }
    editing?.let { id ->
        byId(id)?.let { workspace ->
            WorkspaceEditorDialog(
                title = stringResource(R.string.workspace_edit),
                initial = workspace,
                initialName = workspace.name.ifBlank { if (workspace.id == WorkspaceOps.MAIN_ID) workspaces.names[id].orEmpty() else "" },
                confirmLabel = stringResource(R.string.workspace_save),
                onConfirm = { name, icon -> onEditWorkspace(id, name, icon) },
                onDismiss = { editing = null },
            )
        }
    }
    if (creating) {
        WorkspaceEditorDialog(
            title = stringResource(R.string.workspace_new),
            initial = null,
            initialName = "",
            confirmLabel = stringResource(R.string.workspace_create),
            onConfirm = { name, icon -> onCreateWorkspace(name, icon) },
            onDismiss = { creating = false },
        )
    }
    deleting?.let { id ->
        WorkspaceDeleteDialog(
            name = workspaces.names[id].orEmpty(),
            tabCount = workspaces.counts[id] ?: 0,
            mainName = workspaces.names[WorkspaceOps.MAIN_ID].orEmpty(),
            onDelete = { close -> onDeleteWorkspace(id, close) },
            onDismiss = { deleting = null },
        )
    }
}
