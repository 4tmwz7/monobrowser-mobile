package com.voluene.monobrowser.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.voluene.monobrowser.R
import com.voluene.monobrowser.adblock.HostUtils
import com.voluene.monobrowser.adblock.RustAdBlocker
import com.voluene.monobrowser.data.BookmarkCodec
import com.voluene.monobrowser.data.BookmarkData
import com.voluene.monobrowser.data.BookmarkOps
import com.voluene.monobrowser.data.BookmarksIo
import com.voluene.monobrowser.data.ImportResult
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import com.voluene.monobrowser.data.Workspace
import com.voluene.monobrowser.data.WorkspaceOps
import com.voluene.monobrowser.notes.NotesRepository
import com.voluene.monobrowser.notes.NotesStore
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.settings.PageThemes
import com.voluene.monobrowser.settings.SettingsCodec
import com.voluene.monobrowser.settings.SiteTheme
import com.voluene.monobrowser.settings.SiteThemes
import com.voluene.monobrowser.settings.StartupMode
import com.voluene.monobrowser.settings.PinnedSite
import com.voluene.monobrowser.util.StateFile
import kotlinx.coroutines.flow.receiveAsFlow
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import java.io.File
import com.voluene.monobrowser.data.ClosedTab
import com.voluene.monobrowser.data.HistoryDb
import com.voluene.monobrowser.data.HistoryEntry
import com.voluene.monobrowser.data.TopSite
import com.voluene.monobrowser.data.TopSites
import com.voluene.monobrowser.reader.Reader
import com.voluene.monobrowser.reader.ReaderArticle
import com.voluene.monobrowser.reader.ReadingList
import com.voluene.monobrowser.data.PersistedTab
import com.voluene.monobrowser.data.SessionCodec
import com.voluene.monobrowser.data.SessionData
import com.voluene.monobrowser.data.SessionStore
import com.voluene.monobrowser.download.BlobDownload
import com.voluene.monobrowser.download.DownloadRepository
import android.webkit.JavascriptInterface
import java.util.UUID
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.voluene.monobrowser.adblock.Adblock
import com.voluene.monobrowser.util.UrlUtils
import com.voluene.monobrowser.util.TranslateUrl
import com.voluene.monobrowser.util.TrackingParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Tab list + live WebViews. At most [SleepPolicy.DEFAULT_MAX_LIVE] WebViews exist at once: the least recently
 * used inactive tabs are saved to disk and destroyed ("sleeping") and rebuilt when shown again.
 * Normal tabs are persisted (`session.json` + a WebView state blob per tab); private tabs never touch disk.
 */
class TabManager(app: Application) : AndroidViewModel(app), TabEvents, TabUi, com.voluene.monobrowser.media.WebMediaControl {
    /** User preferences (`settings.json`). */
    private val settingsFile = StateFile(
        File(app.filesDir, "settings.json"), AppSettings(), SettingsCodec::decode, SettingsCodec::encode, viewModelScope,
    )
    val settings: StateFlow<AppSettings> = settingsFile.state

    /** Bookmarks and folders (`bookmarks.json`). */
    private val bookmarkFile = StateFile(
        File(app.filesDir, "bookmarks.json"), BookmarkData(), BookmarkCodec::decode, BookmarkCodec::encode, viewModelScope,
        loadInBackground = true, // can be large after an import; the first frame does not need it
    )
    val bookmarks: StateFlow<BookmarkData> = bookmarkFile.state

    /** Reader-view articles saved for offline reading (`reading/<id>.json`). */
    val readingList = ReadingList(File(app.filesDir, "reading"), viewModelScope)

    /** Markdown notes (`notes/<id>.md`). */
    val notes = NotesRepository(NotesStore(File(app.filesDir, "notes")), viewModelScope)

    // adblock: adblock-rust engine (falls back to allow-all until ready)
    val adBlocker: RustAdBlocker = Adblock.get(app, settingsFile.value.wifiOnlyListUpdates, settingsFile.value.adblockEnabled)

    /** Per-site page theme overrides (`adblock/site-themes.json`, next to the adblock exceptions). */
    val siteThemes = SiteThemes(File(File(app.filesDir, "adblock"), "site-themes.json"))

    val history = HistoryDb(app)
    val downloads = DownloadRepository(app)
    /** Process-wide self-updater (see [com.voluene.monobrowser.update.UpdateManager]). */
    val updater = (app as com.voluene.monobrowser.MonoApp).updater
    private val sessionStore = SessionStore(app.filesDir)

    /** The Activity currently on screen (file chooser, runtime permissions); null while there is none. */
    var host: ActivityHost? = null

    private var nextId = 1L
    private val webViews = HashMap<Long, WebView>()
    /** The themed context each live WebView was created with (see [PageThemeContext]); removed on destroy. */
    private val pageContexts = HashMap<WebView, PageThemeContext>()
    @Volatile private var systemDark: Boolean = app.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val lastUsed = HashMap<Long, Long>()
    /** Tabs currently showing an error page -> the URL that failed. */
    private val errorUrls = HashMap<Long, String>()
    private var lastNormalActive = 0L
    private var nextDialogId = 1L
    /** Normal-tab permission decisions survive restarts (`site_permissions.json`); private ones never touch disk. */
    private val sitePermissionFile = StateFile(
        File(app.filesDir, "site_permissions.json"), emptyMap<String, Boolean>(),
        { text -> runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, Boolean>>(text) }.getOrNull() },
        { map -> kotlinx.serialization.json.Json.encodeToString(map) },
        viewModelScope,
    )
    private val sitePermissions = SitePermissionStore(sitePermissionFile.value) { map -> sitePermissionFile.update { map } }
    private val privateSitePermissions = SitePermissionStore()
    private val barScroll = BarScrollTracker()

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(0L)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()

    private val _workspaces = MutableStateFlow(listOf(WorkspaceOps.main()))
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /** Always the workspace of the active tab. */
    private val _currentWorkspaceId = MutableStateFlow(WorkspaceOps.MAIN_ID)
    val currentWorkspaceId: StateFlow<String> = _currentWorkspaceId.asStateFlow()

    /** Last active tab per workspace (restored when switching back to it). */
    private val activeByWorkspace = HashMap<String, Long>()

    /** The quick link preview (bottom sheet); its WebView lives in [webViews] under the same id, but not in [_tabs]. */
    private val _glance = MutableStateFlow<Tab?>(null)
    val glance: StateFlow<Tab?> = _glance.asStateFlow()

    private val _linkMenu = MutableStateFlow<LinkMenuState?>(null)
    val linkMenu: StateFlow<LinkMenuState?> = _linkMenu.asStateFlow()

    private val _recentlyClosed = MutableStateFlow<List<ClosedTab>>(emptyList())
    val recentlyClosed: StateFlow<List<ClosedTab>> = _recentlyClosed.asStateFlow()

    private val _dialogs = MutableStateFlow<List<PageDialog>>(emptyList())
    val dialogs: StateFlow<List<PageDialog>> = _dialogs.asStateFlow()

    private val _find = MutableStateFlow<FindState?>(null)
    val find: StateFlow<FindState?> = _find.asStateFlow()

    /** Small page previews for the tab grid (memory only, newest [MAX_THUMBNAILS] tabs). */
    private val _thumbnails = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, ImageBitmap>> = _thumbnails.asStateFlow()

    /** Draws the tab's visible page into a small bitmap for the tab grid. Blank pages and hidden views are skipped. */
    /**
     * Copies the tab's page as it is on screen (GPU copy, nothing redrawn on the main thread) and then runs [then]
     * (always, also when there was nothing to copy). Only a page that is actually visible can be copied; while a
     * full-screen overlay covers it, the copy is skipped.
     */
    fun captureThumbnail(id: Long, then: () -> Unit = {}) {
        val webView = webViews[id]
        val tab = _tabs.value.firstOrNull { it.id == id }
        val activityHost = host
        if (webView == null || tab == null || activityHost == null || overlayOpen || !webView.isShown ||
            webView.width <= 0 || webView.height <= 0 || !UrlUtils.isHttpUrl(tab.url)
        ) return then()
        val height = (webView.height * THUMBNAIL_WIDTH.toFloat() / webView.width).toInt().coerceIn(1, (THUMBNAIL_WIDTH * 1.6f).toInt())
        // The top part of the page (the bitmap is shorter than the view when the view is very tall).
        val visibleHeight = (height * webView.width.toFloat() / THUMBNAIL_WIDTH).toInt().coerceAtMost(webView.height)
        val bitmap = runCatching { Bitmap.createBitmap(THUMBNAIL_WIDTH, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return then()
        val location = IntArray(2).also(webView::getLocationInWindow)
        val source = android.graphics.Rect(location[0], location[1], location[0] + webView.width, location[1] + visibleHeight)
        activityHost.copyFromScreen(source, bitmap) { ok ->
            then()
            if (!ok) return@copyFromScreen
            // Half the memory: RGB_565 is plenty for a small greyscale preview (converted off the main thread).
            viewModelScope.launch {
                val small = withContext(Dispatchers.Default) {
                    runCatching { bitmap.copy(Bitmap.Config.RGB_565, false).also { bitmap.recycle() } }.getOrDefault(bitmap)
                }
                storeThumbnail(id, small)
            }
        }
    }

    /** Set by the UI: a full-screen overlay covers the page, so it cannot be copied from the screen. */
    @Volatile var overlayOpen: Boolean = false

    private fun storeThumbnail(id: Long, bitmap: Bitmap) {
        if (_tabs.value.none { it.id == id }) return
        _thumbnails.update { old ->
            // Re-inserting moves the tab to the end: the oldest previews are dropped first.
            val next = LinkedHashMap(old)
            next.remove(id)
            next[id] = bitmap.asImageBitmap()
            while (next.size > MAX_THUMBNAILS) next.remove(next.keys.first())
            next
        }
    }

    // --- In-app messages (a bar above the toolbar with an optional action; replaces toasts where an action helps) ---

    data class UiMessage(val id: Long, val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

    private var nextMessageId = 1L
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    fun showMessage(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _message.value = UiMessage(nextMessageId++, text, actionLabel, action)
    }

    fun dismissMessage(id: Long) {
        _message.update { if (it?.id == id) null else it }
    }

    private fun str(@StringRes res: Int, vararg args: Any) = getApplication<Application>().getString(res, *args)

    /** Finished downloads started by this app: "Downloaded x · Open". */
    private val downloadReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0) return
            viewModelScope.launch {
                val item = withContext(Dispatchers.IO) { runCatching { downloads.items().firstOrNull { it.id == id } }.getOrNull() } ?: return@launch
                when (item.status) {
                    com.voluene.monobrowser.download.DownloadStatus.DONE -> showMessage(str(R.string.download_complete, item.title), str(R.string.message_open)) {
                        val open = downloads.openIntent(id)
                        if (open == null || runCatching { getApplication<Application>().startActivity(open) }.isFailure) toast(R.string.download_failed)
                    }
                    com.voluene.monobrowser.download.DownloadStatus.FAILED -> showMessage(str(R.string.download_failed))
                    else -> Unit
                }
            }
        }
    }

    /** The page element shown full screen (a video), with the WebView's callback to leave it. */
    class FullScreen(val tabId: Long, val view: android.view.View, val callback: WebChromeClient.CustomViewCallback)

    // Declared before init: opening the first tab already touches it.
    // Declared before init (which opens the first tab and reads them).
    private val _floating = MutableStateFlow<Long?>(null)
    /** The copy opened under the floating window (closed again on expand when it was not used for anything else). */
    private var floatingCopy: Long? = null
    private val _pictureInPicture = MutableStateFlow(false)
    private val _fullScreen = MutableStateFlow<FullScreen?>(null)
    val fullScreen: StateFlow<FullScreen?> = _fullScreen.asStateFlow()

    private val _barHidden = MutableStateFlow(false)
    val barHidden: StateFlow<Boolean> = _barHidden.asStateFlow()

    /** Asks the UI to open the command palette (a blank new tab was opened or reused). */
    // A channel, not a SharedFlow: a request made before the UI collects (the app started from the widget) waits.
    private val _paletteRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val paletteRequests: kotlinx.coroutines.flow.Flow<Unit> = _paletteRequests.receiveAsFlow()

    private var persistJob: Job? = null

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = settingsFile.value
        settingsFile.update { SettingsCodec.sanitize(transform(it)) }
        val s = settingsFile.value
        adBlocker.enabled = s.adblockEnabled
        adBlocker.wifiOnlyUpdates = s.wifiOnlyListUpdates
        if (s.textZoom != before.textZoom) webViews.values.forEach { it.settings.textZoom = s.textZoom }
    }

    /** Media playing in pages, shared with WebMediaService (notification, Now Bar, lock screen, watch). */
    private val webMedia = (app as com.voluene.monobrowser.MonoApp).webMedia

    init {
        webMedia.control = this
        ContextCompat.registerReceiver(
            app, downloadReceiver, android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED, // sent by the system's download provider
        )
        // Page theme inputs changed (global mode, darken switch, per-site overrides): restyle every live page.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(settingsFile.state, siteThemes.state, siteThemes.privateState) { _, _, _ -> }
                .collect { reapplyPageThemes() }
        }
        val saved = sessionStore.load()
        if (saved != null) {
            _workspaces.value = saved.workspaces
            _currentWorkspaceId.value = saved.currentWorkspaceId
        }
        if (saved != null && saved.tabs.isNotEmpty() && settingsFile.value.startupMode == StartupMode.NEWTAB) {
            // "On startup: new tab": the previous tabs stay reachable through "Reopen closed tab".
            nextId = saved.nextTabId
            val previous = saved.tabs.filter { it.url.isNotBlank() }.map { ClosedTab(it.url, it.title) }.reversed()
            _recentlyClosed.value = (previous + saved.recentlyClosed).take(SessionCodec.MAX_RECENTLY_CLOSED)
            sessionStore.pruneTabStates(emptySet())
            newTab()
        } else if (saved != null && saved.tabs.isNotEmpty()) {
            nextId = saved.nextTabId
            _tabs.value = saved.tabs.map {
                Tab(
                    id = it.id, title = it.title, url = it.url, neverSleep = it.neverSleep, sleeping = true,
                    workspaceId = it.workspaceId, desktopMode = it.desktopMode,
                )
            }
            _recentlyClosed.value = saved.recentlyClosed
            activeByWorkspace.putAll(saved.activeTabIdByWorkspace)
            sessionStore.pruneTabStates(saved.tabs.map { it.id }.toSet())
            if (saved.activeTabId != 0L) activate(saved.activeTabId) else newTab() // the current workspace has no tab
        } else {
            newTab()
        }
    }

    // --- Tab list ---

    /**
     * Opens a tab in [workspaceId] (default: the current one). With [background] the tab is added without being
     * shown: a normal one stays asleep and loads [url] when first shown; a private one (which never sleeps) loads now.
     */
    fun newTab(
        isPrivate: Boolean = false,
        url: String? = null,
        background: Boolean = false,
        workspaceId: String = _currentWorkspaceId.value,
        openerId: Long? = null,
        /** No URL at all, not even the homepage (a window a page is about to load into). */
        blank: Boolean = false,
    ): Long {
        val workspace = workspaceId.takeIf { id -> _workspaces.value.any { it.id == id } } ?: WorkspaceOps.MAIN_ID
        val target = if (blank) null else url ?: settingsFile.value.newTabUrl
        if (background) {
            val lazy = !isPrivate && target != null
            val tab = Tab(id = nextId++, isPrivate = isPrivate, workspaceId = workspace, url = if (lazy) target.orEmpty() else "", sleeping = lazy)
            _tabs.update { it + tab }
            if (isPrivate && target != null) navigate(tab.id, target)
            schedulePersist()
            return tab.id
        }
        val tab = Tab(id = nextId++, isPrivate = isPrivate, workspaceId = workspace, openerId = openerId)
        _tabs.update { it + tab }
        activate(tab.id)
        if (target != null) navigate(tab.id, target)
        return tab.id
    }

    /**
     * User-initiated "new tab" (menu, tab switcher, palette): switches to an existing empty new tab of the same
     * kind instead of creating another one ([NewTabPolicy]). Returns the tab that is now active.
     */
    /** [focusAddress]: null = the setting (only for a blank new tab); true = always straight to typing (widget). */
    fun openNewTab(isPrivate: Boolean = false, focusAddress: Boolean? = null): Long {
        // De-duplication is per workspace: an empty tab of another workspace is not reused.
        val reusable = NewTabPolicy.findReusable(
            WorkspaceTabs.inWorkspace(_tabs.value, _currentWorkspaceId.value), _activeTabId.value, isPrivate, settingsFile.value.newTabUrl,
        )
        val id = if (reusable != null) {
            if (reusable.id != _activeTabId.value) activate(reusable.id) else showBar()
            reusable.id
        } else {
            newTab(isPrivate)
        }
        val focus = focusAddress ?: (settingsFile.value.newTabFocusAddress && settingsFile.value.newTabUrl == null)
        if (focus) _paletteRequests.trySend(Unit)
        return id
    }

    fun switchTo(id: Long) {
        if (id == _floating.value) { expandFloating(); return }
        if (_tabs.value.any { it.id == id }) activate(id)
    }

    /** Swipe on the address bar: -1 = previous tab, +1 = next tab (within the current workspace). */
    fun switchRelative(delta: Int) {
        WorkspaceTabs.relative(_tabs.value, _activeTabId.value, delta)?.let(::activate)
    }

    fun closeTab(id: Long) {
        val closing = _tabs.value.firstOrNull { it.id == id } ?: return
        val neighbour = WorkspaceTabs.neighbourAfterClose(_tabs.value, id)
        lastClosed = null
        if (!closing.isPrivate && closing.url.isNotBlank() && SessionCodec.isRestorableUrl(closing.url) &&
            !UrlUtils.isAboutBlankUrl(closing.url)
        ) {
            val failed = errorUrls[id] ?: closing.url
            val entry = ClosedTab(failed, closing.title)
            _recentlyClosed.update { (listOf(entry) + it).take(SessionCodec.MAX_RECENTLY_CLOSED) }
            lastClosed = LastClosed(
                entry, _tabs.value.indexOfFirst { it.id == id }, closing.workspaceId,
                wasActive = _activeTabId.value == id, desktopMode = closing.desktopMode,
            )
        }
        cancelDialogsOf(id)
        if (_floating.value == id) _floating.value = null
        if (floatingCopy == id) floatingCopy = null
        _thumbnails.update { it - id }
        _loadProgress.update { it - id }
        webMedia.clear(id)
        pendingBlocked.remove(id)
        webViews.remove(id)?.let { destroy(it, closing.isPrivate) }
        lastUsed.remove(id)
        errorUrls.remove(id)
        sessionStore.deleteTabState(id)
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (activeByWorkspace[closing.workspaceId] == id) activeByWorkspace.remove(closing.workspaceId)
        if (closing.isPrivate && remaining.none { it.isPrivate } && _glance.value?.isPrivate != true) {
            clearPrivateData()
            privateSitePermissions.clear()
        }
        if (_activeTabId.value == id) {
            // Stay in the workspace of the closed tab; an emptied workspace gets a fresh tab.
            if (neighbour == null) newTab(workspaceId = closing.workspaceId) else activate(neighbour)
        } else {
            schedulePersist()
        }
    }

    /** The tab closed last (for "Undo" in the tab switcher): it comes back at the same place. */
    private data class LastClosed(val tab: ClosedTab, val index: Int, val workspaceId: String, val wasActive: Boolean, val desktopMode: Boolean)
    private var lastClosed: LastClosed? = null

    /** Undoes the last [closeTab] (normal tabs with a page only). Returns false when there is nothing to undo. */
    fun undoCloseTab(): Boolean {
        val last = lastClosed ?: return false
        lastClosed = null
        _recentlyClosed.update { list -> if (list.firstOrNull() == last.tab) list.drop(1) else list }
        val workspace = last.workspaceId.takeIf { id -> _workspaces.value.any { it.id == id } } ?: WorkspaceOps.MAIN_ID
        val tab = Tab(
            id = nextId++, url = last.tab.url, title = last.tab.title, workspaceId = workspace, sleeping = true,
            desktopMode = last.desktopMode,
        )
        _tabs.update { list -> list.toMutableList().apply { add(last.index.coerceIn(0, size), tab) } }
        if (last.wasActive) activate(tab.id) else schedulePersist()
        return true
    }

    /** Closes every tab of a workspace; it is left with one fresh blank tab. */
    fun closeAllTabs(workspaceId: String = _currentWorkspaceId.value) {
        val ids = WorkspaceTabs.inWorkspace(_tabs.value, workspaceId).map { it.id }
        if (ids.isEmpty()) return
        // A fresh tab first: closing the active tab would otherwise wake every neighbour in turn.
        if (_activeTabId.value in ids || _currentWorkspaceId.value == workspaceId) newTab(workspaceId = workspaceId)
        ids.forEach(::closeTab)
        lastClosed = null
    }

    /** Re-opens the most recently closed normal tab. Returns false when there is none. */
    fun reopenClosedTab(): Boolean {
        lastClosed = null
        val closed = _recentlyClosed.value.firstOrNull() ?: return false
        _recentlyClosed.update { it.drop(1) }
        val id = newTab(url = closed.url)
        update(id) { it.copy(title = closed.title) }
        return true
    }

    /** A row of the start page's "Recently closed": loads it into [tabId] and drops it from the stack. */
    fun reopenClosedIn(tabId: Long, closed: ClosedTab) {
        _recentlyClosed.update { list -> list.filterIndexed { i, c -> !(c == closed && i == list.indexOf(closed)) } }
        navigate(tabId, closed.url)
        schedulePersist()
    }

    fun setNeverSleep(id: Long, value: Boolean) {
        update(id) { it.copy(neverSleep = value) }
        schedulePersist()
    }

    private fun activate(id: Long) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (_activeTabId.value != id) {
            closeFind()
            exitFullScreen()
            captureThumbnail(_activeTabId.value) // the tab being left, as it looks now (skipped under an overlay)
            webViews[_activeTabId.value]?.let(::pauseIfSilent)
        }
        _activeTabId.value = id
        _currentWorkspaceId.value = tab.workspaceId
        activeByWorkspace[tab.workspaceId] = id
        lastUsed[id] = SystemClock.elapsedRealtime()
        if (!tab.isPrivate) lastNormalActive = id
        ensureLive(id)?.onResume()
        enforceSleepPolicy()
        showBar()
        schedulePersist()
    }

    // --- Navigation ---

    /** Input from the address bar (URL or search query). */
    /** [url] without tracking parameters when the setting is on. */
    fun cleanUrl(url: String): String = if (settingsFile.value.stripTracking) TrackingParams.clean(url) else url

    fun navigate(id: Long, input: String) {
        val url = UrlUtils.resolveInput(input, settingsFile.value.searchTemplate)?.let(::cleanUrl) ?: return
        errorUrls.remove(id)
        val webView = ensureLive(id, restore = false)
        applyPageTheme(id, url) // before the load: the new page must start with the right colour scheme
        webView?.loadUrl(url)
    }

    /** Shared or selected text from another app: a URL is opened, anything else is searched, in a new tab. */
    fun openExternalInput(input: String) {
        val url = UrlUtils.resolveInput(input, settingsFile.value.searchTemplate)?.let(::cleanUrl) ?: return
        if (UrlUtils.isHttpUrl(url)) newTab(url = url)
    }

    fun goBack(id: Long) = goHistory(id, -1)

    /** One entry of a tab's back / forward list; [steps] is what goBackOrForward needs (negative = back). */
    data class HistoryStep(val steps: Int, val title: String, val url: String)

    /** The tab's pages behind (forward = false, nearest first) or ahead of the current one, at most [limit]. */
    fun navigationHistory(id: Long, forward: Boolean, limit: Int = 15): List<HistoryStep> {
        val list = webViews[id]?.copyBackForwardList() ?: return emptyList()
        val current = list.currentIndex
        val range = if (forward) (current + 1 until list.size) else (current - 1 downTo 0)
        return range.take(limit).mapNotNull { i ->
            val item = list.getItemAtIndex(i) ?: return@mapNotNull null
            val url = errorUrls[id]?.takeIf { i == current } ?: item.url
            if (url.startsWith("data:")) null else HistoryStep(i - current, item.title.orEmpty(), url)
        }
    }

    fun goBackOrForward(id: Long, steps: Int) {
        val webView = webViews[id] ?: return
        if (!webView.canGoBackOrForward(steps)) return
        val history = webView.copyBackForwardList()
        history.getItemAtIndex(history.currentIndex + steps)?.url?.let { applyPageTheme(id, it) }
        webView.goBackOrForward(steps)
    }

    /** The tab back returns to when [tab] has no history of its own (its opener, if still open), else null. */
    fun backTarget(tab: Tab): Long? =
        tab.openerId?.takeIf { opener -> !tab.canGoBack && opener != tab.id && _tabs.value.any { it.id == opener } }

    /** System back: page history first, then "close this link tab and return to the tab it came from". */
    fun back(id: Long) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (tab.canGoBack) return goBack(id)
        val opener = backTarget(tab) ?: return
        closeTab(id)
        switchTo(opener)
    }
    fun goForward(id: Long) = goHistory(id, 1)


    /** Back / forward: the page theme of the target site is applied before the history entry is shown. */
    private fun goHistory(id: Long, delta: Int) {
        val webView = webViews[id] ?: return
        val history = webView.copyBackForwardList()
        history.getItemAtIndex(history.currentIndex + delta)?.url?.let { applyPageTheme(id, it) }
        if (delta < 0) webView.goBack() else webView.goForward()
    }
    fun stop(id: Long) { webViews[id]?.stopLoading() }

    fun reload(id: Long) {
        val failed = errorUrls.remove(id)
        if (failed != null) {
            applyPageTheme(id, failed)
            webViews[id]?.loadUrl(failed)
        } else {
            webViews[id]?.reload()
        }
    }

    override fun retryFailedLoad(tabId: Long) = reload(tabId)

    // --- Full screen video ---


    override fun showCustomView(tabId: Long, view: android.view.View, callback: WebChromeClient.CustomViewCallback) {
        // Only the tab on screen may go full screen; a second request replaces the first.
        if (tabId != _activeTabId.value) return callback.onCustomViewHidden()
        _fullScreen.value?.callback?.onCustomViewHidden()
        _fullScreen.value = FullScreen(tabId, view, callback)
    }

    override fun hideCustomView() {
        _fullScreen.value = null
    }

    /** The user left full screen (back, or switching tabs): tell the page. */
    fun exitFullScreen() {
        val current = _fullScreen.value ?: return
        _fullScreen.value = null
        runCatching { current.callback.onCustomViewHidden() }
    }

    // --- New windows (target=_blank, window.open) ---

    /** `window.close()`: only a tab that a page opened (it has an opener) may close itself. */
    override fun closeWindow(tabId: Long) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        if (tab.openerId == null) return
        val opener = backTarget(tab.copy(canGoBack = false))
        closeTab(tabId)
        opener?.let(::switchTo)
    }

    override fun createWindow(tabId: Long, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
        val source = tab(tabId) ?: return false
        if (!isUserGesture) {
            showMessage(str(R.string.popup_blocked))
            return false
        }
        val id = newTab(
            isPrivate = source.isPrivate, workspaceId = source.workspaceId, blank = true,
            openerId = tabId.takeIf { _tabs.value.any { t -> t.id == it } },
        )
        val webView = webViews[id] ?: return false
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = webView
        resultMsg.sendToTarget()
        return true
    }

    /** The WebView of a tab (rebuilt from saved state when the tab was sleeping). */
    fun webViewFor(id: Long): WebView? = webViews[id] ?: ensureLive(id)

    // --- WebView lifecycle / sleeping ---

    private fun ensureLive(id: Long, restore: Boolean = true): WebView? {
        webViews[id]?.let { return it }
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return null
        val webView = createWebView(tab)
        webViews[id] = webView
        update(id) { it.copy(sleeping = false) }
        if (restore && !tab.isPrivate) {
            val state = sessionStore.readTabState(id)?.let(WebViewState::toBundle)
            val restored = state?.let { webView.restoreState(it) }
            if (restored != null) {
                onNavState(id, webView.url, webView.canGoBack(), webView.canGoForward())
            } else if (tab.url.isNotBlank()) {
                webView.loadUrl(tab.url)
            }
        }
        return webView
    }

    private fun sleep(id: Long) {
        val webView = webViews.remove(id) ?: return
        webMedia.clear(id)
        val tab = _tabs.value.firstOrNull { it.id == id }
        if (tab != null && !tab.isPrivate) saveTabState(id, webView)
        cancelDialogsOf(id)
        destroy(webView, wasPrivate = false)
        update(id) { it.copy(sleeping = true, progress = 100) }
    }

    private fun enforceSleepPolicy() {
        val active = _activeTabId.value
        val live = webViews.keys.mapNotNull { id ->
            val tab = _tabs.value.firstOrNull { it.id == id } ?: return@mapNotNull null
            LiveTab(id, lastUsed[id] ?: 0L, id == active, protected = tab.neverSleep || tab.isPrivate || id == _floating.value)
        }
        SleepPolicy.pickVictims(live).forEach(::sleep)
    }

    private fun saveTabState(id: Long, webView: WebView) {
        WebViewState.capture(webView)?.let { sessionStore.writeTabState(id, it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): WebView {
        // Application context: the WebView outlives the Activity (config changes are handled manually).
        // Dialogs, file choosers and permission requests are routed through [TabUi] / [host] instead.
        // The page theme (prefers-color-scheme) comes from the theme of this context, not from the browser UI.
        val pageContext = PageThemeContext(getApplication())
        pageContext.dark = isPageDark(tab.isPrivate, tab.url)
        val webView = MonoWebView(pageContext)
        pageContexts[webView] = pageContext
        webView.keepPlayingInBackground = {
            webMedia.state.value?.let { it.tabId == tab.id && it.playing } == true || _floating.value == tab.id
        }
        // Profile must be set before any other WebView call.
        if (tab.isPrivate && privateProfilesSupported) {
            ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            WebViewCompat.setProfile(webView, PRIVATE_PROFILE)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(true) // every request still goes through the per-origin prompt
            // target=_blank and window.open become new tabs (see createWindow); scripts cannot open windows on their own.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = settingsFile.value.textZoom
            // Pinch-to-zoom without the old on-screen +/- buttons.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (tab.isPrivate && !privateProfilesSupported) {
                // Fallback: no separate profile. TODO: real isolation; cookies/storage are shared with normal tabs.
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        }
        if (tab.desktopMode) applyDesktopMode(webView, true)
        applyPageTheme(webView, pageContext, pageContext.dark, force = true)
        // Renderers of tabs that are not on screen get a lower priority (the system may reclaim them first).
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        webView.webViewClient = MonoWebViewClient(tab.id, adBlocker, this, this)
        adBlocker.attach(webView) // adblock: cosmetic
        webView.webChromeClient = MonoWebChromeClient(tab.id, this, this)
        webView.addJavascriptInterface(BlobBridge(tab.id), BlobDownload.BRIDGE)
        webView.addJavascriptInterface(MediaBridge(tab.id), com.voluene.monobrowser.media.WebMediaHub.BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, com.voluene.monobrowser.media.WebMediaHub.SCRIPT, setOf("*")) }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(tab.id, url, userAgent, contentDisposition, mimeType)
        }
        webView.setFindListener { ordinal, matches, _ -> onFindResult(tab.id, ordinal, matches) }
        webView.setOnLongClickListener { handleLongPress(tab.id, webView) }
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> onScrolled(tab.id, scrollY - oldScrollY, scrollY) }
        return webView
    }

    /** The WebView's own (mobile) user agent; never read from a WebView that may already be in desktop mode. */
    private val mobileUserAgent: String by lazy { WebSettings.getDefaultUserAgent(getApplication()) }

    private fun applyDesktopMode(webView: WebView, desktop: Boolean) {
        val settings = webView.settings
        val mobile = mobileUserAgent
        settings.userAgentString = if (desktop) DesktopMode.userAgent(mobile) else null // null = WebView default
        // Desktop pages are laid out at their full width and zoomed out to fit, like a desktop browser.
        settings.useWideViewPort = desktop
        settings.loadWithOverviewMode = desktop
    }

    /** "Desktop site" on/off for a tab; the page is reloaded with the new user agent. */
    fun toggleDesktopMode(id: Long) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val desktop = !tab.desktopMode
        update(id) { it.copy(desktopMode = desktop) }
        webViews[id]?.let { webView ->
            applyDesktopMode(webView, desktop)
            reload(id)
        }
        schedulePersist()
        toast(if (desktop) R.string.desktop_mode_on else R.string.desktop_mode_off)
    }

    private val privateProfilesSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun destroy(webView: WebView, wasPrivate: Boolean) {
        if (wasPrivate && !privateProfilesSupported) {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
        }
        pageContexts.remove(webView)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Called after the last private tab is closed. */
    private fun clearPrivateData() {
        siteThemes.clearPrivate()
        if (privateProfilesSupported) {
            // Deleting a profile requires that no WebView uses it (all private WebViews are destroyed by now).
            runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        }
        // else: TODO cookies/storage of the fallback cannot be cleared without touching normal tabs.
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(downloadReceiver) }
        if (webMedia.control === this) webMedia.control = null
        flush()
        webViews.values.forEach { it.destroy() }
        webViews.clear()
        pageContexts.clear()
        history.close()
    }

    // --- Sync (a file in the user's cloud: Google Drive, OneDrive ...) ---

    private fun <T> onMain(block: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else java.util.concurrent.FutureTask(block).also { mainHandler.post(it) }.get()

    val sync = com.voluene.monobrowser.sync.SyncManager(
        app, viewModelScope,
        listOf(
            object : com.voluene.monobrowser.sync.SyncSource {
                override val name = "bookmarks"
                override suspend fun read(): Map<String, String> {
                    val (folders, bookmarks) = com.voluene.monobrowser.sync.BookmarkSync.toCollections(bookmarkFile.value)
                    return folders.mapKeys { "f\u001E" + it.key } + bookmarks.mapKeys { "b\u001E" + it.key }
                }
                override suspend fun apply(merged: Map<String, String>) {
                    val folders = merged.filterKeys { it.startsWith("f\u001E") }.mapKeys { it.key.removePrefix("f\u001E") }
                    val bookmarks = merged.filterKeys { it.startsWith("b\u001E") }.mapKeys { it.key.removePrefix("b\u001E") }
                    onMain { bookmarkFile.update { com.voluene.monobrowser.sync.BookmarkSync.fromCollections(it, folders, bookmarks, System.currentTimeMillis()) } }
                }
            },
            object : com.voluene.monobrowser.sync.SyncSource {
                override val name = "pinned"
                override suspend fun read() = settingsFile.value.pinnedSites.associate { it.url to it.title }
                override suspend fun apply(merged: Map<String, String>) = onMain {
                    updateSettings { s ->
                        val kept = s.pinnedSites.filter { it.url in merged }.map { it.copy(title = merged.getValue(it.url)) }
                        val added = merged.filterKeys { url -> kept.none { it.url == url } }.map { (url, title) -> PinnedSite(url, title) }
                        s.copy(pinnedSites = kept + added)
                    }
                }
            },
            object : com.voluene.monobrowser.sync.SyncSource {
                override val name = "reading"
                override suspend fun read() = readingList.snapshot()
                override suspend fun apply(merged: Map<String, String>) = readingList.applySynced(merged)
            },
            object : com.voluene.monobrowser.sync.SyncSource {
                override val name = "notes"
                override suspend fun read() = notes.snapshot()
                override suspend fun apply(merged: Map<String, String>) = notes.applySynced(merged)
            },
        ),
        uri = { settingsFile.value.syncUri },
    )

    /** The user picked (or created) the sync file. */
    fun useSyncFile(picked: Uri) {
        if (!sync.adopt(picked)) return toast(R.string.sync_error_access)
        updateSettings { it.copy(syncUri = picked.toString()) }
        sync.sync(force = true)
    }

    fun stopSync() {
        sync.release(settingsFile.value.syncUri)
        updateSettings { it.copy(syncUri = "") }
    }

    // --- Memory pressure ---

    /**
     * The system is short of memory: drop the tab previews and put every tab that is not on screen to sleep (its
     * state is saved and it wakes when shown). Tabs marked "never sleep", private tabs and tabs playing media stay.
     */
    fun onLowMemory() {
        _thumbnails.value = emptyMap()
        val active = _activeTabId.value
        webViews.keys.toList().forEach { id ->
            val tab = _tabs.value.firstOrNull { it.id == id } ?: return@forEach
            if (id == active || id == _floating.value || tab.neverSleep || tab.isPrivate) return@forEach
            val webView = webViews[id] ?: return@forEach
            isPlayingMedia(webView) { playing -> if (!playing && id != _activeTabId.value) sleep(id) }
        }
    }

    // --- Background work: pause what nobody sees, unless it is playing sound or video ---

    // Also media the page never put in the document (new Audio()), tracked by WebMediaHub.SCRIPT.
    private val mediaPlayingScript =
        "(function(){if(window.__monoMediaPlaying)return window.__monoMediaPlaying();var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++){if(!m[i].paused&&!m[i].ended&&!m[i].muted)return true}return false})()"

    /** [onResult] is true when the page plays audible media (a tab playing music must keep playing). */
    private fun isPlayingMedia(webView: WebView, onResult: (Boolean) -> Unit) {
        runCatching { webView.evaluateJavascript(mediaPlayingScript) { onResult(it == "true") } }.onFailure { onResult(false) }
    }

    /** A tab that left the screen stops animations and plugins (WebView.onPause) unless it plays media. */
    private fun pauseIfSilent(webView: WebView) {
        isPlayingMedia(webView) { playing ->
            val stillHidden = webViews[_activeTabId.value] !== webView
            if (!playing && stillHidden && webViews.containsValue(webView)) webView.onPause()
        }
    }

    private var appVisible = true

    /**
     * The app went to the background (or came back). In the background JavaScript timers of all tabs are paused
     * (WebView.pauseTimers is process-wide) unless some tab plays media, and the visible tab is paused too.
     */
    fun onAppVisible(visible: Boolean) {
        // Pick up what other devices changed when coming back (also at launch); send what changed here when leaving.
        sync.sync(force = !visible)
        if (visible == appVisible) return
        appVisible = visible
        val active = webViews[_activeTabId.value]
        guardPlayback(!visible || _pictureInPicture.value)
        if (visible) {
            webViews.values.firstOrNull()?.resumeTimers()
            active?.onResume()
            return
        }
        val live = webViews.values.toList()
        if (live.isEmpty()) return
        var pending = live.size
        var anyPlaying = false
        live.forEach { webView ->
            isPlayingMedia(webView) { playing ->
                anyPlaying = anyPlaying || playing
                if (--pending == 0 && !appVisible && !anyPlaying) {
                    active?.onPause()
                    webViews.values.firstOrNull()?.pauseTimers()
                }
            }
        }
    }

    // --- Persistence ---

    private fun snapshot(): SessionData {
        val persisted = _tabs.value.filterNot { it.isPrivate }
            .map {
                PersistedTab(
                    it.id, errorUrls[it.id] ?: it.url, it.title, isPrivate = false, neverSleep = it.neverSleep,
                    workspaceId = it.workspaceId, desktopMode = it.desktopMode,
                )
            }
        // A private tab is not saved: fall back to the last normal tab of the same workspace, then to any normal one.
        val active = listOf(_activeTabId.value, activeByWorkspace[_currentWorkspaceId.value] ?: 0L, lastNormalActive)
            .firstOrNull { id -> persisted.any { it.id == id } } ?: 0L
        return SessionData(
            tabs = persisted,
            activeTabId = active,
            nextTabId = nextId,
            recentlyClosed = _recentlyClosed.value,
            workspaces = _workspaces.value,
            currentWorkspaceId = _currentWorkspaceId.value,
            activeTabIdByWorkspace = activeByWorkspace.filter { (_, tabId) -> persisted.any { it.id == tabId } },
        )
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DELAY_MS)
            val data = snapshot()
            withContext(Dispatchers.IO) { sessionStore.save(data) }
        }
    }

    /** Writes everything now (Activity stopped / ViewModel cleared): session file + WebView state of live tabs. */
    fun flush() {
        persistJob?.cancel()
        _tabs.value.filter { !it.isPrivate }.forEach { tab -> webViews[tab.id]?.let { saveTabState(tab.id, it) } }
        sessionStore.save(snapshot())
        notes.flush()
    }

    // --- Events from WebViews (main thread, except onBlocked which may come from IO threads) ---

    private fun update(id: Long, transform: (Tab) -> Tab) {
        _tabs.update { list -> list.map { if (it.id == id) transform(it) else it } }
        _glance.update { glance -> if (glance?.id == id) transform(glance) else glance }
    }

    /** A tab of the list or the glance (which has the same event plumbing but is not in the list). */
    private fun tab(id: Long): Tab? = _tabs.value.firstOrNull { it.id == id } ?: _glance.value?.takeIf { it.id == id }

    override fun onNavigationRequest(tabId: Long, url: String) = applyPageTheme(tabId, url)

    override fun onPageStarted(tabId: Long, url: String) {
        applyPageTheme(tabId, url)
        if (errorUrls[tabId] != url) errorUrls.remove(tabId)
        pendingBlocked[tabId]?.set(0) // counts of the previous page
        webMedia.clear(tabId) // a new page: the old one's media is gone
        val keepTitle = errorUrls[tabId] == url
        _loadProgress.update { it + (tabId to 0) }
        update(tabId) { it.copy(url = url, title = if (keepTitle) it.title else "", blockedCount = 0, progress = 0, readerAvailable = false) }
        if (tabId == _activeTabId.value) showBar()
    }

    override fun onPageFinished(tabId: Long, url: String) {
        update(tabId) { it.copy(url = url, progress = 100) }
        // Without document-start scripts the media script goes in when the page has loaded.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            webViews[tabId]?.let { runCatching { it.evaluateJavascript(com.voluene.monobrowser.media.WebMediaHub.SCRIPT, null) } }
        }
        probeReader(tabId, url)
        val tab = tab(tabId)
        if (tab != null && !tab.isPrivate && tabId != _glance.value?.id && UrlUtils.isHttpUrl(url) && tabId !in errorUrls) {
            val title = tab.title
            viewModelScope.launch(Dispatchers.IO) { runCatching { history.recordVisit(url, title) } }
        }
        schedulePersist()
    }

    override fun onNavState(tabId: Long, url: String?, canGoBack: Boolean, canGoForward: Boolean) =
        update(tabId) { it.copy(url = url ?: it.url, canGoBack = canGoBack, canGoForward = canGoForward) }

    override fun onTitle(tabId: Long, title: String) {
        if (tabId in errorUrls) return // keep the error page title
        update(tabId) { it.copy(title = title) }
        val tab = tab(tabId)
        if (tab != null && !tab.isPrivate && tabId != _glance.value?.id && UrlUtils.isHttpUrl(tab.url)) {
            val url = tab.url
            viewModelScope.launch(Dispatchers.IO) { runCatching { history.updateTitle(url, title) } }
        }
        schedulePersist()
    }

    private val _loadProgress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /** Live load percentage per tab (read by the progress lines only, in their draw phase). */
    val loadProgress: StateFlow<Map<Long, Int>> = _loadProgress.asStateFlow()

    override fun onProgress(tabId: Long, progress: Int) {
        _loadProgress.update { it + (tabId to progress) }
        // The tab itself only changes when it starts or stops loading.
        val loading = progress < 100
        if (tab(tabId)?.isLoading != loading) update(tabId) { it.copy(progress = if (loading) 0 else 100) }
    }

    // Blocked requests are reported from network threads, often hundreds per page: count them atomically and
    // publish the totals at most every BLOCKED_FLUSH_MS, on the main thread (no list copy per request, no races).
    private val pendingBlocked = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>()
    private val blockedFlushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBlocked(tabId: Long) {
        pendingBlocked.getOrPut(tabId) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        if (blockedFlushQueued.compareAndSet(false, true)) mainHandler.postDelayed(::flushBlocked, BLOCKED_FLUSH_MS)
    }

    private fun flushBlocked() {
        blockedFlushQueued.set(false)
        val added = HashMap<Long, Int>()
        pendingBlocked.forEach { (id, counter) -> counter.getAndSet(0).takeIf { it > 0 }?.let { added[id] = it } }
        if (added.isEmpty()) return
        _tabs.update { list -> list.map { t -> added[t.id]?.let { t.copy(blockedCount = t.blockedCount + it) } ?: t } }
        _glance.update { g -> g?.let { added[it.id]?.let { n -> it.copy(blockedCount = it.blockedCount + n) } ?: it } }
    }

    override fun onScrolled(tabId: Long, dy: Int, scrollY: Int) {
        if (tabId != _activeTabId.value) return
        barScroll.onScroll(dy, scrollY, _barHidden.value, SystemClock.uptimeMillis())?.let { _barHidden.value = it }
    }

    override fun onFindResult(tabId: Long, activeOrdinal: Int, matches: Int) {
        if (tabId != _activeTabId.value) return
        _find.update { it?.copy(active = if (matches > 0) activeOrdinal + 1 else 0, total = matches) }
    }

    // --- Bottom bar visibility ---

    fun showBar() {
        barScroll.reset()
        _barHidden.value = false
    }

    // --- Find in page ---

    fun openFind() {
        showBar()
        _find.value = FindState()
    }

    fun findQuery(query: String) {
        val webView = webViews[_activeTabId.value]
        _find.value = FindState(query = query)
        if (query.isEmpty()) webView?.clearMatches() else webView?.findAllAsync(query)
    }

    fun findNext(forward: Boolean) { webViews[_activeTabId.value]?.findNext(forward) }

    fun closeFind() {
        if (_find.value == null) return
        webViews[_activeTabId.value]?.clearMatches()
        _find.value = null
    }

    // --- Command palette data ---

    /** History matches for the palette (blocking IO: call from a background dispatcher). */
    fun paletteHistory(query: String): List<HistoryEntry> =
        runCatching { if (query.isBlank()) history.recent(limit = 8) else history.suggest(query, limit = 8) }.getOrDefault(emptyList())

    // --- Page theme (independent of the browser UI theme) ---

    /** System night mode changed (pushed by the UI): restyle pages that follow the system. */
    fun setSystemDark(dark: Boolean) {
        if (systemDark == dark) return
        systemDark = dark
        reapplyPageThemes()
    }

    private fun isPageDark(isPrivate: Boolean, url: String?): Boolean {
        val override = siteThemes.lookup(HostUtils.hostOfUrl(url), forPrivate = isPrivate)
        return PageThemes.isDark(settingsFile.value.pageThemeMode, systemDark, override)
    }

    /** Applies [dark] to one WebView: theme of its context, colour shown before the first paint, algorithmic darkening. */
    private fun applyPageTheme(webView: WebView, context: PageThemeContext, dark: Boolean, force: Boolean = false) {
        val changed = context.dark != dark
        context.dark = dark
        webView.setBackgroundColor(if (dark) PAGE_BACKGROUND_DARK else PAGE_BACKGROUND_LIGHT)
        // The darkening flag can change without [dark] changing (the "darken pages" setting), so keep it in sync always.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            val darken = dark && settingsFile.value.darkenPages
            // Guarded: some WebView versions report the feature but throw UnsupportedOperationException.
            runCatching {
                if (WebSettingsCompat.isAlgorithmicDarkeningAllowed(webView.settings) != darken) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, darken)
                }
            }
        }
        if (!changed && !force) return
        // Changing a setting makes WebView recompute its preferences, which re-reads the theme of [context]. The
        // darkening flag alone does not always change, so flip a harmless one back and forth as well.
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = !mediaPlaybackRequiresUserGesture
            mediaPlaybackRequiresUserGesture = !mediaPlaybackRequiresUserGesture
        }
    }

    /** Re-evaluates the page theme of one tab for the page it shows (or is about to show, [url]). */
    private fun applyPageTheme(tabId: Long, url: String? = null) {
        val webView = webViews[tabId] ?: return
        val context = pageContexts[webView] ?: return
        val tab = tab(tabId) ?: return
        applyPageTheme(webView, context, isPageDark(tab.isPrivate, url ?: errorUrls[tabId] ?: tab.url))
    }

    private fun reapplyPageThemes() {
        webViews.keys.toList().forEach { applyPageTheme(it) }
    }

    /** Menu / palette: sets (or with null clears) the page theme of the tab's site; private tabs only change memory. */
    fun setSiteTheme(tabId: Long, theme: SiteTheme?) {
        val tab = tab(tabId) ?: return
        val host = HostUtils.hostOfUrl(errorUrls[tabId] ?: tab.url) ?: return
        siteThemes.set(host, theme, forPrivate = tab.isPrivate)
    }

    // --- Workspaces ---

    private val mainWorkspaceLabel: String get() = getApplication<Application>().getString(R.string.workspace_main)

    fun workspaceName(workspace: Workspace): String = WorkspaceOps.displayName(workspace, mainWorkspaceLabel)

    /** Switches to the last active tab of the workspace; an empty workspace gets a new tab. */
    fun switchWorkspace(id: String): Boolean {
        if (_workspaces.value.none { it.id == id }) return false
        if (id == _currentWorkspaceId.value) return true
        val target = WorkspaceTabs.restoreTarget(_tabs.value, id, activeByWorkspace[id])
        if (target != null) activate(target) else newTab(workspaceId = id)
        return true
    }

    /** Creates a workspace and switches to it. Null at the limit. */
    fun createWorkspace(name: String?, icon: String? = null): Workspace? {
        val context = getApplication<Application>()
        val added = WorkspaceOps.add(
            _workspaces.value, name, icon, context.getString(R.string.workspace_default_name), mainWorkspaceLabel,
        )
        if (added == null) {
            toast(R.string.workspace_limit)
            return null
        }
        val (list, workspace) = added
        _workspaces.value = list
        switchWorkspace(workspace.id)
        schedulePersist()
        return workspace
    }

    fun updateWorkspace(id: String, name: String? = null, icon: String? = null) {
        // The localised default name of the main workspace is stored as "" so it follows the language.
        val stored = if (id == WorkspaceOps.MAIN_ID && name?.trim() == mainWorkspaceLabel) "" else name
        WorkspaceOps.update(_workspaces.value, id, stored, icon)?.let {
            _workspaces.value = it
            schedulePersist()
        }
    }

    fun moveWorkspace(id: String, delta: Int) {
        WorkspaceOps.move(_workspaces.value, id, delta)?.let {
            _workspaces.value = it
            schedulePersist()
        }
    }

    /** Deletes a workspace (not the main one). Its tabs are closed or join the main workspace. */
    fun deleteWorkspace(id: String, closeTabs: Boolean) {
        val remaining = WorkspaceOps.remove(_workspaces.value, id) ?: return
        if (_currentWorkspaceId.value == id) switchWorkspace(WorkspaceOps.MAIN_ID)
        if (closeTabs) {
            WorkspaceTabs.inWorkspace(_tabs.value, id).forEach { closeTab(it.id) }
        } else {
            _tabs.update { WorkspaceTabs.moveAll(it, id) }
        }
        activeByWorkspace.remove(id)
        _workspaces.value = remaining
        schedulePersist()
    }

    fun moveTabToWorkspace(tabId: Long, workspaceId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        val target = _workspaces.value.firstOrNull { it.id == workspaceId } ?: return
        if (tab.workspaceId == workspaceId) return
        val source = tab.workspaceId
        _tabs.update { WorkspaceTabs.moveTab(it, tabId, workspaceId) }
        if (activeByWorkspace[source] == tabId) activeByWorkspace.remove(source)
        if (_activeTabId.value == tabId) {
            // Stay in the source workspace: another of its tabs (or a new one) becomes active.
            val fallback = WorkspaceTabs.restoreTarget(_tabs.value, source, null)
            if (fallback != null) activate(fallback) else newTab(workspaceId = source)
        }
        schedulePersist()
        toast(R.string.workspace_moved, workspaceName(target))
    }

    // --- Glance (link preview) and link menu ---

    /** Long-press on a link in a page: runs the configured action. */
    private fun handleLongPress(tabId: Long, webView: WebView): Boolean {
        val hit = webView.hitTestResult
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE && hit.type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
        val fallback = hit.extra.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE }
        // For image links `extra` is the image; the real link target comes from the focus node.
        val message = Handler(Looper.getMainLooper()) { msg ->
            onLinkLongPress(tabId, msg.data?.getString("url") ?: fallback)
            true
        }.obtainMessage()
        webView.requestFocusNodeHref(message)
        return true
    }

    fun onLinkLongPress(tabId: Long, url: String?) {
        val target = url?.trim()
        when (LinkActions.onLongPress(target, settingsFile.value.linkLongPress)) {
            LongPressOutcome.GLANCE -> openGlance(target!!, tabId)
            LongPressOutcome.MENU -> _linkMenu.value = LinkMenuState(target!!, tabId, tab(tabId)?.isPrivate == true)
            LongPressOutcome.NONE -> Unit
        }
    }

    fun dismissLinkMenu() { _linkMenu.value = null }

    fun runLinkAction(state: LinkMenuState, action: LinkMenuAction) {
        _linkMenu.value = null
        when (action) {
            LinkMenuAction.OPEN_TAB -> openLink(state.url, state.sourceTabId, background = false, forcePrivate = false)
            LinkMenuAction.OPEN_BACKGROUND -> openLink(state.url, state.sourceTabId, background = true, forcePrivate = false)
            LinkMenuAction.OPEN_PRIVATE -> openLink(state.url, state.sourceTabId, background = false, forcePrivate = true)
            LinkMenuAction.GLANCE -> openGlance(state.url, state.sourceTabId)
            LinkMenuAction.COPY -> copyToClipboard(state.url)
            LinkMenuAction.SHARE -> Unit // needs an Activity context: handled by the UI
        }
    }

    /** New tab for a link; it lands in the workspace of the source tab and is private when the source is. */
    private fun openLink(url: String, sourceTabId: Long, background: Boolean, forcePrivate: Boolean) {
        val source = tab(sourceTabId)
        val isPrivate = LinkActions.opensPrivate(source?.isPrivate == true, forcePrivate)
        val id = newTab(
            isPrivate = isPrivate, url = url, background = background, workspaceId = source?.workspaceId ?: _currentWorkspaceId.value,
            openerId = sourceTabId.takeIf { !background && source != null && _tabs.value.any { it.id == sourceTabId } },
        )
        if (background) showMessage(str(R.string.link_opened_background), str(R.string.message_switch)) { switchTo(id) }
    }

    /** Copies [text] and confirms with a toast. */
    fun copyToClipboard(text: String) {
        val text = cleanUrl(text)
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(null, text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.link_copied)
    }

    /** Opens the preview sheet for [url]; replaces an open one (one glance at a time). */
    fun openGlance(url: String, sourceTabId: Long) {
        if (!UrlUtils.isHttpUrl(url)) return
        val source = tab(sourceTabId) // before closing: the source may be the open glance itself
        closeGlance()
        val glance = Tab(
            id = nextId++, url = url, progress = 0, isPrivate = source?.isPrivate == true,
            workspaceId = source?.workspaceId ?: _currentWorkspaceId.value,
        )
        _glance.value = glance
        val webView = createWebView(glance) // same profile as the source tab, adblock attached like any tab
        webViews[glance.id] = webView
        applyPageTheme(glance.id, url)
        webView.loadUrl(url)
    }

    /** The glance's WebView (null when none is open). */
    fun glanceWebView(): WebView? = _glance.value?.let { webViews[it.id] }

    fun closeGlance() {
        val glance = _glance.value ?: return
        _glance.value = null
        cancelDialogsOf(glance.id)
        webViews.remove(glance.id)?.let { destroy(it, glance.isPrivate) }
        errorUrls.remove(glance.id)
        if (glance.isPrivate && _tabs.value.none { it.isPrivate }) {
            clearPrivateData()
            privateSitePermissions.clear()
        }
    }

    /** "Open in tab": the glance's WebView becomes a real tab without reloading. */
    fun expandGlance() {
        val source = _activeTabId.value
        val glance = adoptGlance() ?: return
        update(glance.id) { it.copy(openerId = source) }
        activate(glance.id)
    }

    /** "Open in background": the same, but the current tab stays in front. */
    fun backgroundGlance() {
        val glance = adoptGlance() ?: return
        enforceSleepPolicy()
        schedulePersist()
        showMessage(str(R.string.link_opened_background), str(R.string.message_switch)) { switchTo(glance.id) }
    }

    /** Moves the glance (and its live WebView) into the tab list. */
    private fun adoptGlance(): Tab? {
        val glance = _glance.value ?: return null
        _glance.value = null
        lastUsed[glance.id] = SystemClock.elapsedRealtime()
        _tabs.update { it + glance }
        if (!glance.isPrivate && UrlUtils.isHttpUrl(glance.url) && glance.id !in errorUrls) {
            val url = glance.url
            val title = glance.title
            viewModelScope.launch(Dispatchers.IO) { runCatching { history.recordVisit(url, title) } }
        }
        return glance
    }

    // --- Site information (the lock in the address bar) ---

    /** What the site information sheet shows for a tab; null for pages that are not web pages. */
    data class SiteInfo(
        val origin: String,
        val host: String,
        val secure: Boolean,
        val certificateIssuer: String?,
        val certificateValidUntil: Long?,
        val permissions: List<SitePermissionStore.Decision>,
    )

    fun siteInfo(tabId: Long): SiteInfo? {
        val tab = tab(tabId) ?: return null
        val url = errorUrls[tabId] ?: tab.url
        val origin = SitePermissionStore.originOf(url) ?: return null
        val certificate = webViews[tabId]?.certificate?.takeIf { url.startsWith("https", ignoreCase = true) }
        return SiteInfo(
            origin = origin,
            host = SitePermissionStore.hostOf(origin).removePrefix("www."),
            secure = origin.startsWith("https://"),
            certificateIssuer = certificate?.issuedBy?.let { it.oName.ifBlank { it.cName } }?.ifBlank { null },
            certificateValidUntil = certificate?.validNotAfterDate?.time,
            permissions = storeFor(tabId).decisionsFor(origin),
        )
    }

    fun forgetSitePermission(tabId: Long, origin: String, permission: SitePermission) = storeFor(tabId).forget(origin, permission)

    // --- Start page ---

    /** Most visited sites for the start page (blocking IO: call from a background dispatcher). */
    fun topSites(limit: Int = TopSites.DEFAULT_LIMIT): List<TopSite> {
        val since = System.currentTimeMillis() - TOP_SITES_WINDOW_MS
        val counts = runCatching { history.mostVisited(since) }.getOrDefault(emptyList())
        val pinned = settingsFile.value.pinnedSites.mapNotNull { p -> TopSites.hostKey(p.url)?.let { TopSite(p.url, p.title, it) } }
        return TopSites.merge(pinned, TopSites.pick(counts, settingsFile.value.hiddenTopSites.toSet(), limit))
    }

    /** Removes a site from the start page (it comes back only after "Restore removed sites"). */
    fun hideTopSite(host: String) = updateSettings { s ->
        s.copy(hiddenTopSites = s.hiddenTopSites + host, pinnedSites = s.pinnedSites.filter { TopSites.hostKey(it.url) != host })
    }

    fun isPinned(url: String): Boolean = settingsFile.value.pinnedSites.any { it.url == url }

    /** Pins a page to the start page, or unpins it. */
    fun togglePin(url: String, title: String) {
        if (!UrlUtils.isHttpUrl(url)) return
        val wasPinned = isPinned(url)
        updateSettings { s ->
            s.copy(pinnedSites = if (wasPinned) s.pinnedSites.filterNot { it.url == url } else s.pinnedSites + PinnedSite(url, title))
        }
        if (!wasPinned) showMessage(getApplication<Application>().getString(R.string.start_pinned))
    }

    // --- Reader view ---

    /** Extracts the active page's text; [onResult] gets null when the page is not an article (main thread). */
    fun extractArticle(tabId: Long, onResult: (ReaderArticle?) -> Unit) {
        val webView = webViews[tabId]
        val tab = tab(tabId)
        if (webView == null || tab == null || !UrlUtils.isHttpUrl(tab.url) || tabId in errorUrls) {
            onResult(null)
            return
        }
        webView.evaluateJavascript(Reader.SCRIPT) { raw -> onResult(Reader.parse(raw)) }
    }

    /** "Save for offline reading" without opening reader view. */
    fun saveForLater(tabId: Long, onShowList: () -> Unit) {
        extractArticle(tabId) { article ->
            if (article == null) {
                toast(R.string.reader_unavailable)
            } else {
                readingList.save(article)
                showMessage(str(R.string.reading_saved), str(R.string.message_view), onShowList)
            }
        }
    }

    /** Opens the page through Google Translate, into the phone's language. */
    fun translatePage(tabId: Long) {
        val tab = tab(tabId) ?: return
        val url = errorUrls[tabId] ?: tab.url
        if (!UrlUtils.isHttpUrl(url)) return
        navigate(tabId, TranslateUrl.of(url, Locale.getDefault().language))
    }

    /** Asks the page whether it is an article (a few microseconds of JS); the address bar then offers reader view. */
    private fun probeReader(tabId: Long, url: String) {
        if (!UrlUtils.isHttpUrl(url) || tabId in errorUrls) return
        val webView = webViews[tabId] ?: return
        runCatching {
            webView.evaluateJavascript(Reader.PROBE_SCRIPT) { result ->
                val available = result == "true"
                if (tab(tabId)?.let { it.url == url && it.readerAvailable != available } == true) update(tabId) { it.copy(readerAvailable = available) }
            }
        }
    }

    // --- Notes ---

    /** Appends the page (title + URL) to the most recent note; returns false for pages that must not be saved. */
    fun addPageToNote(tabId: Long): Boolean {
        val tab = tab(tabId) ?: return false
        val url = errorUrls[tabId] ?: tab.url
        if (tab.isPrivate || !UrlUtils.isHttpUrl(url)) return false
        val id = notes.appendPage(tab.title, url)
        val title = notes.titleOf(id).ifBlank { getApplication<Application>().getString(R.string.notes_untitled) }
        toast(R.string.notes_page_added, title)
        return true
    }

    // --- Bookmarks ---

    fun toggleBookmark(tabId: Long) {
        val tab = tab(tabId) ?: return
        if (tab.isPrivate || !UrlUtils.isHttpUrl(tab.url)) return
        val url = errorUrls[tabId] ?: tab.url
        bookmarkFile.update {
            if (BookmarkOps.findByUrl(it, url) != null) BookmarkOps.removeByUrl(it, url)
            else BookmarkOps.addBookmark(it, url, tab.title, null, System.currentTimeMillis())
        }
    }

    fun editBookmarks(transform: (BookmarkData) -> BookmarkData) = bookmarkFile.update(transform)

    fun importBookmarks(text: String): Result<ImportResult> = runCatching {
        val entries = BookmarksIo.parseImport(text)
        val result = BookmarkOps.importInto(bookmarkFile.value, entries, System.currentTimeMillis())
        bookmarkFile.update { result.data }
        result
    }

    fun exportBookmarks(json: Boolean): String {
        val portable = BookmarkOps.toPortable(bookmarkFile.value)
        return if (json) BookmarksIo.exportJson(portable) else BookmarksIo.exportHtml(portable)
    }

    // --- Per-site adblock ---

    /** Menu toggle: disables/enables adblock for the tab's site and reloads it. Returns true when now disabled. */
    fun toggleAdblockForSite(tabId: Long): Boolean? {
        val tab = tab(tabId) ?: return null
        val host = HostUtils.hostOfUrl(errorUrls[tabId] ?: tab.url) ?: return null
        val result = adBlocker.exceptions.toggleSite(host)
        if (result != null) reload(tabId)
        return result
    }

    // --- Settings: privacy ---

    val sitePermissionList: List<SitePermissionStore.Decision> get() = sitePermissions.decisionsList()

    fun forgetSitePermission(origin: String, permission: SitePermission) = sitePermissions.forget(origin, permission)

    fun resetSitePermissions() {
        sitePermissions.clear()
        privateSitePermissions.clear()
    }

    /** "Clear browsing data": history is wiped on a background thread, WebView data on the main thread. */
    fun clearBrowsingData(history: Boolean, cookies: Boolean, cache: Boolean, siteStorage: Boolean) {
        if (history) viewModelScope.launch(Dispatchers.IO) { runCatching { this@TabManager.history.clear() } }
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        if (cache) {
            val live = webViews.entries.firstOrNull { (id, _) -> tab(id)?.isPrivate == false }?.value
            if (live != null) {
                live.clearCache(true)
            } else {
                // No normal tab has a WebView right now: a throwaway one reaches the shared HTTP cache.
                WebView(getApplication()).apply { clearCache(true); destroy() }
            }
        }
        if (siteStorage) {
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(getApplication()).clearHttpAuthUsernamePassword()
            webViews.values.forEach { it.clearFormData() }
        }
        toast(R.string.settings_clear_done)
    }

    // --- Page dialogs: permissions and JS alert/confirm/prompt ---

    private fun addDialog(factory: (Long) -> PageDialog) {
        val dialog = factory(nextDialogId++)
        _dialogs.update { it + dialog }
    }

    private fun removeDialog(id: Long) {
        _dialogs.update { list -> list.filterNot { it.id == id } }
        // A queued prompt for something the user has just decided is answered by that decision.
        _dialogs.value.filterIsInstance<PageDialog.Permission>()
            .filter { storeFor(it.tabId).undecided(it.origin, it.wants).isEmpty() }
            .forEach { it.resolve(false) }
    }

    private fun cancelDialogsOf(tabId: Long) {
        _dialogs.value.filter { it.tabId == tabId }.forEach { it.cancel() }
    }

    private fun storeFor(tabId: Long) = if (tab(tabId)?.isPrivate == true) privateSitePermissions else sitePermissions

    override fun showAlert(tabId: Long, origin: String, message: String, onClose: () -> Unit) =
        addDialog { id -> PageDialog.Alert(id, tabId, origin, message) { removeDialog(id); onClose() } }

    override fun showConfirm(tabId: Long, origin: String, message: String, onResult: (Boolean) -> Unit) =
        addDialog { id -> PageDialog.Confirm(id, tabId, origin, message) { removeDialog(id); onResult(it) } }

    override fun showPrompt(tabId: Long, origin: String, message: String, default: String, onResult: (String?) -> Unit) =
        addDialog { id -> PageDialog.Prompt(id, tabId, origin, message, default) { removeDialog(id); onResult(it) } }

    override fun requestSitePermissions(
        tabId: Long,
        origin: String,
        wanted: List<SitePermission>,
        onResult: (Set<SitePermission>) -> Unit,
    ) {
        val store = storeFor(tabId)
        val proceed = {
            val allowed = wanted.filter { store[origin, it] == true }
            if (allowed.isEmpty()) onResult(emptySet()) else requestRuntimePermissions(allowed, onResult)
        }
        val undecided = store.undecided(origin, wanted)
        if (undecided.isEmpty()) {
            proceed()
            return
        }
        addDialog { id ->
            PageDialog.Permission(id, tabId, origin, undecided) { allow ->
                // Only still-undecided permissions are written, so a duplicate prompt cannot overwrite a decision.
                store.set(origin, store.undecided(origin, undecided), allow)
                removeDialog(id)
                proceed()
            }
        }
    }

    private fun androidPermissions(permission: SitePermission): List<String> = when (permission) {
        SitePermission.CAMERA -> listOf(Manifest.permission.CAMERA)
        SitePermission.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        SitePermission.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    /** The user allowed the site; now the app itself needs the Android runtime permission. */
    private fun requestRuntimePermissions(allowed: List<SitePermission>, onResult: (Set<SitePermission>) -> Unit) {
        fun finish() = onResult(allowed.filter { p -> androidPermissions(p).any(::isGranted) }.toSet())
        val missing = allowed.flatMap(::androidPermissions).distinct().filterNot(::isGranted)
        val activityHost = host
        if (missing.isEmpty() || activityHost == null) finish() else activityHost.requestPermissions(missing) { finish() }
    }

    // --- File chooser, external links, downloads, error pages ---

    override fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val activityHost = host ?: return false
        activityHost.pickFiles(params) { callback.onReceiveValue(it) }
        return true
    }

    /** A link tapped in a page: when it carries tracking parameters, load the clean URL instead. */
    override fun cleanNavigation(tabId: Long, url: String): Boolean {
        val clean = cleanUrl(url)
        if (clean === url) return false
        webViews[tabId]?.loadUrl(clean) ?: return false
        return true
    }

    override fun openExternalLink(tabId: Long, action: LinkAction, hasGesture: Boolean): Boolean {
        if (action == LinkAction.Load) return false
        if (!hasGesture) return true // no app launches from scripts/redirects without a tap
        if (action is LinkAction.Ignore) {
            toast(R.string.link_unsupported)
            return true
        }
        when (val result = ExternalLauncher.launch(getApplication(), action)) {
            LaunchResult.Opened -> Unit
            is LaunchResult.LoadFallback -> webViews[tabId]?.loadUrl(result.url)
            LaunchResult.Failed -> toast(R.string.link_no_app)
        }
        return true
    }

    override fun startDownload(tabId: Long, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("data:", ignoreCase = true) -> viewModelScope.launch {
                val name = withContext(Dispatchers.IO) { downloads.saveDataUrl(url) }
                if (name != null) toast(R.string.download_saved, name) else toast(R.string.download_failed)
            }
            url.startsWith("blob:", ignoreCase = true) -> startBlobDownload(tabId, url, contentDisposition)
            UrlUtils.isHttpUrl(url) -> withStoragePermission { withNotificationPermission {
                val referer = webViews[tabId]?.url
                runCatching {
                    downloads.enqueue(url, userAgent, contentDisposition, mimeType, cookiesFor(tabId, url), referer)
                }.onSuccess { (_, name) ->
                    toast(R.string.download_started, name)
                    com.voluene.monobrowser.download.DownloadProgressService.start(getApplication())
                }
                    .onFailure { toast(R.string.download_failed) }
            } }
            else -> toast(R.string.download_failed)
        }
    }

    /**
     * Android 13+: asks once for notifications, so the download progress (notification, Now Bar) can be shown;
     * [then] runs whatever the answer (without it the system's download notification shows the progress).
     */
    private fun withNotificationPermission(then: () -> Unit) {
        val app = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return then()
        val host = host ?: return then()
        host.requestPermissions(listOf(android.Manifest.permission.POST_NOTIFICATIONS)) { then() }
    }

    /** One-time tokens of `blob:` downloads in flight (main thread only). */
    private class PendingBlob(val tabId: Long, val name: String?)
    private val pendingBlobs = HashMap<String, PendingBlob>()

    /** Asks the page to read the blob and send it back through [BlobBridge]. */
    private fun startBlobDownload(tabId: Long, url: String, contentDisposition: String?) {
        val webView = webViews[tabId] ?: return toast(R.string.download_failed)
        val token = UUID.randomUUID().toString()
        pendingBlobs[token] = PendingBlob(tabId, BlobDownload.fileNameFromDisposition(contentDisposition))
        webView.evaluateJavascript(BlobDownload.script(url, token), null)
    }

    private fun onBlobData(tabId: Long, token: String, dataUrl: String) {
        val pending = pendingBlobs[token]?.takeIf { it.tabId == tabId } ?: return
        pendingBlobs.remove(token)
        withStoragePermission {
            viewModelScope.launch {
                val name = withContext(Dispatchers.IO) { downloads.saveDataUrl(dataUrl, pending.name) }
                if (name != null) toast(R.string.download_saved, name) else toast(R.string.download_failed)
            }
        }
    }

    private fun onBlobFailed(tabId: Long, token: String, reason: String) {
        if (pendingBlobs[token]?.tabId != tabId) return
        pendingBlobs.remove(token)
        toast(if (reason == "size") R.string.download_too_large else R.string.download_failed)
    }

    // --- Media playing in pages ---

    /** The app is a floating video window: only the video is shown. */
    val pictureInPicture: StateFlow<Boolean> = _pictureInPicture.asStateFlow()

    /** The video that a floating window would show: playing, in the current tab. */
    fun pictureInPictureVideo(requirePlaying: Boolean = true): com.voluene.monobrowser.media.WebMediaState? =
        webMedia.state.value?.takeIf {
            (it.playing || !requirePlaying) && it.isVideo && (it.tabId == _activeTabId.value || it.tabId == _floating.value)
        }

    /** What plays in the pages (for the floating window's play button and shape). */
    val mediaState: StateFlow<com.voluene.monobrowser.media.WebMediaState?> get() = webMedia.state

    fun togglePlayback(tabId: Long) {
        if (webMedia.state.value?.let { it.tabId == tabId && it.playing } == true) pause(tabId) else play(tabId)
    }

    /**
     * The tab shown in the floating video window over the browser (its video fills it); the browser below goes on
     * with a copy of that page that does not play by itself, so its comments and links can be read meanwhile.
     */
    val floating: StateFlow<Long?> = _floating.asStateFlow()

    /** Moves the playing video of the current tab into the floating window. False when nothing plays here. */
    fun canFloatVideo(): Boolean =
        webMedia.state.value?.let { it.playing && it.isVideo && it.tabId == _activeTabId.value && it.tabId != _floating.value } == true

    fun floatVideo(): Boolean {
        if (!canFloatVideo()) return false
        val video = webMedia.state.value ?: return false
        val tab = tab(video.tabId) ?: return false
        if (_floating.value != null) closeFloating()
        exitFullScreen()
        _floating.value = tab.id
        webViews[tab.id]?.let { web ->
            runCatching { web.evaluateJavascript("window.__monoPip && window.__monoPip(true); window.__monoGuard && window.__monoGuard(true)", null) }
        }
        val copy = newTab(isPrivate = tab.isPrivate, url = tab.url, workspaceId = tab.workspaceId, openerId = tab.id)
        // The copy shows the page; only the user starts playback there.
        webViews[copy]?.settings?.mediaPlaybackRequiresUserGesture = true
        floatingCopy = copy
        return true
    }

    /** Back to the floating tab, full size. The copy goes away when it still shows the same page. */
    fun expandFloating() {
        val id = _floating.value ?: return
        unfloat(id)
        val copy = floatingCopy
        floatingCopy = null
        val source = tab(id)
        activate(id)
        tab(copy ?: return)?.let { c -> if (source != null && c.url == source.url) closeTab(c.id) }
    }

    /** The window's close button: playback stops, the tab stays where it was. */
    fun closeFloating() {
        val id = _floating.value ?: return
        pause(id)
        unfloat(id)
        floatingCopy = null
    }

    private fun unfloat(id: Long) {
        _floating.value = null
        if (_pictureInPicture.value) return
        webViews[id]?.let { web ->
            runCatching { web.evaluateJavascript("window.__monoPip && window.__monoPip(false); window.__monoGuard && window.__monoGuard(${!appVisible})", null) }
        }
    }

    /** A tab's WebView if it is live (never wakes a sleeping tab). */
    fun liveWebView(id: Long): WebView? = webViews[id]

    /** The floating tab's WebView (for the window); null when there is none. */
    fun floatingWebView(): WebView? = _floating.value?.let { webViews[it] }

    fun enterPictureInPicture(): Boolean = host?.enterPictureInPicture() == true

    /** The window became (or stopped being) a floating video window: the video fills the page meanwhile. */
    fun onPictureInPicture(active: Boolean) {
        if (_pictureInPicture.value == active) return
        _pictureInPicture.value = active
        guardPlayback(active || !appVisible)
        val tabId = webMedia.state.value?.tabId ?: _activeTabId.value
        // A page element already in full screen is shown as it is.
        if (active && _fullScreen.value != null) return
        val wholePage = settingsFile.value.pipWholePage
        if (tabId == _floating.value) {
            // The floating window shows only the video; the system window shows the whole page when so set.
            if (wholePage) webViews[tabId]?.let { runCatching { it.evaluateJavascript("window.__monoPip && window.__monoPip(${!active})", null) } }
            return
        }
        if (wholePage) return
        webViews[tabId]?.let { runCatching { it.evaluateJavascript("window.__monoPip && window.__monoPip($active)", null) } }
    }

    /** Pages may not pause their own playback while nobody can see them (background, floating window). */
    private fun guardPlayback(on: Boolean) {
        webViews.forEach { (id, web) ->
            val guard = on || id == _floating.value
            runCatching { web.evaluateJavascript("window.__monoGuard && window.__monoGuard($guard)", null) }
        }
    }

    /** The floating window was closed (swiped away): like closing a video player, playback stops. */
    fun onPictureInPictureDismissed() {
        if (_floating.value != null) { closeFloating(); return }
        webMedia.state.value?.let { pause(it.tabId) }
    }

    /** The page reports play / pause (WebMediaHub.SCRIPT); called on a WebView thread. */
    private inner class MediaBridge(private val tabId: Long) {
        @JavascriptInterface
        fun state(
            playing: Boolean, title: String, artist: String, canNext: Boolean, canPrevious: Boolean,
            artwork: String, positionMs: Double, durationMs: Double, rate: Double, videoWidth: Double, videoHeight: Double,
        ) {
            val at = SystemClock.elapsedRealtime()
            mainHandler.post {
                onWebMedia(
                    tabId, playing, title, artist, canNext, canPrevious, artwork, positionMs.toLong(), durationMs.toLong(), at, rate.toFloat(),
                    videoWidth.toInt().coerceIn(0, 16384), videoHeight.toInt().coerceIn(0, 16384),
                )
            }
        }
    }

    private fun onWebMedia(
        tabId: Long, playing: Boolean, title: String, artist: String, canNext: Boolean, canPrevious: Boolean,
        artwork: String, positionMs: Long, durationMs: Long, at: Long, rate: Float, videoWidth: Int = 0, videoHeight: Int = 0,
    ) {
        val tab = tab(tabId) ?: return
        val current = webMedia.state.value
        // A page that pauses does not take over the controls from another one that plays.
        if (!playing && current?.tabId != tabId) return
        webMedia.update(
            com.voluene.monobrowser.media.WebMediaState(
                tabId, playing, title.take(200).ifBlank { tab.title }, artist.take(100), canNext, canPrevious, tab.isPrivate,
                artworkUrl = artwork.take(2048).takeIf { UrlUtils.isHttpUrl(it) }.orEmpty(),
                positionMs = positionMs.coerceAtLeast(0), durationMs = durationMs.coerceAtLeast(0), updatedAt = at,
                rate = rate.takeIf { it in 0.1f..16f } ?: 1f,
                videoWidth = videoWidth, videoHeight = videoHeight,
            ),
        )
        // Stopped while the app is in the background: the page may be hidden from Chromium now (saves battery).
        if (!playing && !appVisible) (webViews[tabId] as? MonoWebView)?.applyRealVisibility()
        if (playing) {
            val app = getApplication<Application>()
            // Allowed while the app is in front (the usual case: the user pressed play); otherwise already running.
            runCatching { ContextCompat.startForegroundService(app, android.content.Intent(app, com.voluene.monobrowser.WebMediaService::class.java)) }
        }
    }

    override fun play(tabId: Long) {
        mainHandler.post { (webViews[tabId] as? MonoWebView)?.onPlayRequested() }
        mediaCommand(tabId, "window.__monoMediaPlay && window.__monoMediaPlay()")
    }
    override fun pause(tabId: Long) = mediaCommand(tabId, "window.__monoMediaPause && window.__monoMediaPause()")
    override fun skip(tabId: Long, forward: Boolean) = mediaCommand(tabId, "window.__monoMediaSkip && window.__monoMediaSkip($forward)")
    override fun seekTo(tabId: Long, positionMs: Long) =
        mediaCommand(tabId, "window.__monoMediaSeek && window.__monoMediaSeek(${positionMs.coerceAtLeast(0)})")

    private fun mediaCommand(tabId: Long, script: String) {
        mainHandler.post {
            val webView = webViews[tabId] ?: return@post
            // Commands from the lock screen / Now Bar arrive while the app is in the background: wake the page first.
            webView.resumeTimers()
            webView.onResume()
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }

    /** JavaScript side of `blob:` downloads; calls arrive on a WebView thread and are handed to the main thread. */
    private inner class BlobBridge(private val tabId: Long) {
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun deliver(token: String, dataUrl: String, @Suppress("UNUSED_PARAMETER") mimeType: String) {
            main.post { onBlobData(tabId, token, dataUrl) }
        }

        @JavascriptInterface
        fun fail(token: String, reason: String) {
            main.post { onBlobFailed(tabId, token, reason) }
        }
    }

    /** Public Downloads needs a storage permission only before Android 10. */
    private fun withStoragePermission(action: () -> Unit) {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val activityHost = host
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || isGranted(permission) || activityHost == null) {
            action()
        } else {
            activityHost.requestPermissions(listOf(permission)) { if (it[permission] == true) action() else toast(R.string.download_failed) }
        }
    }

    private fun cookiesFor(tabId: Long, url: String): String? {
        val webView = webViews[tabId]
        return if (webView != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.getProfile(webView).cookieManager.getCookie(url)
        } else {
            CookieManager.getInstance().getCookie(url)
        }
    }

    override fun onLoadError(tabId: Long, failedUrl: String, kind: ErrorKind) {
        val webView = webViews[tabId] ?: return
        val context = getApplication<Application>()
        val host = UrlUtils.shortUrlLabel(failedUrl).substringBefore('/')
        val title = context.getString(kind.title)
        val html = ErrorPageHtml.render(
            title = title,
            hint = context.getString(kind.hint, host),
            retryLabel = context.getString(R.string.error_retry),
            failedUrl = failedUrl,
            language = Locale.getDefault().language,
        )
        errorUrls[tabId] = failedUrl
        update(tabId) { it.copy(url = failedUrl, title = title, progress = 100) }
        // Posted: replacing the page from inside the error callback is not reliable.
        webView.post { webViews[tabId]?.loadDataWithBaseURL(failedUrl, html, "text/html", "utf-8", failedUrl) }
    }

    private fun toast(@StringRes res: Int, vararg args: Any) =
        Toast.makeText(getApplication(), getApplication<Application>().getString(res, *args), Toast.LENGTH_SHORT).show()

    private companion object {
        const val PRIVATE_PROFILE = "private"
        const val PERSIST_DELAY_MS = 1_000L
        const val TOP_SITES_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
        const val THUMBNAIL_WIDTH = 300
        const val BLOCKED_FLUSH_MS = 250L
        const val MAX_THUMBNAILS = 30
        const val PAGE_BACKGROUND_LIGHT = 0xFFFFFFFF.toInt()
        const val PAGE_BACKGROUND_DARK = 0xFF000000.toInt()
    }
}
