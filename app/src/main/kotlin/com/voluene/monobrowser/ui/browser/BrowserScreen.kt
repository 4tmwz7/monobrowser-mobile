package com.voluene.monobrowser.ui.browser

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.adblock.HostUtils
import com.voluene.monobrowser.browser.LinkActions
import com.voluene.monobrowser.browser.LinkMenuAction
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.data.WorkspaceOps
import com.voluene.monobrowser.ui.notes.NotesScreen
import com.voluene.monobrowser.ui.update.UpdateBanner
import com.voluene.monobrowser.ui.tabs.WorkspaceEditorDialog
import com.voluene.monobrowser.ui.tabs.WorkspaceUiState
import com.voluene.monobrowser.data.BookmarkOps
import com.voluene.monobrowser.settings.ToolbarPosition
import com.voluene.monobrowser.ui.bookmarks.BookmarksScreen
import com.voluene.monobrowser.ui.downloads.DownloadsScreen
import com.voluene.monobrowser.ui.history.HistoryScreen
import com.voluene.monobrowser.ui.onboarding.OnboardingScreen
import com.voluene.monobrowser.ui.palette.PaletteAction
import com.voluene.monobrowser.ui.palette.PaletteActionId
import com.voluene.monobrowser.ui.palette.PaletteLink
import com.voluene.monobrowser.ui.palette.PaletteResult
import com.voluene.monobrowser.ui.palette.PaletteScreen
import com.voluene.monobrowser.ui.palette.PaletteTab
import com.voluene.monobrowser.ui.settings.SettingsScreen
import com.voluene.monobrowser.ui.tabs.TabSwitcher
import com.voluene.monobrowser.util.UrlUtils
import android.content.ClipboardManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import androidx.compose.runtime.produceState
import com.voluene.monobrowser.data.TopSite
import com.voluene.monobrowser.reader.ReaderArticle
import com.voluene.monobrowser.ui.reader.ReaderScreen
import com.voluene.monobrowser.ui.start.StartPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Overlay { None, Tabs, Menu, History, Downloads, Settings, Bookmarks, Palette, Notes, Reader }

@Composable
fun BrowserScreen(manager: TabManager) {
    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeTabId.collectAsState()
    val recentlyClosed by manager.recentlyClosed.collectAsState()
    val dialogs by manager.dialogs.collectAsState()
    val find by manager.find.collectAsState()
    val barHidden by manager.barHidden.collectAsState()
    val settings by manager.settings.collectAsState()
    val bookmarks by manager.bookmarks.collectAsState()
    val exceptionHosts by manager.adBlocker.exceptions.hostsFlow.collectAsState()
    val workspaces by manager.workspaces.collectAsState()
    val currentWorkspaceId by manager.currentWorkspaceId.collectAsState()
    val glance by manager.glance.collectAsState()
    val linkMenu by manager.linkMenu.collectAsState()
    val tab = tabs.firstOrNull { it.id == activeId } ?: return
    var overlay by remember { mutableStateOf(Overlay.None) }
    var notesOpenId by remember { mutableStateOf<String?>(null) }
    var creatingWorkspace by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<ReaderArticle?>(null) }
    var textSizeOpen by remember { mutableStateOf(false) }
    var addressMenuOpen by remember { mutableStateOf(false) }
    var siteOptions by remember { mutableStateOf<TopSite?>(null) }
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val toolbarTop = settings.toolbarPosition == ToolbarPosition.TOP

    // The page background follows the theme (blank tabs, areas that have not painted yet).
    LaunchedEffect(scheme.background) { manager.setPageBackground(scheme.background.toArgb()) }
    // A blank new tab was opened or reused: go straight to typing.
    LaunchedEffect(manager) { manager.paletteRequests.collect { overlay = Overlay.Palette } }
    // Update checks begin once the first frame is on screen (non-blocking, release builds only).
    LaunchedEffect(manager) { manager.updater.start(manager.settings) }

    // System back, lowest to highest priority: page history, find bar, full-screen overlays.
    BackHandler(enabled = overlay == Overlay.None && find == null && tab.canGoBack) { manager.goBack(tab.id) }
    BackHandler(enabled = overlay == Overlay.None && find != null) { manager.closeFind() }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }
    BackHandler(enabled = linkMenu != null) { manager.dismissLinkMenu() }
    BackHandler(enabled = addressMenuOpen) { addressMenuOpen = false }
    BackHandler(enabled = siteOptions != null) { siteOptions = null }
    BackHandler(enabled = textSizeOpen) { textSizeOpen = false }

    val isWeb = UrlUtils.isHttpUrl(tab.url)
    val siteHost = HostUtils.hostOfUrl(tab.url)
    val siteExcepted = siteHost != null && HostUtils.isHostExcepted(siteHost, exceptionHosts.toSet())
    val bookmarked = isWeb && BookmarkOps.findByUrl(bookmarks, tab.url) != null

    fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.menu_share)))
    }
    fun shareLink() = shareText(tab.url)

    fun toast(res: Int, vararg args: Any) = Toast.makeText(context, context.getString(res, *args), Toast.LENGTH_SHORT).show()

    fun openReader() {
        val tabId = tab.id
        manager.extractArticle(tabId) { article ->
            if (article == null) {
                toast(R.string.reader_unavailable)
            } else if (manager.activeTabId.value == tabId) {
                readerArticle = article
                overlay = Overlay.Reader
            }
        }
    }

    fun printPage() {
        val webView = manager.webViewFor(tab.id)
        val printManager = context.getSystemService(PrintManager::class.java)
        if (webView == null || printManager == null) {
            toast(R.string.print_unavailable)
            return
        }
        val name = tab.title.ifBlank { UrlUtils.shortUrlLabel(tab.url) }.ifBlank { context.getString(R.string.app_name) }
        runCatching { printManager.print(name, webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build()) }
            .onFailure { toast(R.string.print_unavailable) }
    }

    val pasteAndGo = {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) manager.navigate(tab.id, text)
    }

    // Workspaces: the switcher shows (and the tab count counts) only the current workspace.
    val mainLabel = stringResource(R.string.workspace_main)
    val workspaceNames = workspaces.associate { it.id to WorkspaceOps.displayName(it, mainLabel) }
    val workspaceTabs = tabs.filter { it.workspaceId == currentWorkspaceId }
    val workspaceState = WorkspaceUiState(
        workspaces = workspaces,
        currentId = currentWorkspaceId,
        names = workspaceNames,
        counts = tabs.groupingBy { it.workspaceId }.eachCount(),
    )

    fun openNotes(newNote: Boolean) {
        notesOpenId = if (newNote) manager.notes.create() else null
        overlay = Overlay.Notes
    }

    val menuTiles = listOf(
        MenuTile(R.string.tab_new, Icons.Filled.Add) { manager.openNewTab() },
        MenuTile(R.string.menu_new_private_short, Icons.Filled.Lock) { manager.openNewTab(isPrivate = true) },
        MenuTile(R.string.menu_bookmarks, MenuIcons.Bookmark) { overlay = Overlay.Bookmarks },
        MenuTile(
            if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add,
            if (bookmarked) Icons.Filled.Star else MenuIcons.StarOutline, enabled = isWeb && !tab.isPrivate, active = bookmarked,
        ) { manager.toggleBookmark(tab.id) },
        MenuTile(R.string.menu_history, Icons.Filled.DateRange) { overlay = Overlay.History },
        MenuTile(R.string.menu_downloads, MenuIcons.Download) { overlay = Overlay.Downloads },
        MenuTile(R.string.menu_notes, Icons.Filled.Edit) { openNotes(newNote = false) },
        MenuTile(R.string.menu_note_add_short, Icons.Filled.List, enabled = isWeb && !tab.isPrivate) { manager.addPageToNote(tab.id) },
        MenuTile(R.string.menu_find, Icons.Filled.Search, enabled = isWeb) { manager.openFind() },
        MenuTile(R.string.menu_reader, MenuIcons.Article, enabled = isWeb && !tab.isLoading) { openReader() },
        MenuTile(R.string.menu_desktop_site, MenuIcons.Desktop, enabled = isWeb, active = tab.desktopMode) { manager.toggleDesktopMode(tab.id) },
        MenuTile(R.string.menu_text_size, MenuIcons.TextSize) { textSizeOpen = true },
        MenuTile(R.string.menu_reopen_short, Icons.Filled.Refresh, enabled = recentlyClosed.isNotEmpty()) { manager.reopenClosedTab() },
        MenuTile(R.string.menu_share, Icons.Filled.Share, enabled = isWeb) { shareLink() },
        MenuTile(R.string.menu_print, MenuIcons.Print, enabled = isWeb && !tab.isLoading) { printPage() },
        MenuTile(R.string.menu_settings, Icons.Filled.Settings) { overlay = Overlay.Settings },
    )
    val menuInfo = when {
        !isWeb -> null
        !settings.adblockEnabled -> MenuInfo(stringResource(R.string.menu_adblock_header_off))
        siteExcepted -> MenuInfo(stringResource(R.string.menu_adblock_header_site_off), stringResource(R.string.menu_adblock_toggle_on)) { manager.toggleAdblockForSite(tab.id) }
        else -> MenuInfo(stringResource(R.string.menu_adblock_header, tab.blockedCount), stringResource(R.string.menu_adblock_toggle_off)) { manager.toggleAdblockForSite(tab.id) }
    }

    val labelNewTab = stringResource(R.string.tab_new)
    val labelNewPrivate = stringResource(R.string.tab_new_private)
    val paletteActions = buildList {
        add(PaletteAction(PaletteActionId.NEW_TAB, labelNewTab, "nowa karta new tab open"))
        add(PaletteAction(PaletteActionId.NEW_PRIVATE_TAB, labelNewPrivate, "prywatna incognito private nowa karta"))
        add(PaletteAction(PaletteActionId.OPEN_SETTINGS, stringResource(R.string.palette_open_settings), "ustawienia settings preferences opcje"))
        add(PaletteAction(PaletteActionId.OPEN_HISTORY, stringResource(R.string.menu_history), "historia history"))
        add(PaletteAction(PaletteActionId.OPEN_DOWNLOADS, stringResource(R.string.menu_downloads), "pobieranie pobrane downloads"))
        add(PaletteAction(PaletteActionId.OPEN_BOOKMARKS, stringResource(R.string.menu_bookmarks), "zakladki bookmarks favorites ulubione"))
        add(PaletteAction(PaletteActionId.OPEN_NOTES, stringResource(R.string.menu_notes), "notatki notatnik notes markdown"))
        add(PaletteAction(PaletteActionId.NEW_NOTE, stringResource(R.string.notes_new), "nowa notatka new note"))
        if (isWeb && !tab.isPrivate) {
            add(PaletteAction(PaletteActionId.ADD_PAGE_TO_NOTE, stringResource(R.string.notes_add_page), "dodaj strone do notatki add page to note"))
        }
        add(PaletteAction(PaletteActionId.FIND, stringResource(R.string.menu_find), "szukaj na stronie find search page ctrl f"))
        if (isWeb) {
            add(PaletteAction(PaletteActionId.READER, stringResource(R.string.menu_reader), "czytnik tryb czytania reader view article artykul"))
            add(PaletteAction(
                PaletteActionId.DESKTOP_SITE,
                stringResource(if (tab.desktopMode) R.string.desktop_mode_off else R.string.menu_desktop_site),
                "wersja komputerowa desktop site user agent pc",
            ))
            add(PaletteAction(PaletteActionId.PRINT, stringResource(R.string.menu_print), "drukuj print pdf zapisz save"))
        }
        add(PaletteAction(PaletteActionId.TEXT_SIZE, stringResource(R.string.menu_text_size), "rozmiar tekstu czcionka text size font zoom powiekszenie"))
        if (isWeb) {
            add(PaletteAction(
                PaletteActionId.TOGGLE_ADBLOCK_SITE,
                stringResource(if (siteExcepted) R.string.menu_adblock_site_on else R.string.menu_adblock_site_off),
                "reklamy adblock blokowanie ads blocking witryna site wyjatek exception",
            ))
        }
        add(PaletteAction(
            PaletteActionId.TOGGLE_ADBLOCK,
            stringResource(if (settings.adblockEnabled) R.string.palette_adblock_off else R.string.palette_adblock_on),
            "reklamy adblock blokowanie tresci content blocking ads",
        ))
        if (isWeb && !tab.isPrivate) {
            add(PaletteAction(
                PaletteActionId.BOOKMARK_PAGE,
                stringResource(if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add),
                "zakladka bookmark star gwiazdka",
            ))
        }
        add(PaletteAction(PaletteActionId.RELOAD, stringResource(R.string.action_reload), "odswiez przeladuj reload refresh"))
        if (tab.canGoBack) add(PaletteAction(PaletteActionId.BACK, stringResource(R.string.action_back), "wstecz back"))
        if (tab.canGoForward) add(PaletteAction(PaletteActionId.FORWARD, stringResource(R.string.action_forward), "dalej forward"))
        add(PaletteAction(PaletteActionId.CLOSE_TAB, stringResource(R.string.tab_close), "zamknij karte close tab"))
        if (recentlyClosed.isNotEmpty()) add(PaletteAction(PaletteActionId.REOPEN_CLOSED, stringResource(R.string.tab_reopen_closed), "przywroc zamknieta karte reopen closed"))
        if (tab.url.isNotBlank()) add(PaletteAction(PaletteActionId.SHARE, stringResource(R.string.menu_share), "udostepnij share link"))
        add(PaletteAction(PaletteActionId.NEW_WORKSPACE, stringResource(R.string.workspace_new), "nowa przestrzen workspace space new"))
        workspaces.filter { it.id != currentWorkspaceId }.forEach { target ->
            val name = workspaceNames[target.id].orEmpty()
            add(PaletteAction(
                PaletteActionId.SWITCH_WORKSPACE,
                stringResource(R.string.palette_switch_workspace, name),
                "przestrzen przelacz workspace switch space $name",
                hint = com.voluene.monobrowser.ui.tabs.monoGlyph(target.icon),
                arg = target.id,
            ))
        }
    }

    fun runAction(action: PaletteAction) {
        when (action.id) {
            PaletteActionId.NEW_TAB -> manager.openNewTab()
            PaletteActionId.NEW_PRIVATE_TAB -> manager.openNewTab(isPrivate = true)
            PaletteActionId.CLOSE_TAB -> manager.closeTab(tab.id)
            PaletteActionId.RELOAD -> manager.reload(tab.id)
            PaletteActionId.BACK -> manager.goBack(tab.id)
            PaletteActionId.FORWARD -> manager.goForward(tab.id)
            PaletteActionId.REOPEN_CLOSED -> manager.reopenClosedTab()
            PaletteActionId.FIND -> manager.openFind()
            PaletteActionId.BOOKMARK_PAGE -> manager.toggleBookmark(tab.id)
            PaletteActionId.SHARE -> shareLink()
            PaletteActionId.TOGGLE_ADBLOCK_SITE -> manager.toggleAdblockForSite(tab.id)
            PaletteActionId.TOGGLE_ADBLOCK -> manager.updateSettings { it.copy(adblockEnabled = !it.adblockEnabled) }
            PaletteActionId.OPEN_BOOKMARKS -> overlay = Overlay.Bookmarks
            PaletteActionId.OPEN_HISTORY -> overlay = Overlay.History
            PaletteActionId.OPEN_DOWNLOADS -> overlay = Overlay.Downloads
            PaletteActionId.OPEN_SETTINGS -> overlay = Overlay.Settings
            PaletteActionId.OPEN_NOTES -> openNotes(newNote = false)
            PaletteActionId.NEW_NOTE -> openNotes(newNote = true)
            PaletteActionId.ADD_PAGE_TO_NOTE -> manager.addPageToNote(tab.id)
            PaletteActionId.NEW_WORKSPACE -> creatingWorkspace = true
            PaletteActionId.SWITCH_WORKSPACE -> action.arg?.let(manager::switchWorkspace)
            PaletteActionId.READER -> openReader()
            PaletteActionId.DESKTOP_SITE -> manager.toggleDesktopMode(tab.id)
            PaletteActionId.PRINT -> printPage()
            PaletteActionId.TEXT_SIZE -> textSizeOpen = true
        }
    }

    fun runPalette(result: PaletteResult) {
        overlay = Overlay.None
        when (result) {
            is PaletteResult.Go -> manager.navigate(tab.id, result.input)
            is PaletteResult.OpenTab -> manager.switchTo(result.tab.id)
            is PaletteResult.Bookmark -> manager.navigate(tab.id, result.link.url)
            is PaletteResult.History -> manager.navigate(tab.id, result.link.url)
            is PaletteResult.Run -> runAction(result.action)
        }
    }

    // A blank tab shows the start page over its (empty) WebView.
    val showStart = !tab.canGoBack && !tab.isLoading && (tab.url.isBlank() || UrlUtils.isAboutBlankUrl(tab.url))
    val topSites by produceState<List<TopSite>?>(null, showStart, settings.showTopSites, settings.hiddenTopSites, tab.id) {
        value = if (showStart && settings.showTopSites && !tab.isPrivate) withContext(Dispatchers.IO) { manager.topSites() } else value
    }

    val barVisible = !barHidden || overlay != Overlay.None || showStart
    val barBg = if (tab.isPrivate) scheme.onBackground else scheme.background

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        // Keyed by the toolbar position: switching it live must rebuild the page area (otherwise the WebView keeps a stale
        // size/offset and a blank band or an overlap with the status bar remains).
        androidx.compose.runtime.key(toolbarTop) { Column(Modifier.fillMaxSize()) {
            val bar: @Composable () -> Unit = {
                CollapsingBar(visible = barVisible, fromTop = toolbarTop) {
                    AddressBar(
                        tab = tab,
                        tabCount = workspaceTabs.size,
                        toolbarTop = toolbarTop,
                        onOpenPalette = { overlay = Overlay.Palette },
                        onAddressLongPress = { addressMenuOpen = true },
                        onBack = { manager.goBack(tab.id) },
                        onForward = { manager.goForward(tab.id) },
                        onReloadOrStop = { if (tab.isLoading) manager.stop(tab.id) else manager.reload(tab.id) },
                        onMenu = { overlay = Overlay.Menu },
                        onTabs = { overlay = Overlay.Tabs },
                        onSwipeTab = manager::switchRelative,
                    )
                }
            }
            if (toolbarTop) {
                // The status-bar strip stays in the bar's colour when the bar slides away.
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barBg))
                bar()
            }
            // The page area ends where the bar starts, so overlays aligned to its bottom sit right above the bar.
            Box(Modifier.weight(1f).fillMaxWidth().then(if (toolbarTop) Modifier.navigationBarsPadding() else Modifier.statusBarsPadding())) {
                WebViewHost(
                    webView = manager.webViewFor(tab.id),
                    onRefresh = { manager.reload(tab.id) },
                    lineAtBottom = !toolbarTop,
                )
                if (showStart) {
                    StartPage(
                        isPrivate = tab.isPrivate,
                        topSites = topSites,
                        showTopSites = settings.showTopSites,
                        recentlyClosed = if (tab.isPrivate) emptyList() else recentlyClosed,
                        onSearch = { overlay = Overlay.Palette },
                        onOpen = { manager.navigate(tab.id, it) },
                        onSiteOptions = { siteOptions = it },
                        onReopen = { manager.reopenClosedIn(tab.id, it) },
                    )
                }
                if (manager.updater.enabled) {
                    UpdateBanner(
                        updater = manager.updater,
                        onOpenUrl = { manager.newTab(url = it) },
                        modifier = Modifier.align(if (toolbarTop) Alignment.BottomCenter else Alignment.TopCenter).padding(8.dp).widthIn(max = 480.dp),
                    )
                }
                val findState = find
                if (findState != null) {
                    FindBar(
                        state = findState,
                        onQuery = manager::findQuery,
                        onNext = manager::findNext,
                        onClose = manager::closeFind,
                        modifier = Modifier.align(Alignment.BottomStart).then(if (toolbarTop) Modifier.imePadding() else Modifier)
                            .padding(8.dp).widthIn(max = 360.dp),
                    )
                }
            }
            if (!toolbarTop) bar()
        } }
        when (overlay) {
            Overlay.None -> Unit
            Overlay.Menu -> BrowserMenuGrid(menuTiles, menuInfo, onDismiss = { overlay = Overlay.None })
            Overlay.Tabs -> TabSwitcher(
                tabs = workspaceTabs,
                activeId = activeId,
                canReopen = recentlyClosed.isNotEmpty(),
                onSwitch = { manager.switchTo(it); overlay = Overlay.None },
                onClose = manager::closeTab,
                onNewTab = { manager.openNewTab(); overlay = Overlay.None },
                onNewPrivateTab = { manager.openNewTab(isPrivate = true); overlay = Overlay.None },
                onReopen = { manager.reopenClosedTab(); overlay = Overlay.None },
                onSetNeverSleep = manager::setNeverSleep,
                workspaces = workspaceState,
                onSwitchWorkspace = manager::switchWorkspace,
                onCreateWorkspace = { name, icon -> manager.createWorkspace(name, icon) },
                onEditWorkspace = { id, name, icon -> manager.updateWorkspace(id, name, icon) },
                onMoveWorkspace = manager::moveWorkspace,
                onDeleteWorkspace = manager::deleteWorkspace,
                onMoveTabToWorkspace = manager::moveTabToWorkspace,
                onUndoClose = { manager.undoCloseTab() },
                onCloseAll = { manager.closeAllTabs() },
                onDismiss = { overlay = Overlay.None },
            )
            Overlay.History -> HistoryScreen(
                history = manager.history,
                onOpen = { manager.navigate(tab.id, it); overlay = Overlay.None },
                onClose = { overlay = Overlay.None },
            )
            Overlay.Downloads -> DownloadsScreen(
                downloads = manager.downloads,
                updater = manager.updater,
                onOpenUrl = { manager.newTab(url = it); overlay = Overlay.None },
                onClose = { overlay = Overlay.None },
            )
            Overlay.Bookmarks -> BookmarksScreen(
                data = bookmarks,
                host = { manager.host },
                onEdit = manager::editBookmarks,
                onOpen = { url, newTab ->
                    if (newTab) manager.newTab(url = url) else manager.navigate(tab.id, url)
                    overlay = Overlay.None
                },
                onImport = manager::importBookmarks,
                onExport = manager::exportBookmarks,
                onClose = { overlay = Overlay.None },
            )
            Overlay.Settings -> SettingsScreen(
                manager = manager,
                onOpenUrl = { manager.newTab(url = it); overlay = Overlay.None },
                onClose = { overlay = Overlay.None },
            )
            Overlay.Notes -> NotesScreen(
                repository = manager.notes,
                initialId = notesOpenId,
                onOpenUrl = { manager.newTab(url = it); overlay = Overlay.None },
                onClose = { overlay = Overlay.None },
            )
            Overlay.Reader -> readerArticle?.let { article ->
                ReaderScreen(
                    article = article,
                    fontSize = settings.readerFontSize,
                    serif = settings.readerSerif,
                    onFontSize = { size -> manager.updateSettings { it.copy(readerFontSize = size) } },
                    onSerif = { serif -> manager.updateSettings { it.copy(readerSerif = serif) } },
                    onClose = { overlay = Overlay.None; readerArticle = null },
                )
            }
            Overlay.Palette -> {
                val initial = remember { if (tab.url.isBlank() || UrlUtils.isAboutBlankUrl(tab.url)) "" else UrlUtils.displayUrl(tab.url) }
                PaletteScreen(
                    initialText = initial,
                    toolbarTop = toolbarTop,
                    // Tabs of every workspace; the current one first, the others marked with their workspace name.
                    tabs = tabs.sortedBy { it.workspaceId != currentWorkspaceId }.map {
                        PaletteTab(it.id, it.title, it.url, it.isPrivate, if (workspaces.size > 1) workspaceNames[it.workspaceId].orEmpty() else "")
                    },
                    bookmarks = bookmarks.bookmarks.asReversed().map { PaletteLink(it.title, it.url) },
                    actions = paletteActions,
                    historyLookup = { q -> manager.paletteHistory(q).map { PaletteLink(it.title, it.url) } },
                    onResult = ::runPalette,
                    onDismiss = { overlay = Overlay.None },
                )
            }
        }
        linkMenu?.let { state ->
            val entries = LinkActions.menu(state.url, state.sourceIsPrivate).map { action ->
                MenuEntry(linkMenuLabel(action)) { if (action == LinkMenuAction.SHARE) shareText(state.url) else manager.runLinkAction(state, action) }
            }
            BrowserMenu(entries, onDismiss = manager::dismissLinkMenu, header = state.url)
        }
        if (addressMenuOpen) {
            val hasClip = context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
            val entries = buildList {
                if (isWeb) add(MenuEntry(R.string.address_copy) { manager.copyToClipboard(tab.url) })
                add(MenuEntry(R.string.address_paste_go, enabled = hasClip) { pasteAndGo() })
                add(MenuEntry(R.string.address_edit) { overlay = Overlay.Palette })
                if (isWeb) add(MenuEntry(R.string.address_share) { shareLink() })
            }
            BrowserMenu(entries, onDismiss = { addressMenuOpen = false }, header = if (isWeb) tab.url else null)
        }
        siteOptions?.let { site ->
            val entries = listOf(
                MenuEntry(R.string.link_open_tab) { manager.newTab(url = site.url) },
                MenuEntry(R.string.link_copy) { manager.copyToClipboard(site.url) },
                MenuEntry(R.string.start_remove_site) {
                    manager.hideTopSite(site.host)
                    toast(R.string.start_site_removed, site.host)
                },
            )
            BrowserMenu(entries, onDismiss = { siteOptions = null }, header = site.host)
        }
        if (textSizeOpen) {
            TextSizeDialog(
                zoom = settings.textZoom,
                onChange = { zoom -> manager.updateSettings { it.copy(textZoom = zoom) } },
                onDismiss = { textSizeOpen = false },
            )
        }
        glance?.let { g ->
            GlanceSheet(
                glance = g,
                webView = manager.glanceWebView(),
                onOpenInTab = manager::expandGlance,
                onOpenInBackground = manager::backgroundGlance,
                onClose = manager::closeGlance,
            )
        }
        if (creatingWorkspace) {
            WorkspaceEditorDialog(
                title = stringResource(R.string.workspace_new),
                initial = null,
                initialName = "",
                confirmLabel = stringResource(R.string.workspace_create),
                onConfirm = { name, icon -> manager.createWorkspace(name, icon) },
                onDismiss = { creatingWorkspace = false },
            )
        }
        (dialogs.firstOrNull { it.tabId == glance?.id } ?: dialogs.firstOrNull { it.tabId == activeId })?.let { PageDialogView(it) }
        if (!settings.onboardingDone) {
            OnboardingScreen(
                settings = settings,
                onChange = manager::updateSettings,
                onFinish = { manager.updateSettings { it.copy(onboardingDone = true) } },
            )
        }
    }
}

@androidx.annotation.StringRes
private fun linkMenuLabel(action: LinkMenuAction): Int = when (action) {
    LinkMenuAction.OPEN_TAB -> R.string.link_open_tab
    LinkMenuAction.OPEN_BACKGROUND -> R.string.link_open_background
    LinkMenuAction.OPEN_PRIVATE -> R.string.link_open_private
    LinkMenuAction.GLANCE -> R.string.link_glance
    LinkMenuAction.COPY -> R.string.link_copy
    LinkMenuAction.SHARE -> R.string.menu_share
}
