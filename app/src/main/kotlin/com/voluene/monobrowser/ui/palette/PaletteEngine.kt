package com.voluene.monobrowser.ui.palette

import com.voluene.monobrowser.util.UrlUtils
import java.text.Normalizer

/** Identifiers of palette commands; the UI maps them to behaviour. */
enum class PaletteActionId {
    NEW_TAB, NEW_PRIVATE_TAB, CLOSE_TAB, RELOAD, BACK, FORWARD, REOPEN_CLOSED, FIND,
    BOOKMARK_PAGE, SHARE, TOGGLE_ADBLOCK_SITE, TOGGLE_ADBLOCK,
    OPEN_BOOKMARKS, OPEN_HISTORY, OPEN_DOWNLOADS, OPEN_SETTINGS,
    /** [PaletteAction.arg] = workspace id. */
    SWITCH_WORKSPACE, NEW_WORKSPACE,
    OPEN_NOTES, NEW_NOTE, ADD_PAGE_TO_NOTE,
}

/** A command; [keywords] are extra search words (both languages, no diacritics needed), never shown. */
data class PaletteAction(
    val id: PaletteActionId,
    val label: String,
    val keywords: String = "",
    val hint: String = "",
    /** Parameter of the command (e.g. the workspace to switch to). */
    val arg: String? = null,
)

/** An open tab; [workspace] is the name of its workspace (blank when workspaces are not worth showing) and is searchable. */
data class PaletteTab(val id: Long, val title: String, val url: String, val isPrivate: Boolean, val workspace: String = "")
data class PaletteLink(val title: String, val url: String)

sealed interface PaletteResult {
    /** First row: load the typed text as a URL or search it. */
    data class Go(val input: String, val isSearch: Boolean) : PaletteResult
    data class OpenTab(val tab: PaletteTab) : PaletteResult
    data class Bookmark(val link: PaletteLink) : PaletteResult
    data class History(val link: PaletteLink) : PaletteResult
    data class Run(val action: PaletteAction) : PaletteResult
}

/** Section a result belongs to (the UI draws a header whenever it changes). */
enum class PaletteSection { GO, TABS, BOOKMARKS, HISTORY, ACTIONS }

fun PaletteResult.section(): PaletteSection = when (this) {
    is PaletteResult.Go -> PaletteSection.GO
    is PaletteResult.OpenTab -> PaletteSection.TABS
    is PaletteResult.Bookmark -> PaletteSection.BOOKMARKS
    is PaletteResult.History -> PaletteSection.HISTORY
    is PaletteResult.Run -> PaletteSection.ACTIONS
}

/**
 * Port of the desktop palette's matching (`renderer/palette.ts`: all words must be substrings, otherwise a
 * subsequence "fuzzy" fallback) extended to mobile result groups: Go/Search, open tabs, bookmarks, history,
 * actions. Results are returned as one flat list in display order; the first element is what the keyboard
 * "Go" key executes. Pure, unit-tested.
 */
object PaletteEngine {
    const val MAX_TABS = 5
    const val MAX_BOOKMARKS = 5
    const val MAX_HISTORY = 5
    const val MAX_ACTIONS = 8

    fun fold(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun words(query: String) = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun matchesAll(words: List<String>, haystack: String) = words.all { haystack.contains(it) }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var cursor = 0
        for (ch in needle) {
            if (ch == ' ') continue
            val found = haystack.indexOf(ch, cursor)
            if (found == -1) return false
            cursor = found + 1
        }
        return true
    }

    /** Lower is better: title prefix < host prefix < title word start < anything else. */
    private fun rank(words: List<String>, title: String, url: String): Int {
        val t = fold(title)
        val u = fold(UrlUtils.shortUrlLabel(url))
        val first = words.firstOrNull() ?: return 0
        return when {
            t.startsWith(first) -> 0
            u.startsWith(first) -> 1
            t.contains(" $first") -> 2
            else -> 3
        }
    }

    private fun <T> filterLinks(
        items: List<T>,
        words: List<String>,
        title: (T) -> String,
        url: (T) -> String,
        limit: Int,
        extra: (T) -> String = { "" },
    ): List<T> =
        items.withIndex()
            .filter { (_, item) -> words.isEmpty() || matchesAll(words, fold("${title(item)} ${url(item)} ${extra(item)}")) }
            .sortedWith(compareBy({ rank(words, title(it.value), url(it.value)) }, { it.index })) // stable: keeps source order
            .map { it.value }
            .take(limit)

    fun filterActions(actions: List<PaletteAction>, query: String, limit: Int = MAX_ACTIONS): List<PaletteAction> {
        val words = words(query)
        if (words.isEmpty()) return actions.take(limit)
        val substring = actions.filter { matchesAll(words, fold("${it.label} ${it.keywords}")) }
        if (substring.isNotEmpty()) return substring.take(limit)
        val needle = fold(query).replace(Regex("\\s+"), "")
        return actions.filter { isSubsequence(needle, fold("${it.label} ${it.keywords}").replace(Regex("\\s+"), "")) }.take(limit)
    }

    fun isSearchInput(input: String): Boolean {
        val trimmed = input.trim()
        return !(UrlUtils.isHttpUrl(trimmed) || UrlUtils.isAboutBlankUrl(trimmed) || UrlUtils.looksLikeHost(trimmed) ||
            UrlUtils.recoverEmbeddedUrl(trimmed) != null)
    }

    /**
     * @param history already-queried history matches for [query] (database order is kept).
     * Bookmarks/history rows whose URL is already offered earlier (open tab / bookmark) are dropped.
     */
    fun search(
        query: String,
        tabs: List<PaletteTab>,
        bookmarks: List<PaletteLink>,
        history: List<PaletteLink>,
        actions: List<PaletteAction>,
    ): List<PaletteResult> {
        val trimmed = query.trim()
        val words = words(query)
        val out = ArrayList<PaletteResult>()
        if (trimmed.isNotEmpty()) out.add(PaletteResult.Go(trimmed, isSearchInput(trimmed)))

        val tabHits = filterLinks(tabs, words, { it.title }, { it.url }, MAX_TABS, { it.workspace })
        tabHits.forEach { out.add(PaletteResult.OpenTab(it)) }
        val seen = tabHits.map { it.url }.toMutableSet()

        val bookmarkHits = filterLinks(bookmarks, words, { it.title }, { it.url }, MAX_BOOKMARKS + seen.size)
            .filter { seen.add(it.url) }.take(MAX_BOOKMARKS)
        bookmarkHits.forEach { out.add(PaletteResult.Bookmark(it)) }

        if (words.isNotEmpty()) {
            history.filter { seen.add(it.url) }.take(MAX_HISTORY).forEach { out.add(PaletteResult.History(it)) }
        }
        filterActions(actions, query).forEach { out.add(PaletteResult.Run(it)) }
        return out
    }
}
