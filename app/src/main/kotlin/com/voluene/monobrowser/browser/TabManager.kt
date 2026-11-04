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
import com.voluene.monobrowser.settings.SettingsCodec
import com.voluene.monobrowser.settings.StartupMode
import com.voluene.monobrowser.settings.Themes
import com.voluene.monobrowser.settings.WebDarkMode
import android.view.ContextThemeWrapper
import androidx.webkit.WebSettingsCompat
import com.voluene.monobrowser.util.StateFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.voluene.monobrowser.adblock.Adblock
import com.voluene.monobrowser.util.UrlUtils
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
class TabManager(app: Application) : AndroidViewModel(app), TabEvents, TabUi {
    /** User preferences (`settings.json`). */
    private val settingsFile = StateFile(
        File(app.filesDir, "settings.json"), AppSettings(), SettingsCodec::decode, SettingsCodec::encode, viewModelScope,
    )
    val settings: StateFlow<AppSettings> = settingsFile.state

    /** Bookmarks and folders (`bookmarks.json`). */
    private val bookmarkFile = StateFile(
        File(app.filesDir, "bookmarks.json"), BookmarkData(), BookmarkCodec::decode, BookmarkCodec::encode, viewModelScope,
    )
    val bookmarks: StateFlow<BookmarkData> = bookmarkFile.state

    /** Reader-view articles saved for offline reading (`reading/<id>.json`). */
    val readingList = ReadingList(File(app.filesDir, "reading"), viewModelScope)

    /** Markdown notes (`notes/<id>.md`). */
    val notes = NotesRepository(NotesStore(File(app.filesDir, "notes")), viewModelScope)

    // adblock: adblock-rust engine (falls back to allow-all until ready)
    val adBlocker: RustAdBlocker = Adblock.get(app, settingsFile.value.wifiOnlyListUpdates, settingsFile.value.adblockEnabled)

    val history = HistoryDb(app)
    val downloads = DownloadRepository(app)
    /** Process-wide self-updater (see [com.voluene.monobrowser.update.UpdateManager]). */
    val updater = (app as com.voluene.monobrowser.MonoApp).updater
    private val sessionStore = SessionStore(app.filesDir)

    /** The Activity currently on screen (file chooser, runtime permissions); null while there is none. */
    var host: ActivityHost? = null

    private var nextId = 1L
    private val webViews = HashMap<Long, WebView>()
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
    fun captureThumbnail(id: Long) {
        val webView = webViews[id] ?: return
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        if (webView.width <= 0 || webView.height <= 0 || webView.parent == null || !UrlUtils.isHttpUrl(tab.url)) return
        val bitmap = runCatching {
            val scale = THUMBNAIL_WIDTH.toFloat() / webView.width
            val height = (webView.height * scale).toInt().coerceIn(1, (THUMBNAIL_WIDTH * 1.6f).toInt())
            Bitmap.createBitmap(THUMBNAIL_WIDTH, height, Bitmap.Config.RGB_565).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
                webView.draw(canvas)
            }
        }.getOrNull() ?: return
        _thumbnails.update { old ->
            // Re-inserting moves the tab to the end: the oldest previews are dropped first.
            val next = LinkedHashMap(old)
            next.remove(id)
            next[id] = bitmap.asImageBitmap()
            while (next.size > MAX_THUMBNAILS) next.remove(next.keys.first())
            next
        }
    }

    private val _barHidden = MutableStateFlow(false)
    val barHidden: StateFlow<Boolean> = _barHidden.asStateFlow()

    /** Asks the UI to open the command palette (a blank new tab was opened or reused). */
    private val _paletteRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val paletteRequests: SharedFlow<Unit> = _paletteRequests.asSharedFlow()

    private var persistJob: Job? = null
    @Volatile private var pageBackground: Int? = null

    /** Whether new WebViews get the dark context theme, and whether pages without a dark design are darkened. */
    private var webDark: Boolean = initialWebDark(app)
    private var webForceDark: Boolean = settingsFile.value.webDarkMode == WebDarkMode.FORCE

    private fun initialWebDark(app: Application): Boolean {
        val s = settingsFile.value
        if (s.webDarkMode == WebDarkMode.OFF) return false
        val systemDark = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return Themes.resolve(s.themeId, s.themeMode, systemDark).dark
    }

    /**
     * The app's light/dark appearance (or the setting) changed. A WebView's colour scheme comes from the theme of
     * the context it was created with, so live normal tabs are rebuilt from their saved state (sleeping tabs pick
     * it up when they wake). Private tabs cannot be saved and keep their current look until reopened.
     */
    fun setWebAppearance(appDark: Boolean) {
        val mode = settingsFile.value.webDarkMode
        val dark = appDark && mode != WebDarkMode.OFF
        val force = dark && mode == WebDarkMode.FORCE
        if (dark == webDark && force == webForceDark) return
        val rebuild = dark != webDark
        webDark = dark
        webForceDark = force
        webViews.values.forEach(::applyDarkening)
        if (!rebuild) return
        val active = _activeTabId.value
        val normal = webViews.keys.filter { id -> _tabs.value.any { it.id == id && !it.isPrivate } }
        normal.forEach(::sleep)
        if (active in normal) ensureLive(active)
    }

    private fun applyDarkening(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Some WebView versions report the feature but still refuse the call: never let that break a tab.
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, webForceDark) }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = settingsFile.value
        settingsFile.update { SettingsCodec.sanitize(transform(it)) }
        val s = settingsFile.value
        adBlocker.enabled = s.adblockEnabled
        adBlocker.wifiOnlyUpdates = s.wifiOnlyListUpdates
        if (s.textZoom != before.textZoom) webViews.values.forEach { it.settings.textZoom = s.textZoom }
    }

    init {
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
    ): Long {
        val workspace = workspaceId.takeIf { id -> _workspaces.value.any { it.id == id } } ?: WorkspaceOps.MAIN_ID
        val target = url ?: settingsFile.value.newTabUrl
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
    fun openNewTab(isPrivate: Boolean = false): Long {
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
        if (settingsFile.value.newTabUrl == null && settingsFile.value.newTabFocusAddress) _paletteRequests.tryEmit(Unit)
        return id
    }

    fun switchTo(id: Long) {
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
        _thumbnails.update { it - id }
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
            captureThumbnail(_activeTabId.value) // the tab being left, as it looks now
        }
        _activeTabId.value = id
        _currentWorkspaceId.value = tab.workspaceId
        activeByWorkspace[tab.workspaceId] = id
        lastUsed[id] = SystemClock.elapsedRealtime()
        if (!tab.isPrivate) lastNormalActive = id
        ensureLive(id)
        enforceSleepPolicy()
        showBar()
        schedulePersist()
    }

    // --- Navigation ---

    /** Input from the address bar (URL or search query). */
    fun navigate(id: Long, input: String) {
        val url = UrlUtils.resolveInput(input, settingsFile.value.searchTemplate) ?: return
        errorUrls.remove(id)
        ensureLive(id, restore = false)?.loadUrl(url)
    }

    /** Shared or selected text from another app: a URL is opened, anything else is searched, in a new tab. */
    fun openExternalInput(input: String) {
        val url = UrlUtils.resolveInput(input, settingsFile.value.searchTemplate) ?: return
        if (UrlUtils.isHttpUrl(url)) newTab(url = url)
    }

    fun goBack(id: Long) { webViews[id]?.goBack() }

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
    fun goForward(id: Long) { webViews[id]?.goForward() }
    fun stop(id: Long) { webViews[id]?.stopLoading() }

    fun reload(id: Long) {
        val failed = errorUrls.remove(id)
        if (failed != null) webViews[id]?.loadUrl(failed) else webViews[id]?.reload()
    }

    override fun retryFailedLoad(tabId: Long) = reload(tabId)

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
            LiveTab(id, lastUsed[id] ?: 0L, id == active, protected = tab.neverSleep || tab.isPrivate)
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
        // The context theme decides prefers-color-scheme (and whether algorithmic darkening may apply).
        val webView = WebView(ContextThemeWrapper(getApplication(), if (webDark) R.style.Theme_MonoBrowser_WebDark else R.style.Theme_MonoBrowser_WebLight))
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
        applyDarkening(webView)
        pageBackground?.let(webView::setBackgroundColor)
        webView.webViewClient = MonoWebViewClient(tab.id, adBlocker, this, this)
        adBlocker.attach(webView) // adblock: cosmetic
        webView.webChromeClient = MonoWebChromeClient(tab.id, this, this)
        webView.addJavascriptInterface(BlobBridge(tab.id), BlobDownload.BRIDGE)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(tab.id, url, userAgent, contentDisposition, mimeType)
        }
        webView.setFindListener { ordinal, matches, _ -> onFindResult(tab.id, ordinal, matches) }
        webView.setOnLongClickListener { handleLongPress(tab.id, webView) }
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> onScrolled(tab.id, scrollY - oldScrollY, scrollY) }
        return webView
    }

    private var mobileUserAgent: String? = null

    private fun applyDesktopMode(webView: WebView, desktop: Boolean) {
        val settings = webView.settings
        val mobile = mobileUserAgent ?: settings.userAgentString.also { mobileUserAgent = it }
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
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Called after the last private tab is closed. */
    private fun clearPrivateData() {
        if (privateProfilesSupported) {
            // Deleting a profile requires that no WebView uses it (all private WebViews are destroyed by now).
            runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        }
        // else: TODO cookies/storage of the fallback cannot be cleared without touching normal tabs.
    }

    override fun onCleared() {
        flush()
        webViews.values.forEach { it.destroy() }
        webViews.clear()
        history.close()
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

    override fun onPageStarted(tabId: Long, url: String) {
        if (errorUrls[tabId] != url) errorUrls.remove(tabId)
        val keepTitle = errorUrls[tabId] == url
        update(tabId) { it.copy(url = url, title = if (keepTitle) it.title else "", blockedCount = 0, progress = 0) }
        if (tabId == _activeTabId.value) showBar()
    }

    override fun onPageFinished(tabId: Long, url: String) {
        update(tabId) { it.copy(url = url, progress = 100) }
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

    override fun onProgress(tabId: Long, progress: Int) = update(tabId) { it.copy(progress = progress) }

    override fun onBlocked(tabId: Long) = update(tabId) { it.copy(blockedCount = it.blockedCount + 1) }

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
        runCatching { history.suggest(query, limit = 8) }.getOrDefault(emptyList())

    /** Colour shown while a page has not painted yet (matches the theme). */
    fun setPageBackground(argb: Int) {
        pageBackground = argb
        webViews.values.forEach { it.setBackgroundColor(argb) }
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
        newTab(
            isPrivate = isPrivate, url = url, background = background, workspaceId = source?.workspaceId ?: _currentWorkspaceId.value,
            openerId = sourceTabId.takeIf { !background && source != null && _tabs.value.any { it.id == sourceTabId } },
        )
        if (background) toast(R.string.link_opened_background)
    }

    /** Copies [text] and confirms with a toast. */
    fun copyToClipboard(text: String) {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(null, text))
        toast(R.string.link_copied)
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
        adoptGlance() ?: return
        enforceSleepPolicy()
        schedulePersist()
        toast(R.string.link_opened_background)
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
        return TopSites.pick(counts, settingsFile.value.hiddenTopSites.toSet(), limit)
    }

    /** Removes a site from the start page (it comes back only after "Restore removed sites"). */
    fun hideTopSite(host: String) = updateSettings { it.copy(hiddenTopSites = it.hiddenTopSites + host) }

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
    fun saveForLater(tabId: Long) {
        extractArticle(tabId) { article ->
            if (article == null) {
                toast(R.string.reader_unavailable)
            } else {
                readingList.save(article)
                toast(R.string.reading_saved)
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
            UrlUtils.isHttpUrl(url) -> withStoragePermission {
                val referer = webViews[tabId]?.url
                runCatching {
                    downloads.enqueue(url, userAgent, contentDisposition, mimeType, cookiesFor(tabId, url), referer)
                }.onSuccess { (_, name) -> toast(R.string.download_started, name) }
                    .onFailure { toast(R.string.download_failed) }
            }
            else -> toast(R.string.download_failed)
        }
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
        const val THUMBNAIL_WIDTH = 360
        const val MAX_THUMBNAILS = 30
    }
}
