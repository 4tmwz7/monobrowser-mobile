package com.voluene.monobrowser.browser

import com.voluene.monobrowser.data.WorkspaceOps

data class Tab(
    val id: Long,
    val title: String = "",
    val url: String = "",
    val isPrivate: Boolean = false,
    /**
     * 0 while loading, 100 when done. The live percentage is in TabManager.loadProgress (it changes many times per
     * page; keeping it out of the tab list keeps the whole screen from recomposing on every step).
     */
    val progress: Int = 100,
    val blockedCount: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Excluded from the sleeping-tab policy. */
    val neverSleep: Boolean = false,
    /** No live WebView: restored lazily when the tab is shown again. */
    val sleeping: Boolean = false,
    /** Workspace the tab belongs to (private tabs too: they live in the workspace they were opened in). */
    val workspaceId: String = WorkspaceOps.MAIN_ID,
    /** "Desktop site": desktop user agent and an overview-sized viewport. */
    val desktopMode: Boolean = false,
    /** The tab a link was opened from: back on a tab without history closes it and returns there. */
    val openerId: Long? = null,
    /** The loaded page looks like an article (reader view is offered in the address bar). */
    val readerAvailable: Boolean = false,
) {
    val isLoading: Boolean get() = progress in 0..99
}

/** Find-in-page state of the active tab (1-based [active], 0 when there is no match). */
data class FindState(val query: String = "", val active: Int = 0, val total: Int = 0)
