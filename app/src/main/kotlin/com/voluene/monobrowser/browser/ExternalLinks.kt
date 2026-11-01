package com.voluene.monobrowser.browser

import java.net.URLDecoder

/** What to do with a URL the WebView wants to navigate to (pure logic; the Android side lives in [ExternalLauncher]). */
sealed interface LinkAction {
    /** Let the WebView load it (http, https, about, blob). */
    data object Load : LinkAction

    /** Open with an app via ACTION_VIEW (mailto:, tel:, sms:, geo:, market: ...). */
    data class OpenExternal(val url: String) : LinkAction

    /** Android `intent:` URI; [fallbackUrl] is the http(s) `browser_fallback_url`, if any. */
    data class OpenIntentUri(val uri: String, val fallbackUrl: String?) : LinkAction

    /** Unknown or unsafe scheme: do nothing (a toast tells the user). */
    data class Ignore(val scheme: String) : LinkAction
}

object ExternalLinks {
    private val LOAD_SCHEMES = setOf("http", "https", "about", "blob")
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "mms", "mmsto", "geo", "market")
    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")
    private val FALLBACK = Regex("""(?:^|;)S\.browser_fallback_url=([^;]*)""")

    fun schemeOf(url: String): String? = SCHEME.find(url.trim())?.groupValues?.get(1)?.lowercase()

    fun classify(url: String): LinkAction {
        val scheme = schemeOf(url) ?: return LinkAction.Ignore("")
        return when {
            scheme in LOAD_SCHEMES -> LinkAction.Load
            scheme in EXTERNAL_SCHEMES -> LinkAction.OpenExternal(url.trim())
            scheme == "intent" -> LinkAction.OpenIntentUri(url.trim(), intentFallbackUrl(url))
            else -> LinkAction.Ignore(scheme)
        }
    }

    /** `S.browser_fallback_url=` of an `intent:#Intent;...;end` URI, only if it is an http(s) URL. */
    fun intentFallbackUrl(uri: String): String? {
        val extras = uri.substringAfter("#Intent;", "")
        val raw = FALLBACK.find(extras)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        return decoded.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /** `package=` of an intent URI, used to offer the Play Store page when the app is missing. */
    fun intentPackage(uri: String): String? =
        Regex("""(?:^|;)package=([A-Za-z0-9._]+)""").find(uri.substringAfter("#Intent;", ""))?.groupValues?.get(1)
}
