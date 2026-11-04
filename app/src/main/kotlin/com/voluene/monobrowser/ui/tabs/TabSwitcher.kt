package com.voluene.monobrowser.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import com.voluene.monobrowser.data.TopSites
import com.voluene.monobrowser.ui.common.ConfirmDialog
import com.voluene.monobrowser.ui.common.MonoTextField
import com.voluene.monobrowser.ui.palette.PaletteEngine
import com.voluene.monobrowser.ui.start.monogram
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
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
    onUndoClose: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var optionsFor by remember { mutableStateOf<Long?>(null) }
    var workspaceOptions by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    fun byId(id: String?) = workspaces.workspaces.firstOrNull { it.id == id }
    var query by remember { mutableStateOf("") }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var undoTitle by remember { mutableStateOf<String?>(null) }
    var undoKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(undoKey) { if (undoTitle != null) { delay(UNDO_MS); undoTitle = null } }
    val shown = remember(tabs, query) { tabs.filter { tabMatches(it, query) } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (tabs.indexOfFirst { it.id == activeId } - 1).coerceAtLeast(0))
    val untitled = stringResource(R.string.tab_untitled)

    fun close(tab: Tab) {
        onClose(tab.id)
        // Only normal tabs with a page can come back (the same rule as "Reopen closed tab").
        undoTitle = if (!tab.isPrivate && tab.url.isNotBlank() && !UrlUtils.isAboutBlankUrl(tab.url)) {
            tab.title.ifBlank { UrlUtils.shortUrlLabel(tab.url) }
        } else null
        undoKey++
    }

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tabs_title), color = scheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                tabs.size.toString(), color = scheme.onBackground.copy(alpha = 0.55f), fontSize = 20.sp,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(onClick = { confirmCloseAll = true }, enabled = tabs.isNotEmpty()) {
                Icon(Icons.Default.Delete, stringResource(R.string.tabs_close_all), tint = scheme.onBackground)
            }
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
        if (tabs.size >= FILTER_MIN_TABS || query.isNotEmpty()) {
            MonoTextField(query, { query = it }, stringResource(R.string.tabs_filter_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(shown, key = { it.id }) { tab ->
                val bg = if (tab.isPrivate) scheme.onBackground else scheme.background
                val fg = if (tab.isPrivate) scheme.background else scheme.onBackground
                val active = tab.id == activeId
                val borderWidth = if (active) 3.dp else 1.dp
                SwipeToClose(onClose = { close(tab) }, label = stringResource(R.string.tab_close), modifier = Modifier.animateItem()) {
                Column(Modifier.fillMaxWidth().background(bg).border(borderWidth, scheme.outline, RectangleShape)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onSwitch(tab.id) }, onLongClick = { optionsFor = if (optionsFor == tab.id) null else tab.id })
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val host = UrlUtils.shortUrlLabel(tab.url).substringBefore('/')
                        Box(
                            Modifier.size(36.dp).border(1.dp, fg, RectangleShape).then(if (active) Modifier.background(fg) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            val key = TopSites.hostKey(tab.url)
                            Text(
                                if (key != null) monogram(TopSites.label(key)) else "+",
                                color = if (active) bg else fg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
                            val badges = buildList {
                                if (tab.isPrivate) add(stringResource(R.string.tab_private_badge))
                                if (tab.desktopMode) add(stringResource(R.string.tab_desktop_badge))
                                if (tab.neverSleep) add(stringResource(R.string.tab_never_sleep_badge))
                                if (tab.sleeping) add(stringResource(R.string.tab_sleeping_badge))
                            }
                            if (badges.isNotEmpty()) {
                                Text(badges.joinToString("  ·  "), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                tab.title.ifBlank { host.ifBlank { untitled } },
                                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                            )
                            if (tab.url.isNotBlank() && !UrlUtils.isAboutBlankUrl(tab.url)) {
                                Text(UrlUtils.shortUrlLabel(tab.url), color = fg.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (tab.blockedCount > 0) {
                                Text(stringResource(R.string.tab_blocked_count, tab.blockedCount), color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { close(tab) }) {
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
        }
        undoTitle?.let { title ->
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().background(scheme.onBackground)
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tabs_closed, title), color = scheme.background, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.tabs_undo).uppercase(), color = scheme.background, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clickable { onUndoClose(); undoTitle = null }.padding(horizontal = 16.dp, vertical = 14.dp),
                )
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

    if (confirmCloseAll) {
        ConfirmDialog(
            stringResource(R.string.tabs_close_all),
            stringResource(R.string.tabs_close_all_message, tabs.size),
            stringResource(R.string.tabs_close_all_confirm),
            onConfirm = { onCloseAll(); undoTitle = null },
            onDismiss = { confirmCloseAll = false },
        )
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

private const val UNDO_MS = 4_000L
private const val FILTER_MIN_TABS = 6

/** Tab list filter: every word of [query] must appear in the title or the URL (accents ignored). */
internal fun tabMatches(tab: Tab, query: String): Boolean {
    val words = PaletteEngine.fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = PaletteEngine.fold("${tab.title} ${tab.url}")
    return words.all { haystack.contains(it) }
}

/**
 * Swipe sideways to close: the row follows the finger; past [THRESHOLD] of its width it flies out and [onClose]
 * runs, otherwise it springs back. The uncovered strip shows [label].
 */
@Composable
private fun SwipeToClose(onClose: () -> Unit, label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    val close by rememberUpdatedState(onClose)
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        if (offset.value != 0f) {
            Box(
                Modifier.matchParentSize().background(scheme.surfaceVariant).padding(horizontal = 20.dp),
                contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(label.uppercase(), color = scheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 1f) * 0.6f }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(offset.value) > width * THRESHOLD) {
                                    offset.animateTo(if (offset.value > 0) width.toFloat() else -width.toFloat(), tween(150))
                                    close()
                                    offset.snapTo(0f)
                                } else {
                                    offset.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + delta) }
                        },
                    )
                },
        ) { content() }
    }
}

private const val THRESHOLD = 0.4f
