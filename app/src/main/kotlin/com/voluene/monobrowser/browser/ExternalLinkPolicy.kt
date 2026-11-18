package com.voluene.monobrowser.browser

/** What to do with a non-web link the page wants to open. */
sealed interface ExternalDecision {
    /** Nothing: no gesture (a script must not start apps or show dialogs), or a link that loads normally. */
    data object None : ExternalDecision
    data object ToastUnsupported : ExternalDecision
    data object ToastNoApp : ExternalDecision

    /** mailto:, tel:, sms:, geo: ... start at once. */
    data object OpenNow : ExternalDecision

    /** The `intent:` link names no installed app: load its `browser_fallback_url` straight away. */
    data class LoadFallback(val url: String) : ExternalDecision

    /**
     * Ask "Open in app" / "Stay in browser"; [appLabel] is the one app that opens it (null when not known or several:
     * then [choices] names them and "Open" shows the system chooser). [stayUrl] loads on "Stay".
     */
    data class Ask(val appLabel: String?, val stayUrl: String?, val choices: List<String> = emptyList()) : ExternalDecision
}

/** Pure decision logic for [ExternalLinks] actions; [apps] comes from `ExternalLauncher.resolveApps` (null = must not be started). */
object ExternalLinkPolicy {
    fun decide(action: LinkAction, hasGesture: Boolean, apps: List<String>?): ExternalDecision {
        if (action == LinkAction.Load || !hasGesture) return ExternalDecision.None
        return when (action) {
            LinkAction.Load -> ExternalDecision.None
            is LinkAction.Ignore -> ExternalDecision.ToastUnsupported
            is LinkAction.OpenExternal -> ExternalDecision.OpenNow
            is LinkAction.OpenApp ->
                if (apps.isNullOrEmpty()) ExternalDecision.ToastNoApp else ask(apps, null)
            is LinkAction.OpenIntentUri -> when {
                !apps.isNullOrEmpty() -> ask(apps, action.fallbackUrl)
                action.fallbackUrl != null -> ExternalDecision.LoadFallback(action.fallbackUrl)
                // Unsafe intents are never started, not even through the store.
                apps != null && ExternalLinks.intentPackage(action.uri) != null -> ExternalDecision.Ask(null, null)
                else -> ExternalDecision.ToastNoApp
            }
        }
    }

    private fun ask(apps: List<String>, stayUrl: String?) =
        if (apps.size == 1) ExternalDecision.Ask(apps[0], stayUrl) else ExternalDecision.Ask(null, stayUrl, apps)
}
