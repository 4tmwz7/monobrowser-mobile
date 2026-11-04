package com.voluene.monobrowser.util

/** How the address bar shows a page: the site's host, plus whether the connection is encrypted. */
data class AddressDisplay(
    /** `example.com` (no `www.`), or the whole text for non-web pages. */
    val host: String,
    /** The rest of the URL (path, query), shown faded when the full address is wanted. */
    val rest: String,
    /** true = https, false = http, null = not a web page (blank, data:, about:). */
    val secure: Boolean?,
) {
    companion object {
        private val WEB = Regex("""^(https?)://([^/?#\s]*)(.*)$""", RegexOption.IGNORE_CASE)

        /** Pure, unit-tested. */
        fun of(url: String): AddressDisplay {
            val match = WEB.find(url.trim()) ?: return AddressDisplay(url, "", null)
            val scheme = match.groupValues[1].lowercase()
            val authority = match.groupValues[2].substringAfterLast('@')
            val host = authority.lowercase().removePrefix("www.").let { h -> if (h.endsWith(":443") && scheme == "https") h.dropLast(4) else h }
            val rest = match.groupValues[3].let { if (it == "/") "" else it }
            return AddressDisplay(host.ifEmpty { url }, rest, scheme == "https")
        }
    }
}
