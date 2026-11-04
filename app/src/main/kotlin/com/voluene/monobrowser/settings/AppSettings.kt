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

/** Dark mode for web pages (only matters while the app itself is dark). */
@Serializable
enum class WebDarkMode {
    /** Pages that have their own dark design use it (prefers-color-scheme follows the app). */
    AUTO,
    /** Additionally darken pages without a dark design (algorithmic darkening). */
    FORCE,
    /** Pages always render light. */
    OFF,
}

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
    val webDarkMode: WebDarkMode = WebDarkMode.AUTO,
    /** Start page tiles the user pinned (shown before the most visited sites). */
    val pinnedSites: List<PinnedSite> = emptyList(),
    /** Remove utm_*, fbclid, gclid and similar trackers from links you open, copy and share. */
    val stripTracking: Boolean = true,
    /** Sync file chosen by the user (a content:// URI with a persisted permission); empty = sync off. */
    val syncUri: String = "",
) {
    /** Template (`{query}`) used by the address bar for text that is not a URL. */
    val searchTemplate: String
        get() = if (searchEngine == SearchEngine.CUSTOM) customSearchUrl else searchEngine.template ?: UrlUtils.DEFAULT_SEARCH_TEMPLATE

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
        runCatching { sanitize(json.decodeFromString(AppSettings.serializer(), text)) }.getOrNull()

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
            hiddenTopSites = s.hiddenTopSites.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
                .takeLast(AppSettings.MAX_HIDDEN_TOP_SITES),
        )
    }
}
