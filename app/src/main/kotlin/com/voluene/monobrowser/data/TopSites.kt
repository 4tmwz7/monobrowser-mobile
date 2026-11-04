package com.voluene.monobrowser.data

import java.net.URI

/** A URL with its visit count, as aggregated by [HistoryDb.mostVisited]. */
data class VisitCount(val url: String, val title: String, val visits: Int, val lastVisit: Long)

/** A tile of the start page. [host] is the label source and the key used to hide it. */
data class TopSite(val url: String, val title: String, val host: String)

/**
 * Start-page tiles from history: one tile per site (host without `www.`), the most visited URL of that site wins,
 * sites the user removed are skipped. Pure, unit-tested.
 */
object TopSites {
    const val DEFAULT_LIMIT = 8

    /** `www.example.com` -> `example.com`; null for URLs without a host (or not http/https). */
    fun hostKey(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        return host.removePrefix("www.")
    }

    /**
     * @param counts per-URL counts in any order.
     * @param hidden host keys the user removed from the start page.
     */
    fun pick(counts: List<VisitCount>, hidden: Set<String>, limit: Int = DEFAULT_LIMIT): List<TopSite> {
        val bySite = LinkedHashMap<String, MutableList<VisitCount>>()
        for (entry in counts) {
            val key = hostKey(entry.url) ?: continue
            if (key in hidden) continue
            bySite.getOrPut(key) { ArrayList() }.add(entry)
        }
        return bySite.map { (host, entries) ->
            val total = entries.sumOf { it.visits }
            val last = entries.maxOf { it.lastVisit }
            val best = entries.maxWith(compareBy<VisitCount>({ it.visits }, { it.lastVisit }))
            // Prefer the site's root page when it was visited at all: a tile should open the site, not one article.
            val root = entries.filter { isRootPage(it.url) }.maxByOrNull { it.visits }
            val chosen = root ?: best
            Triple(TopSite(chosen.url, chosen.title.ifBlank { best.title }, host), total, last)
        }
            .sortedWith(compareByDescending<Triple<TopSite, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(limit)
            .map { it.first }
    }

    private fun isRootPage(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty()
        return (path.isEmpty() || path == "/") && uri.rawQuery == null
    }

    /** Short tile label: `news.ycombinator.com` -> `ycombinator`, `example.co.uk` -> `example`. */
    fun label(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 1) return host
        val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac")
        val core = if (parts.size >= 3 && parts[parts.size - 2] in secondLevel && parts.last().length == 2) {
            parts[parts.size - 3]
        } else {
            parts[parts.size - 2]
        }
        return core
    }
}
