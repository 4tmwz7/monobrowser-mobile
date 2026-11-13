package com.voluene.monobrowser.settings

import com.voluene.monobrowser.util.UrlUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Search engines of the desktop app (`SearchEngine` in main.ts). */
@Serializable
enum class SearchEngine(val template: String?, val displayName: String) {
    GOOGLE("https://www.google.com/search?q={query}", "Google"),
    DUCKDUCKGO("https://duckduckgo.com/?q={query}", "DuckDuckGo"),
    BING("https://www.bing.com/search?q={query}", "Bing"),
    BRAVE("https://search.brave.com/search?q={query}", "Brave Search"),
    ECOSIA("https://www.ecosia.org/search?q={query}", "Ecosia"),
    STARTPAGE("https://www.startpage.com/do/search?q={query}", "Startpage"),
    CUSTOM(null, ""),
}

/** What the browser opens on launch (desktop `startupMode`: restore / newtab). */
@Serializable
enum class StartupMode { RESTORE, NEWTAB }

/** Content of a freshly opened tab. */
@Serializable
enum class NewTabPage { BLANK, HOMEPAGE }

@Serializable
enum class AppLanguage(val tag: String?) {
    SYSTEM(null), PL("pl"), EN("en");
}

/** Where the whole address bar lives. */
@Serializable
enum class ToolbarPosition { BOTTOM, TOP }

/** Light/dark choice; only the default theme follows it (colour presets are intrinsically light or dark). */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Default action of a long-press on a link in a page (desktop: Glance trigger; mobile has only the long-press). */
@Serializable
enum class LinkLongPress { GLANCE, MENU }

/** A site pinned to the start page. */
@Serializable
data class PinnedSite(val url: String, val title: String = "")

@Serializable
data class AppSettings(
    val version: Int = 1,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val customSearchUrl: String = DEFAULT_CUSTOM_SEARCH_URL,
    val startupMode: StartupMode = StartupMode.RESTORE,
    val newTabPage: NewTabPage = NewTabPage.BLANK,
    val homepageUrl: String = "",
    val language: AppLanguage = AppLanguage.SYSTEM,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM,
    val linkLongPress: LinkLongPress = LinkLongPress.GLANCE,
    val themeId: String = Themes.DEFAULT_ID,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Light/dark of web pages (`prefers-color-scheme`), independent of the browser UI [themeMode]; per-site overrides win. */
    val pageThemeMode: ThemeMode = ThemeMode.SYSTEM,
    /** When a page is dark, darken sites that have no dark theme of their own (WebView algorithmic darkening). */
    val darkenPages: Boolean = true,
    /** 0.2.0 setting (AUTO/FORCE/OFF, pages followed the app theme); read once by [SettingsCodec.decode], then dropped. */
    val webDarkMode: String? = null,
    val adblockEnabled: Boolean = true,
    val wifiOnlyListUpdates: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Release builds: look for a new version on start and every 6 h (never downloads without a tap). */
    val autoUpdateCheck: Boolean = true,
    /** Page text size in percent (WebSettings.textZoom). */
    val textZoom: Int = 100,
    /** Start page (blank new tab): show the most visited sites. */
    val showTopSites: Boolean = true,
    /** Sites (host keys, see `TopSites.hostKey`) removed from the start page. */
    val hiddenTopSites: List<String> = emptyList(),
    /** A new blank tab opens the command palette with the keyboard right away (otherwise the start page shows). */
    val newTabFocusAddress: Boolean = false,
    /** Reader view text size in sp. */
    val readerFontSize: Int = DEFAULT_READER_FONT_SIZE,
    val readerSerif: Boolean = true,
    /** Ask the search engine for suggestions while typing (never in private tabs). */
    val searchSuggestions: Boolean = true,
    /** Address bar: the whole URL instead of the site's host with a lock. */
    val showFullAddress: Boolean = false,
    /** Start page tiles the user pinned (shown before the most visited sites). */
    val pinnedSites: List<PinnedSite> = emptyList(),
    /** Remove utm_*, fbclid, gclid and similar trackers from links you open, copy and share. */
    val stripTracking: Boolean = true,
    /** Leaving the app while a video plays in the current tab shrinks it into a floating window (picture in picture). */
    val autoPictureInPicture: Boolean = true,
    /** The picture-in-picture window shows the whole page made small (with the site's own player controls); off = only the video. */
    val pipWholePage: Boolean = false,
    /** Start page: show the bookmarks and bookmark folders the user chose (see `BookmarkData.startPage`). */
    val showStartBookmarks: Boolean = true,
    /** Elements of the address bar's row in order (names of [BarItem]); see [BarLayout]. A missing field = the default layout. */
    val barItems: List<String> = BarLayout.DEFAULT_IDS,
    /** Main menu: order of the entries (names of [MenuId]); empty = default, ids missing here are appended. See [MenuLayout]. */
    val menuOrder: List<String> = emptyList(),
    /** Main menu entries the user hid (names of [MenuId]); Settings can never be hidden. */
    val menuHidden: List<String> = emptyList(),
    /** Start page tiles show the site's icon (from the pages you visit; nothing is downloaded for it) instead of a letter. */
    val showFavicons: Boolean = true,
    /** Site icons are drawn in greyscale (applied when drawing). */
    val faviconsGrayscale: Boolean = true,
    /** Custom start page background: the time its image was saved (0 = none); the file is `files/start-background.jpg`. */
    val startBackground: Long = 0,
    /** Strength of the overlay on the background (index into `StartBackground.DIM_LEVELS`). */
    val startBackgroundDim: Int = StartBackground.DEFAULT_DIM,
    /** The background image is drawn in greyscale (applied when drawing). */
    val startBackgroundGrayscale: Boolean = true,
    /** Sync file chosen by the user (a content:// URI with a persisted permission); empty = sync off. */
    val syncUri: String = "",
) {
    /** Template (`{query}`) used by the address bar for text that is not a URL. */
    val searchTemplate: String
        get() = if (searchEngine == SearchEngine.CUSTOM) customSearchUrl else searchEngine.template ?: UrlUtils.DEFAULT_SEARCH_TEMPLATE

    /** The address bar's row as a valid layout. */
    val barLayout: List<BarItem>
        get() = BarLayout.parse(barItems)

    /** URL loaded into a new tab, or null for a blank tab. */
    val newTabUrl: String?
        get() = if (newTabPage == NewTabPage.HOMEPAGE && homepageUrl.isNotBlank()) homepageUrl else null

    companion object {
        const val DEFAULT_CUSTOM_SEARCH_URL = "https://example.com/search?q={query}"
        const val DEFAULT_READER_FONT_SIZE = 18
        val READER_FONT_RANGE = 14..30
        val TEXT_ZOOM_STEPS = listOf(75, 85, 100, 115, 130, 150, 175, 200)
        const val MAX_HIDDEN_TOP_SITES = 200
        const val MAX_PINNED = 12
    }
}

object SettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true // unknown enum value -> default
    }

    /** Custom engine URL rule of the desktop app: http(s), at most 2048 chars, contains `{query}`. */
    fun isValidSearchUrl(value: String): Boolean {
        val v = value.trim()
        return v.length <= 2048 && v.contains("{query}") && UrlUtils.isHttpUrl(v) && !v.any { it.isWhitespace() }
    }

    fun isValidHomepage(value: String): Boolean {
        val v = value.trim()
        return v.length <= 2048 && UrlUtils.isHttpUrl(v) && !v.any { it.isWhitespace() }
    }

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), sanitize(settings))

    /** Null when the text is not valid JSON; otherwise always a consistent settings object. */
    fun decode(text: String): AppSettings? =
        runCatching { sanitize(migrate(json.decodeFromString(AppSettings.serializer(), text))) }.getOrNull()

    /** 0.2.0 `webDarkMode` -> page theme settings, keeping what the user saw: AUTO/FORCE followed the app's mode. */
    fun migrate(s: AppSettings): AppSettings = when (s.webDarkMode) {
        null -> s
        "OFF" -> s.copy(pageThemeMode = ThemeMode.LIGHT, darkenPages = false, webDarkMode = null)
        "FORCE" -> s.copy(pageThemeMode = s.themeMode, darkenPages = true, webDarkMode = null)
        else -> s.copy(pageThemeMode = s.themeMode, darkenPages = false, webDarkMode = null)
    }

    fun sanitize(s: AppSettings): AppSettings {
        val custom = s.customSearchUrl.trim().takeIf(::isValidSearchUrl) ?: AppSettings.DEFAULT_CUSTOM_SEARCH_URL
        val home = s.homepageUrl.trim().takeIf(::isValidHomepage).orEmpty()
        return s.copy(
            customSearchUrl = custom,
            homepageUrl = home,
            themeId = if (Themes.isKnown(s.themeId)) s.themeId else Themes.DEFAULT_ID,
            // A custom engine is only usable with a valid URL; the stored value is already validated above,
            // but if the user never configured one the default engine is safer than example.com.
            searchEngine = if (s.searchEngine == SearchEngine.CUSTOM && custom == AppSettings.DEFAULT_CUSTOM_SEARCH_URL) SearchEngine.GOOGLE else s.searchEngine,
            newTabPage = if (s.newTabPage == NewTabPage.HOMEPAGE && home.isEmpty()) NewTabPage.BLANK else s.newTabPage,
            textZoom = s.textZoom.coerceIn(50, 300),
            readerFontSize = s.readerFontSize.coerceIn(AppSettings.READER_FONT_RANGE.first, AppSettings.READER_FONT_RANGE.last),
            pinnedSites = s.pinnedSites.filter { UrlUtils.isHttpUrl(it.url) && it.url.length <= 2048 }
                .distinctBy { it.url }.take(AppSettings.MAX_PINNED).map { it.copy(title = it.title.take(200)) },
            startBackground = s.startBackground.coerceAtLeast(0),
            startBackgroundDim = StartBackground.clampDim(s.startBackgroundDim),
            menuOrder = MenuLayout.sanitizeOrder(s.menuOrder),
            menuHidden = MenuLayout.sanitizeHidden(s.menuHidden),
            barItems = BarLayout.ids(BarLayout.parse(s.barItems)),
            hiddenTopSites = s.hiddenTopSites.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
                .takeLast(AppSettings.MAX_HIDDEN_TOP_SITES),
        )
    }
}
