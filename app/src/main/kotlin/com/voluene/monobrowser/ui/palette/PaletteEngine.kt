package com.voluene.monobrowser.ui.palette

import com.voluene.monobrowser.util.UrlUtils
import java.text.Normalizer

/** Identifiers of palette commands; the UI maps them to behaviour. */
enum class PaletteActionId {
    NEW_TAB, NEW_PRIVATE_TAB, CLOSE_TAB, RELOAD, BACK, FORWARD, REOPEN_CLOSED, FIND,
    BOOKMARK_PAGE, SHARE, TOGGLE_ADBLOCK_SITE, TOGGLE_ADBLOCK, PAGE_THEME_SITE,
    OPEN_BOOKMARKS, OPEN_HISTORY, OPEN_DOWNLOADS, OPEN_SETTINGS,
    /** [PaletteAction.arg] = workspace id. */
    SWITCH_WORKSPACE, NEW_WORKSPACE,
    OPEN_NOTES, NEW_NOTE, ADD_PAGE_TO_NOTE,
    READER, DESKTOP_SITE, PRINT, TEXT_SIZE, READING_LIST, SAVE_FOR_LATER, TRANSLATE,
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
    /** A search suggestion of the engine (right under the Go row). */
    data class Suggestion(val text: String) : PaletteResult
    data class OpenTab(val tab: PaletteTab) : PaletteResult
    data class Bookmark(val link: PaletteLink) : PaletteResult
    data class History(val link: PaletteLink) : PaletteResult
    data class Run(val action: PaletteAction) : PaletteResult
}

/** Section a result belongs to (the UI draws a header whenever it changes). */
enum class PaletteSection { GO, TABS, BOOKMARKS, HISTORY, ACTIONS }

fun PaletteResult.section(): PaletteSection = when (this) {
    is PaletteResult.Go, is PaletteResult.Suggestion -> PaletteSection.GO
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
    const val MAX_SUGGESTIONS = 4
    const val MAX_RECENT = 4

    private val MARKS = Regex("\\p{M}+")

    /**
     * Folded strings of recent calls: searching re-folds every tab, bookmark and action title on each keystroke,
     * and those titles rarely change. Access-ordered, bounded, synchronized (the palette and the tab list share it).
     */
    private val foldCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > FOLD_CACHE_SIZE
    }
    private const val FOLD_CACHE_SIZE = 4096

    /** Lower case without diacritics; letters that do not decompose (Polish ł, ø, đ, ß ...) are mapped by hand. */
    fun fold(value: String): String {
        if (value.length > 512) return foldUncached(value)
        synchronized(foldCache) { foldCache[value]?.let { return it } }
        val folded = foldUncached(value)
        synchronized(foldCache) { foldCache[value] = folded }
        return folded
    }

    private fun foldUncached(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            .replace('ł', 'l').replace('ø', 'o').replace('đ', 'd').replace('ħ', 'h').replace("ß", "ss").replace("æ", "ae").replace("œ", "oe")

    private val SPACES = Regex("\\s+")

    private fun words(query: String) = fold(query).split(SPACES).filter { it.isNotEmpty() }

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

    /** `https://www.example.com/a` -> `example.com/a` without regexes (ranking runs for every hit on every keystroke). */
    internal fun hostFirst(url: String): String {
        var start = url.indexOf("://").let { if (it in 1..12) it + 3 else 0 }
        if (url.regionMatches(start, "www.", 0, 4, ignoreCase = true)) start += 4
        val end = if (url.endsWith("/")) url.length - 1 else url.length
        return if (start < end) url.substring(start, end) else ""
    }

    /** Lower is better: title prefix < host prefix < title word start < anything else. */
    private fun rank(words: List<String>, t: String, u: String): Int {
        val first = words.firstOrNull() ?: return 0
        return when {
            t.startsWith(first) -> 0
            u.startsWith(first) -> 1
            t.contains(" $first") -> 2
            else -> 3
        }
    }

    /** Folded search text of one list, computed once per list instance and reused on every keystroke. */
    private class Prepared(val haystacks: Array<String>, val titles: Array<String>, val hosts: Array<String>)

    /** The last few lists searched (by identity): typing searches the same tab / bookmark lists again and again. */
    private val preparedLists = ArrayDeque<Pair<List<*>, Prepared>>()
    private const val PREPARED_LISTS = 6

    private fun <T> prepared(items: List<T>, title: (T) -> String, url: (T) -> String, extra: (T) -> String): Prepared {
        synchronized(preparedLists) { preparedLists.firstOrNull { it.first === items }?.let { return it.second } }
        val p = Prepared(
            Array(items.size) { i -> items[i].let { foldUncached("${title(it)} ${url(it)} ${extra(it)}") } },
            Array(items.size) { i -> fold(title(items[i])) },
            Array(items.size) { i -> foldUncached(hostFirst(url(items[i]))) },
        )
        synchronized(preparedLists) {
            preparedLists.addFirst(items to p)
            while (preparedLists.size > PREPARED_LISTS) preparedLists.removeLast()
        }
        return p
    }

    private fun <T> filterLinks(
        items: List<T>,
        words: List<String>,
        title: (T) -> String,
        url: (T) -> String,
        limit: Int,
        extra: (T) -> String = { "" },
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val p = prepared(items, title, url, extra)
        // Rank once per hit (not in the comparator), then a stable sort keeps the source order within a rank.
        val hits = ArrayList<Long>()
        for (i in items.indices) {
            if (words.isNotEmpty() && !matchesAll(words, p.haystacks[i])) continue
            hits.add(rank(words, p.titles[i], p.hosts[i]).toLong() shl 32 or i.toLong())
        }
        hits.sort()
        return List(minOf(limit, hits.size)) { items[(hits[it] and 0xffffffffL).toInt()] }
    }

    fun filterActions(actions: List<PaletteAction>, query: String, limit: Int = MAX_ACTIONS): List<PaletteAction> {
        val words = words(query)
        if (words.isEmpty()) return actions.take(limit)
        val substring = actions.filter { matchesAll(words, fold("${it.label} ${it.keywords}")) }
        if (substring.isNotEmpty()) return substring.take(limit)
        val needle = fold(query).replace(SPACES, "")
        return actions.filter { isSubsequence(needle, fold("${it.label} ${it.keywords}").replace(SPACES, "")) }.take(limit)
    }

    /** Commands start with a slash: `/newtab`, `/nowakarta`, `/ustawienia` ... */
    const val COMMAND_PREFIX = '/'
    const val MAX_COMMANDS = 40

    fun isCommand(query: String): Boolean = query.trimStart().startsWith(COMMAND_PREFIX)

    /** `Nowa karta` -> `nowakarta`: how a command is typed after the slash. */
    fun slug(label: String): String = fold(label).filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * What the address bar shows: an ordinary URL box (go / search, engine suggestions, matching bookmarks and
     * history) unless the text starts with [COMMAND_PREFIX]; then commands (and open tabs) instead.
     */
    fun addressBar(
        query: String,
        tabs: List<PaletteTab>,
        bookmarks: List<PaletteLink>,
        history: List<PaletteLink>,
        actions: List<PaletteAction>,
        suggestions: List<String> = emptyList(),
    ): List<PaletteResult> =
        if (isCommand(query)) commands(query, actions, tabs)
        else search(query, emptyList(), bookmarks, history, emptyList(), suggestions)

    /**
     * Commands for `/text`: label typed without spaces first (`/nowak` -> Nowa karta), then keyword aliases
     * (`/newtab`), then all words anywhere, then a subsequence; open tabs that match follow. A bare `/` lists all.
     */
    fun commands(query: String, actions: List<PaletteAction>, tabs: List<PaletteTab> = emptyList()): List<PaletteResult> {
        val rest = query.trimStart().drop(1)
        val needle = slug(rest)
        val words = words(rest)
        val out = ArrayList<PaletteResult>()
        if (needle.isEmpty()) {
            actions.take(MAX_COMMANDS).forEach { out.add(PaletteResult.Run(it)) }
            return out
        }
        val hits = ArrayList<Long>()
        actions.forEachIndexed { i, action ->
            val label = slug(action.label)
            val keywords = fold(action.keywords)
            val score = when {
                label.startsWith(needle) -> 0
                keywords.replace(SPACES, "").contains(needle) || label.contains(needle) -> 1
                matchesAll(words, "${fold(action.label)} $keywords") -> 2
                isSubsequence(needle, label + keywords.replace(SPACES, "")) -> 3
                else -> return@forEachIndexed
            }
            hits.add(score.toLong() shl 32 or i.toLong())
        }
        hits.sort()
        hits.take(MAX_COMMANDS).forEach { out.add(PaletteResult.Run(actions[(it and 0xffffffffL).toInt()])) }
        filterLinks(tabs, words, { it.title }, { it.url }, MAX_TABS, { it.workspace }).forEach { out.add(PaletteResult.OpenTab(it)) }
        return out
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
        suggestions: List<String> = emptyList(),
    ): List<PaletteResult> {
        val trimmed = query.trim()
        val words = words(query)
        val out = ArrayList<PaletteResult>()
        if (trimmed.isNotEmpty()) {
            val isSearch = isSearchInput(trimmed)
            out.add(PaletteResult.Go(trimmed, isSearch))
            // Engine suggestions only make sense for text that is going to be searched.
            if (isSearch) {
                suggestions.filter { !it.equals(trimmed, ignoreCase = true) }.distinctBy { it.lowercase() }
                    .take(MAX_SUGGESTIONS).forEach { out.add(PaletteResult.Suggestion(it)) }
            }
        }

        val tabHits = filterLinks(tabs, words, { it.title }, { it.url }, MAX_TABS, { it.workspace })
        tabHits.forEach { out.add(PaletteResult.OpenTab(it)) }
        val seen = tabHits.map { it.url }.toMutableSet()

        val bookmarkHits = filterLinks(bookmarks, words, { it.title }, { it.url }, MAX_BOOKMARKS + seen.size)
            .filter { seen.add(it.url) }.take(MAX_BOOKMARKS)
        bookmarkHits.forEach { out.add(PaletteResult.Bookmark(it)) }

        // With a query: matches; without one, the caller passes the most recent pages ("pick up where you left off").
        history.filter { seen.add(it.url) }.take(if (words.isEmpty()) MAX_RECENT else MAX_HISTORY).forEach { out.add(PaletteResult.History(it)) }
        filterActions(actions, query).forEach { out.add(PaletteResult.Run(it)) }
        return out
    }
}
