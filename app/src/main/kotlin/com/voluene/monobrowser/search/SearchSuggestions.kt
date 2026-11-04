package com.voluene.monobrowser.search

import com.voluene.monobrowser.settings.SearchEngine
import com.voluene.monobrowser.util.UrlUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Search suggestions of the chosen engine (the "OpenSearch suggestions" JSON both Google and DuckDuckGo answer
 * with: `["query", ["suggestion", ...], ...]`). Only the typed text is sent; never for private tabs (the caller
 * decides). URL building and parsing are pure and unit-tested.
 */
object SearchSuggestions {
    const val MAX = 4
    private const val TIMEOUT_MS = 1_500
    private const val MAX_BYTES = 64 * 1024

    /** Suggestion endpoint for [engine], or null when it has none (custom engines). */
    fun url(engine: SearchEngine, query: String, language: String): String? {
        val q = UrlUtils.encodeUriComponent(query.trim())
        if (q.isEmpty()) return null
        return when (engine) {
            SearchEngine.GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&hl=${UrlUtils.encodeUriComponent(language)}&q=$q"
            SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?type=list&q=$q"
            SearchEngine.BING -> "https://api.bing.com/osjson.aspx?query=$q"
            SearchEngine.ECOSIA -> "https://ac.ecosia.org/autocomplete?type=list&q=$q"
            // No public suggestion endpoint (Brave answers bots with a page, Startpage has none).
            SearchEngine.BRAVE, SearchEngine.STARTPAGE, SearchEngine.CUSTOM -> null
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The suggestion strings, without the query itself and duplicates; empty for anything unexpected. */
    fun parse(body: String, query: String, limit: Int = MAX): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        val list = root.getOrNull(1) as? JsonArray ?: return emptyList()
        val typed = query.trim().lowercase()
        val seen = HashSet<String>()
        return list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() && it.length <= 200 && it.lowercase() != typed && seen.add(it.lowercase()) }
            .take(limit)
    }

    /** Blocking network call (call from Dispatchers.IO); empty on any failure. */
    fun fetch(engine: SearchEngine, query: String, language: String): List<String> {
        val address = url(engine, query, language) ?: return emptyList()
        return runCatching {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode != 200) return@runCatching emptyList()
                // InputStream.readNBytes needs API 33; read by hand up to the cap.
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (out.size() < MAX_BYTES) {
                        val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                parse(String(bytes, Charsets.UTF_8), query)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}
