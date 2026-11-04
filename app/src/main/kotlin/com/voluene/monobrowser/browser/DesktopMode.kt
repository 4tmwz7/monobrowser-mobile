package com.voluene.monobrowser.browser

/**
 * "Desktop site": a per-tab user agent that looks like desktop Chrome on Linux, derived from the WebView's own
 * mobile user agent so the Chrome version stays current. Pure, unit-tested.
 */
object DesktopMode {
    private val PLATFORM = Regex("""\(Linux; Android[^)]*\)""")
    private val MOBILE = Regex("""\s+Mobile(?=\s|$)""")
    private val WEBVIEW_VERSION = Regex("""\s+Version/\d+(\.\d+)*""")

    const val DESKTOP_PLATFORM = "(X11; Linux x86_64)"

    /** The desktop variant of [mobileUserAgent]; unchanged when it does not look like an Android user agent. */
    fun userAgent(mobileUserAgent: String): String {
        if (!PLATFORM.containsMatchIn(mobileUserAgent)) return mobileUserAgent
        return mobileUserAgent
            .replace(PLATFORM, DESKTOP_PLATFORM)
            .replace(MOBILE, "")
            .replace(WEBVIEW_VERSION, "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}
