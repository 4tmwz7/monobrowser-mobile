package com.voluene.monobrowser.util

import java.net.URLEncoder

/**
 * Port of the pure logic of the desktop `src/main/url-utils.ts`
 * (address-bar input -> URL or search query, display formatting).
 */
object UrlUtils {
    /** Default search template (desktop default engine: Google). `{query}` is replaced with the encoded query. */
    const val DEFAULT_SEARCH_TEMPLATE = "https://www.google.com/search?q={query}"

    private val IC = setOf(RegexOption.IGNORE_CASE)

    /** Local/LAN hosts: dev servers usually have no TLS, so they open over http://. */
    private val LOCAL_HOST_PATTERNS = listOf(
        Regex("""^(?:[a-z0-9-]+\.)*localhost(?::\d+)?$""", IC),
        Regex("""^127(?:\.\d{1,3}){3}(?::\d+)?$"""),
        Regex("""^0\.0\.0\.0(?::\d+)?$"""),
        Regex("""^10(?:\.\d{1,3}){3}(?::\d+)?$"""),
        Regex("""^192\.168(?:\.\d{1,3}){2}(?::\d+)?$"""),
        Regex("""^172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}(?::\d+)?$"""),
        Regex("""^\[[0-9a-f:.]+\](?::\d+)?$""", IC),
        Regex("""^[a-z0-9-]+\.local(?::\d+)?$""", IC),
    )

    private val LOOPBACK_HOST_PATTERNS = listOf(
        Regex("""^(?:[a-z0-9-]+\.)*localhost(?::\d+)?$""", IC),
        Regex("""^127(?:\.\d{1,3}){3}(?::\d+)?$"""),
        Regex("""^\[(?:0*:)*0*1\](?::\d+)?$"""),
    )

    private val HOST_PATTERNS = LOCAL_HOST_PATTERNS + listOf(
        Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?$"""),
        Regex("""^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?::\d+)?$""", IC),
    )

    private val HTTP_URL = Regex("""^https?://""", IC)
    private val ABOUT_BLANK = Regex("""^about:blank(?:[?#].*)?$""", IC)
    private val WHITESPACE = Regex("""\s""")
    private val BARE_ORIGIN_WITH_SLASH = Regex("""^https?://[^/?#]+/$""", IC)
    private val INTERNAL_SCHEME = Regex("""^monobrowser://""", IC)
    private val EMBEDDED_URL = Regex("""^monobrowser://[a-z-]*(https?://\S+)$""", IC)
    private val SCHEME_PREFIX = Regex("""^[a-z][a-z0-9+.-]*://""", IC)
    private val WWW_PREFIX = Regex("""^www\.""", IC)

    fun isHttpUrl(value: String): Boolean = HTTP_URL.containsMatchIn(value)

    fun isAboutBlankUrl(value: String): Boolean = ABOUT_BLANK.matches(value)

    private fun hostOf(input: String): String = input.trim().split('/', '?', '#').first()

    /** Does the text look like a host address (as opposed to a search query)? */
    fun looksLikeHost(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || WHITESPACE.containsMatchIn(trimmed)) return false
        val host = hostOf(trimmed)
        return HOST_PATTERNS.any { it.matches(host) }
    }

    fun isLocalHost(input: String): Boolean {
        val host = hostOf(input)
        return LOCAL_HOST_PATTERNS.any { it.matches(host) }
    }

    /** Loopback host (localhost, *.localhost, 127.x, [::1]); no LAN ranges. */
    fun isLoopbackHost(hostname: String): Boolean {
        val host = hostOf(hostname)
        return LOOPBACK_HOST_PATTERNS.any { it.matches(host) }
    }

    /** URL for host-like text: local hosts over http://, everything else over https://. */
    fun hostInputToUrl(input: String): String =
        "${if (isLocalHost(input)) "http" else "https"}://${input.trim()}"

    /** Address for display: without a lone "/" after a bare origin; everything else unchanged. */
    fun displayUrl(value: String): String =
        if (BARE_ORIGIN_WITH_SLASH.matches(value)) value.dropLast(1) else value

    fun isInternalSchemeInput(value: String): Boolean = INTERNAL_SCHEME.containsMatchIn(value.trim())

    /**
     * Malformed text starting with the internal scheme and a URL glued after it
     * (e.g. "monobrowser://settingshttps://example.com"): the http(s) URL, or null. Never turn it into a search.
     */
    fun recoverEmbeddedUrl(value: String): String? = EMBEDDED_URL.find(value.trim())?.groupValues?.get(1)

    /** Host + path for list rows (no scheme, no "www.", no lone trailing slash). */
    fun shortUrlLabel(value: String): String =
        value.replace(SCHEME_PREFIX, "").replace(WWW_PREFIX, "").removeSuffix("/")

    /** Equivalent of JavaScript's encodeURIComponent. */
    fun encodeUriComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    fun buildSearchUrl(query: String, template: String = DEFAULT_SEARCH_TEMPLATE): String =
        template.replace("{query}", encodeUriComponent(query))

    /**
     * Address-bar input -> URL to load, or null for blank input.
     * http(s) and about:blank pass through, host-like text becomes a URL, everything else a web search.
     * Dangerous schemes (javascript:, file:, ...) are not navigated; they fall through to a search.
     */
    fun resolveInput(input: String, searchTemplate: String = DEFAULT_SEARCH_TEMPLATE): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        recoverEmbeddedUrl(trimmed)?.let { return it }
        if (isInternalSchemeInput(trimmed)) return null // TODO: internal pages (monobrowser://settings ...)
        if (isHttpUrl(trimmed) && !WHITESPACE.containsMatchIn(trimmed)) return trimmed
        if (isAboutBlankUrl(trimmed)) return trimmed
        if (looksLikeHost(trimmed)) return hostInputToUrl(trimmed)
        return buildSearchUrl(trimmed, searchTemplate)
    }
}
