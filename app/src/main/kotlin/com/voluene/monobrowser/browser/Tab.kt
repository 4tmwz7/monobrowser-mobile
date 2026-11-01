package com.voluene.monobrowser.browser

import com.voluene.monobrowser.data.WorkspaceOps

data class Tab(
    val id: Long,
    val title: String = "",
    val url: String = "",
    val isPrivate: Boolean = false,
    /** 0..100 */
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
) {
    val isLoading: Boolean get() = progress in 0..99
}

/** Find-in-page state of the active tab (1-based [active], 0 when there is no match). */
data class FindState(val query: String = "", val active: Int = 0, val total: Int = 0)
