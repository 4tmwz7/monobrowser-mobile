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
import androidx.compose.ui.graphics.luminance
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
import com.voluene.monobrowser.search.SearchSuggestions
import com.voluene.monobrowser.ui.common.BackStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import com.voluene.monobrowser.ui.common.PredictiveBackContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class Overlay { None, Tabs, Menu, History, Downloads, Settings, Bookmarks, Palette, Notes, Reader, ReadingList }

/**
 * Overlay transitions: pages fade in while growing from 96 %, the menu sheet slides up from the bottom; leaving
 * reverses it. Switching straight from one overlay to another (menu -> settings) crossfades.
 */
private fun AnimatedContentTransitionScope<Overlay>.overlayTransition(from: Overlay, to: Overlay): ContentTransform {
    val pageIn = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f)
    val pageOut = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.96f)
    val sheetIn = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(160))
    val sheetOut = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(180))
    val enter = when (to) { Overlay.None -> EnterTransition.None; Overlay.Menu -> sheetIn; else -> pageIn }
    val exit = when (from) { Overlay.None -> ExitTransition.None; Overlay.Menu -> sheetOut; else -> pageOut }
    // The overlay that is coming in is drawn on top.
    return (enter togetherWith exit).apply { targetContentZIndex = if (to == Overlay.None) 0f else 1f }
}

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
    val thumbnails by manager.thumbnails.collectAsState()
    val readingItems by manager.readingList.items.collectAsState()
    val message by manager.message.collectAsState()
    val fullScreen by manager.fullScreen.collectAsState()
    val linkMenu by manager.linkMenu.collectAsState()
    val tab = tabs.firstOrNull { it.id == activeId } ?: return
    var overlay by remember { mutableStateOf(Overlay.None) }
    var notesOpenId by remember { mutableStateOf<String?>(null) }
    var creatingWorkspace by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<ReaderArticle?>(null) }
    /** The reader shows a saved copy (offline) rather than the current page. */
    var readerOffline by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var textSizeOpen by remember { mutableStateOf(false) }
    var addressMenuOpen by remember { mutableStateOf(false) }
    var siteOptions by remember { mutableStateOf<TopSite?>(null) }
    var siteInfo by remember { mutableStateOf<TabManager.SiteInfo?>(null) }
    var historySteps by remember { mutableStateOf<List<TabManager.HistoryStep>?>(null) }
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val toolbarTop = settings.toolbarPosition == ToolbarPosition.TOP

    // The page background follows the theme (blank tabs, areas that have not painted yet).
    LaunchedEffect(scheme.background) { manager.setPageBackground(scheme.background.toArgb()) }
    // Web pages follow the app's light / dark appearance (Settings -> Dark mode for websites).
    val appDark = scheme.background.luminance() < 0.5f
    LaunchedEffect(appDark, settings.webDarkMode) { manager.setWebAppearance(appDark) }
    // A blank new tab was opened or reused: go straight to typing.
    LaunchedEffect(manager) { manager.paletteRequests.collect { overlay = Overlay.Palette } }
    // Update checks begin once the first frame is on screen (non-blocking, release builds only).
    LaunchedEffect(manager) { manager.updater.start(manager.settings) }
    // The tab grid shows the current page as it is right now.
    // Full-screen overlays hide the page: no thumbnails are copied from the screen meanwhile.
    LaunchedEffect(overlay) { manager.overlayOpen = overlay != Overlay.None }

    // System back, lowest to highest priority: page history, find bar, full-screen overlays.
    // Page history, then (a link opened in a new tab) close it and return to the source tab; otherwise back leaves
    // the app, with the system's predictive back-to-home animation.
    BackHandler(enabled = overlay == Overlay.None && find == null && (tab.canGoBack || manager.backTarget(tab) != null)) {
        manager.back(tab.id)
    }
    BackHandler(enabled = overlay == Overlay.None && find != null) { manager.closeFind() }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }
    BackHandler(enabled = linkMenu != null) { manager.dismissLinkMenu() }
    BackHandler(enabled = addressMenuOpen) { addressMenuOpen = false }
    BackHandler(enabled = siteOptions != null) { siteOptions = null }
    BackHandler(enabled = textSizeOpen) { textSizeOpen = false }
    BackHandler(enabled = siteInfo != null) { siteInfo = null }
    BackHandler(enabled = historySteps != null) { historySteps = null }

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
                readerOffline = false
                overlay = Overlay.Reader
            }
        }
    }

    fun openSaved(item: com.voluene.monobrowser.reader.SavedArticleInfo) {
        scope.launch {
            val article = withContext(Dispatchers.IO) { manager.readingList.load(item.id) }
            if (article != null) {
                readerArticle = article
                readerOffline = true
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

    // Built only while the menu / palette is open (not on every progress tick of the page).
    val menuTiles: @Composable () -> List<MenuTile> = { listOf(
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
        MenuTile(R.string.menu_reopen_short, MenuIcons.Restore, enabled = recentlyClosed.isNotEmpty()) { manager.reopenClosedTab() },
        MenuTile(R.string.menu_share, Icons.Filled.Share, enabled = isWeb) { shareLink() },
        MenuTile(R.string.menu_print, MenuIcons.Print, enabled = isWeb && !tab.isLoading) { printPage() },
        MenuTile(R.string.menu_settings, Icons.Filled.Settings) { overlay = Overlay.Settings },
    ) }
    val menuInfo: @Composable () -> MenuInfo? = { when {
        !isWeb -> null
        !settings.adblockEnabled -> MenuInfo(stringResource(R.string.menu_adblock_header_off))
        siteExcepted -> MenuInfo(stringResource(R.string.menu_adblock_header_site_off), stringResource(R.string.menu_adblock_toggle_on)) { manager.toggleAdblockForSite(tab.id) }
        else -> MenuInfo(stringResource(R.string.menu_adblock_header, tab.blockedCount), stringResource(R.string.menu_adblock_toggle_off)) { manager.toggleAdblockForSite(tab.id) }
    } }

    val paletteActions: @Composable () -> List<PaletteAction> = { val labelNewTab = stringResource(R.string.tab_new)
    val labelNewPrivate = stringResource(R.string.tab_new_private)
    buildList {
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
        add(PaletteAction(PaletteActionId.READING_LIST, stringResource(R.string.reading_list), "do przeczytania czytaj pozniej offline reading list read later zapisane"))
        if (isWeb) add(PaletteAction(PaletteActionId.SAVE_FOR_LATER, stringResource(R.string.reading_save), "zapisz do przeczytania offline save read later"))
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
    } }

    fun runAction(action: PaletteAction) {
        when (action.id) {
            PaletteActionId.NEW_TAB -> manager.openNewTab()
            PaletteActionId.NEW_PRIVATE_TAB -> manager.openNewTab(isPrivate = true)
            PaletteActionId.CLOSE_TAB -> {
                val title = tab.title.ifBlank { UrlUtils.shortUrlLabel(tab.url) }
                manager.closeTab(tab.id)
                if (!tab.isPrivate && UrlUtils.isHttpUrl(tab.url)) {
                    manager.showMessage(context.getString(R.string.tabs_closed, title), context.getString(R.string.tabs_undo)) { manager.undoCloseTab() }
                }
            }
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
            PaletteActionId.READING_LIST -> overlay = Overlay.ReadingList
            PaletteActionId.SAVE_FOR_LATER -> manager.saveForLater(tab.id) { overlay = Overlay.ReadingList }
        }
    }

    fun runPalette(result: PaletteResult) {
        overlay = Overlay.None
        when (result) {
            is PaletteResult.Go -> manager.navigate(tab.id, result.input)
            is PaletteResult.Suggestion -> manager.navigate(tab.id, result.text)
            is PaletteResult.OpenTab -> manager.switchTo(result.tab.id)
            is PaletteResult.Bookmark -> manager.navigate(tab.id, result.link.url)
            is PaletteResult.History -> manager.navigate(tab.id, result.link.url)
            is PaletteResult.Run -> runAction(result.action)
        }
    }

    // A blank tab shows the start page over its (empty) WebView.
    val showStart = !tab.canGoBack && !tab.isLoading && (tab.url.isBlank() || UrlUtils.isAboutBlankUrl(tab.url))
    val topSites by produceState<List<TopSite>?>(null, showStart, settings.showTopSites, settings.hiddenTopSites, settings.pinnedSites, tab.id) {
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
                        onSiteInfo = { siteInfo = manager.siteInfo(tab.id) },
                        onHistory = { forward -> historySteps = manager.navigationHistory(tab.id, forward).takeIf { it.isNotEmpty() } },
                        fullAddress = settings.showFullAddress,
                        onBack = { manager.goBack(tab.id) },
                        onForward = { manager.goForward(tab.id) },
                        onReloadOrStop = { if (tab.isLoading) manager.stop(tab.id) else manager.reload(tab.id) },
                        onMenu = { overlay = Overlay.Menu },
                        // Copy the page as it is right now (one GPU frame), then show the grid.
                        onTabs = { manager.captureThumbnail(tab.id) { overlay = Overlay.Tabs } },
                        onNewTab = { manager.openNewTab() },
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
                        readingList = if (tab.isPrivate) emptyList() else readingItems,
                        onOpenSaved = ::openSaved,
                        onShowReadingList = { overlay = Overlay.ReadingList },
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
                message?.let { m ->
                    MessageBar(
                        m, onDismiss = manager::dismissMessage,
                        modifier = Modifier.align(if (toolbarTop) Alignment.TopCenter else Alignment.BottomCenter)
                            .then(if (toolbarTop) Modifier else Modifier.imePadding()).padding(8.dp).widthIn(max = 520.dp),
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
        // Full-screen overlays (and the menu sheet) follow the predictive back gesture.
        AnimatedContent(
            targetState = overlay,
            transitionSpec = { overlayTransition(initialState, targetState) },
            contentAlignment = Alignment.BottomCenter,
            label = "overlay",
        ) { shown ->
        if (shown != Overlay.None) PredictiveBackContainer(
            onBack = { overlay = Overlay.None },
            // Only the overlay on screen listens; one that is animating out ignores a second back.
            enabled = shown == overlay,
            style = if (shown == Overlay.Menu) BackStyle.SHEET else BackStyle.PAGE,
        ) {
        when (shown) {
            Overlay.None -> Unit
            Overlay.Menu -> BrowserMenuGrid(menuTiles(), menuInfo(), onDismiss = { overlay = Overlay.None })
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
                thumbnails = thumbnails,
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
                    onClose = { overlay = Overlay.None }, // the article stays for the exit animation
                    // Private tabs never write to disk, so nothing is saved from them.
                    saved = if (tab.isPrivate && !readerOffline) null else readingItems.any { it.url == article.url },
                    onToggleSave = {
                        if (readingItems.any { it.url == article.url }) manager.readingList.remove(article.url) else manager.readingList.save(article)
                    },
                    onOpenOriginal = if (readerOffline) ({ overlay = Overlay.None; manager.navigate(tab.id, article.url) }) else null,
                )
            }
            Overlay.ReadingList -> com.voluene.monobrowser.ui.reader.ReadingListScreen(
                items = readingItems,
                onOpen = ::openSaved,
                onRemove = { manager.readingList.remove(it.url) },
                onClose = { overlay = Overlay.None },
            )
            Overlay.Palette -> {
                val initial = remember { if (tab.url.isBlank() || UrlUtils.isAboutBlankUrl(tab.url)) "" else UrlUtils.displayUrl(tab.url) }
                PaletteScreen(
                    initialText = initial,
                    toolbarTop = toolbarTop,
                    // Tabs of every workspace; the current one first, the others marked with their workspace name.
                    // Same instances while their content is unchanged: the palette keeps its search index between keystrokes.
                    tabs = rememberEqual(tabs.sortedBy { it.workspaceId != currentWorkspaceId }.map {
                        PaletteTab(it.id, it.title, it.url, it.isPrivate, if (workspaces.size > 1) workspaceNames[it.workspaceId].orEmpty() else "")
                    }),
                    bookmarks = remember(bookmarks) { bookmarks.bookmarks.asReversed().map { PaletteLink(it.title, it.url) } },
                    actions = paletteActions(),
                    historyLookup = { q -> manager.paletteHistory(q).map { PaletteLink(it.title, it.url) } },
                    suggestionLookup = if (settings.searchSuggestions && !tab.isPrivate) {
                        { q -> withContext(Dispatchers.IO) { SearchSuggestions.fetch(settings.searchEngine, q, java.util.Locale.getDefault().language) } }
                    } else null,
                    onResult = ::runPalette,
                    onDismiss = { overlay = Overlay.None },
                )
            }
        }
        } }
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
                if (isWeb && !tab.isPrivate) {
                    add(MenuEntry(if (manager.isPinned(tab.url)) R.string.start_unpin else R.string.start_pin) { manager.togglePin(tab.url, tab.title) })
                }
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
                MenuEntry(if (site.pinned) R.string.start_unpin else R.string.start_pin) { manager.togglePin(site.url, site.title) },
                MenuEntry(R.string.start_remove_site) {
                    manager.hideTopSite(site.host)
                    toast(R.string.start_site_removed, site.host)
                },
            )
            BrowserMenu(entries, onDismiss = { siteOptions = null }, header = site.host)
        }
        historySteps?.let { steps ->
            val entries = steps.map { step ->
                MenuEntry(0, text = step.title.ifBlank { UrlUtils.shortUrlLabel(step.url) }, subtitle = UrlUtils.shortUrlLabel(step.url)) {
                    manager.goBackOrForward(tab.id, step.steps)
                }
            }
            BrowserMenu(entries, onDismiss = { historySteps = null }, header = stringResource(R.string.tab_history))
        }
        siteInfo?.let { info ->
            val host = HostUtils.hostOfUrl(tab.url)
            SiteInfoSheet(
                info = info,
                blockedCount = tab.blockedCount,
                adblockEnabled = settings.adblockEnabled,
                adblockOnForSite = !(host != null && HostUtils.isHostExcepted(host, exceptionHosts.toSet())),
                desktopMode = tab.desktopMode,
                onToggleAdblock = { manager.toggleAdblockForSite(tab.id) },
                onToggleDesktop = { manager.toggleDesktopMode(tab.id) },
                onResetPermission = { permission ->
                    manager.forgetSitePermission(tab.id, info.origin, permission)
                    siteInfo = manager.siteInfo(tab.id)
                },
                onCopy = { manager.copyToClipboard(tab.url) },
                onShare = { shareLink() },
                onDismiss = { siteInfo = null },
            )
        }
        if (textSizeOpen) {
            TextSizeDialog(
                zoom = settings.textZoom,
                onChange = { zoom -> manager.updateSettings { it.copy(textZoom = zoom) } },
                onDismiss = { textSizeOpen = false },
            )
        }
        glance?.let { g ->
            PredictiveBackContainer(onBack = manager::closeGlance, style = BackStyle.SHEET) { GlanceSheet(
                glance = g,
                webView = manager.glanceWebView(),
                onOpenInTab = manager::expandGlance,
                onOpenInBackground = manager::backgroundGlance,
                onClose = manager::closeGlance,
            ) }
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
        // Full screen video above everything else.
        fullScreen?.let { FullScreenLayer(it, onExit = manager::exitFullScreen) }
        if (!settings.onboardingDone) {
            OnboardingScreen(
                settings = settings,
                onChange = manager::updateSettings,
                onFinish = { manager.updateSettings { it.copy(onboardingDone = true) } },
            )
        }
    }
}

/** [value], but the previous instance as long as it is equal (lets callers cache by identity). */
@Composable
private fun <T> rememberEqual(value: T): T {
    val holder = remember { arrayOfNulls<Any?>(1) }
    if (holder[0] != value) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T
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
