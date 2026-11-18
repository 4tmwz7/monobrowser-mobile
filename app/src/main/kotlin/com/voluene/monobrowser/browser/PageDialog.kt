package com.voluene.monobrowser.browser

/** A prompt from a page that the UI shows as a monochrome dialog (permissions, JS alert/confirm/prompt). */
sealed interface PageDialog {
    val id: Long
    val tabId: Long

    /** Host (or origin) shown in the dialog. */
    val origin: String

    /** Resolves the dialog as dismissed (tab closed, page navigated away). */
    fun cancel()

    class Permission(
        override val id: Long,
        override val tabId: Long,
        override val origin: String,
        val wants: List<SitePermission>,
        val resolve: (Boolean) -> Unit,
    ) : PageDialog {
        override fun cancel() = resolve(false)
    }

    class Alert(
        override val id: Long,
        override val tabId: Long,
        override val origin: String,
        val message: String,
        val close: () -> Unit,
    ) : PageDialog {
        override fun cancel() = close()
    }

    class Confirm(
        override val id: Long,
        override val tabId: Long,
        override val origin: String,
        val message: String,
        val resolve: (Boolean) -> Unit,
    ) : PageDialog {
        override fun cancel() = resolve(false)
    }

    class Prompt(
        override val id: Long,
        override val tabId: Long,
        override val origin: String,
        val message: String,
        val default: String,
        val resolve: (String?) -> Unit,
    ) : PageDialog {
        override fun cancel() = resolve(null)
    }

    /** A link that wants to start an app: "Open in app" or "Stay in browser" (back / tap outside = stay). */
    class OpenInApp(
        override val id: Long,
        override val tabId: Long,
        override val origin: String,
        /** The one app that handles the link; null = not known / several (the dialog says "app"). */
        val appLabel: String?,
        /** Several apps handle the link (no default): their names, shown under the title. */
        val choices: List<String>,
        /** What the link points to, shown small (own schemes only). */
        val target: String?,
        val open: () -> Unit,
        val stay: () -> Unit,
        /** The tab is closing or going to sleep: just drop the question (no fallback load into a dying WebView). */
        val dismiss: () -> Unit,
    ) : PageDialog {
        override fun cancel() = dismiss()
    }
}
