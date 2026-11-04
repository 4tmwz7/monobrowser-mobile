package com.voluene.monobrowser.util

import java.net.URI

/**
 * Removes query parameters that only exist to track clicks (utm_*, fbclid, gclid ...) from http(s) URLs. The list
 * is deliberately short: parameters that some sites need for the page itself are left alone, and host-specific
 * ones (`si`) only go where they are known to be share trackers. Pure, unit-tested.
 */
object TrackingParams {
    private val ANYWHERE = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "gclsrc", "msclkid", "yclid", "twclid", "ttclid", "li_fat_id",
        "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id",
        "__s", "_openstat", "wickedid", "srsltid",
    )
    private val PREFIXES = listOf("utm_")
    private val BY_HOST = mapOf(
        "youtube.com" to setOf("si", "feature", "pp"),
        "youtu.be" to setOf("si", "feature"),
        "open.spotify.com" to setOf("si"),
        "instagram.com" to setOf("igshid", "igsh"),
        "x.com" to setOf("s", "t"),
        "twitter.com" to setOf("s", "t"),
    )

    private fun isTracking(name: String, host: String): Boolean {
        val key = name.lowercase()
        if (key in ANYWHERE || PREFIXES.any { key.startsWith(it) }) return true
        return BY_HOST.entries.any { (h, params) -> (host == h || host.endsWith(".$h")) && key in params }
    }

    /** [url] without tracking parameters; unchanged (same instance) when there is nothing to remove. */
    fun clean(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || !UrlUtils.isHttpUrl(url)) return url
        val host = runCatching { URI(url.substring(0, queryStart)).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val query = url.substring(queryStart + 1, fragmentStart)
        val parts = query.split('&')
        val kept = parts.filter { part -> part.isNotEmpty() && !isTracking(part.substringBefore('='), host) }
        if (kept.size == parts.count { it.isNotEmpty() }) return url
        val base = url.substring(0, queryStart)
        val fragment = url.substring(fragmentStart)
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }
}
