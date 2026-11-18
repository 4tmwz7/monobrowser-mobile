package com.voluene.monobrowser.browser

import java.net.URLDecoder

/** What to do with a URL the WebView wants to navigate to (pure logic; the Android side lives in [ExternalLauncher]). */
sealed interface LinkAction {
    /** Let the WebView load it (http, https, about, blob). */
    data object Load : LinkAction

    /** Open at once with an app via ACTION_VIEW (mailto:, tel:, sms:, smsto:, mms:, mmsto:, geo:): no question asked. */
    data class OpenExternal(val url: String) : LinkAction

    /** An app's own scheme (reddit:, vnd.youtube:, fb:, market: ...): the user is asked first ("Open in app" / "Stay in browser"). */
    data class OpenApp(val url: String, val scheme: String) : LinkAction

    /** Android `intent:` URI; [fallbackUrl] is the http(s) `browser_fallback_url`, if any. */
    data class OpenIntentUri(val uri: String, val fallbackUrl: String?) : LinkAction

    /** Unknown or unsafe scheme: do nothing (a toast tells the user). */
    data class Ignore(val scheme: String) : LinkAction
}

object ExternalLinks {
    private val LOAD_SCHEMES = setOf("http", "https", "about", "blob")
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "mms", "mmsto", "geo")

    /** Browser-internal, script, file and intent-like schemes that never start an app. */
    private val BLOCKED_SCHEMES = setOf(
        "javascript", "vbscript", "file", "content", "data", "chrome", "chrome-extension", "chrome-search",
        "chrome-untrusted", "chrome-error", "devtools", "view-source", "filesystem", "jar", "resource",
        "android-app", "ftp", "ws", "wss", "monobrowser-retry",
    )
    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")
    private val FALLBACK = Regex("""(?:^|;)S\.browser_fallback_url=([^;]*)""")

    fun schemeOf(url: String): String? = SCHEME.find(url.trim())?.groupValues?.get(1)?.lowercase()

    fun classify(url: String): LinkAction {
        val scheme = schemeOf(url) ?: return LinkAction.Ignore("")
        return when {
            scheme in LOAD_SCHEMES -> LinkAction.Load
            scheme in EXTERNAL_SCHEMES -> LinkAction.OpenExternal(url.trim())
            scheme == "intent" -> LinkAction.OpenIntentUri(url.trim(), intentFallbackUrl(url))
            scheme in BLOCKED_SCHEMES -> LinkAction.Ignore(scheme)
            else -> LinkAction.OpenApp(url.trim(), scheme)
        }
    }

    /** `S.browser_fallback_url=` of an `intent:#Intent;...;end` URI, only if it is an http(s) URL. */
    fun intentFallbackUrl(uri: String): String? {
        val extras = uri.substringAfter("#Intent;", "")
        val raw = FALLBACK.find(extras)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        return decoded.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /**
     * Short, readable description of where an app link goes, shown small under the "Open in app?" title: the app id for
     * store links and `intent:` links that name a package, otherwise the scheme, host and path without the (usually long,
     * tracking) query. Null when there is nothing useful to show.
     */
    fun displayTarget(action: LinkAction): String? {
        val raw = when (action) {
            is LinkAction.OpenApp -> action.url
            is LinkAction.OpenIntentUri -> return intentPackage(action.uri)
            else -> return null
        }
        if (schemeOf(raw) == "market") {
            Regex("""[?&]id=([A-Za-z0-9._]+)""").find(raw)?.groupValues?.get(1)?.let { return it }
        }
        val short = raw.substringBefore('?').substringBefore('#').trimEnd('/')
        if (short.isBlank()) return null
        return if (short.length > MAX_TARGET) short.take(MAX_TARGET - 1) + "\u2026" else short
    }

    private const val MAX_TARGET = 60

    /** `package=` of an intent URI, used to offer the Play Store page when the app is missing. */
    fun intentPackage(uri: String): String? =
        Regex("""(?:^|;)package=([A-Za-z0-9._]+)""").find(uri.substringAfter("#Intent;", ""))?.groupValues?.get(1)
}
