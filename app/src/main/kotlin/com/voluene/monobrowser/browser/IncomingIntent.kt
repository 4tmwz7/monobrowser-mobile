package com.voluene.monobrowser.browser

import com.voluene.monobrowser.util.UrlUtils

/** What an Intent sent to the app asks for. Pure (strings in, decision out), unit-tested. */
sealed interface IncomingRequest {
    /** Open [input] (a URL, or text to search for) in a new tab. */
    data class Open(val input: String) : IncomingRequest
    data class NewTab(val isPrivate: Boolean) : IncomingRequest
    data object None : IncomingRequest
}

object IncomingIntent {
    const val ACTION_NEW_TAB = "com.voluene.monobrowser.action.NEW_TAB"
    const val ACTION_NEW_PRIVATE_TAB = "com.voluene.monobrowser.action.NEW_PRIVATE_TAB"

    private const val VIEW = "android.intent.action.VIEW"
    private const val SEND = "android.intent.action.SEND"
    private const val PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    private const val WEB_SEARCH = "android.intent.action.WEB_SEARCH"
    private const val MAX_TEXT = 2000

    /**
     * @param data the Intent's data URI (VIEW).
     * @param text EXTRA_TEXT (SEND), EXTRA_PROCESS_TEXT (PROCESS_TEXT) or SearchManager.QUERY (WEB_SEARCH).
     */
    fun parse(action: String?, data: String?, text: CharSequence?): IncomingRequest = when (action) {
        VIEW -> data?.takeIf { UrlUtils.isHttpUrl(it) }?.let { IncomingRequest.Open(it) } ?: IncomingRequest.None
        SEND, PROCESS_TEXT, WEB_SEARCH -> fromText(text)
        ACTION_NEW_TAB -> IncomingRequest.NewTab(isPrivate = false)
        ACTION_NEW_PRIVATE_TAB -> IncomingRequest.NewTab(isPrivate = true)
        else -> IncomingRequest.None
    }

    /** Shared text: a link inside it ("Look at this: https://...") wins; otherwise the text is searched. */
    private fun fromText(text: CharSequence?): IncomingRequest {
        val value = text?.toString()?.trim()?.take(MAX_TEXT).orEmpty()
        if (value.isEmpty()) return IncomingRequest.None
        val link = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(value)?.value?.trimEnd('.', ',', ')', ']', '"', '\'', '>')
        return IncomingRequest.Open(link ?: value.replace(Regex("\\s+"), " "))
    }
}
