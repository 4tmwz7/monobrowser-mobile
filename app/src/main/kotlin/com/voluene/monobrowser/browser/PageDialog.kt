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
}
