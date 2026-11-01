package com.voluene.monobrowser.browser

import com.voluene.monobrowser.data.WorkspaceOps

/** Pure tab-list operations that depend on workspaces (the [TabManager] applies the results). */
object WorkspaceTabs {
    fun inWorkspace(tabs: List<Tab>, workspaceId: String): List<Tab> = tabs.filter { it.workspaceId == workspaceId }

    /**
     * The tab to show when switching to [workspaceId]: [preferred] (its last active tab) when it still belongs to
     * the workspace, otherwise the last tab of the workspace; null when the workspace has no tab.
     */
    fun restoreTarget(tabs: List<Tab>, workspaceId: String, preferred: Long?): Long? {
        val own = inWorkspace(tabs, workspaceId)
        return own.firstOrNull { it.id == preferred }?.id ?: own.lastOrNull()?.id
    }

    /** Moves a tab to the end of [workspaceId]'s tabs (end of the list). Unknown id / same workspace: unchanged. */
    fun moveTab(tabs: List<Tab>, tabId: Long, workspaceId: String): List<Tab> {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return tabs
        if (tab.workspaceId == workspaceId) return tabs
        return tabs.filterNot { it.id == tabId } + tab.copy(workspaceId = workspaceId)
    }

    /** Deleting a workspace but keeping its tabs: they join the main workspace (after its existing tabs). */
    fun moveAll(tabs: List<Tab>, from: String, to: String = WorkspaceOps.MAIN_ID): List<Tab> {
        val moved = tabs.filter { it.workspaceId == from }.map { it.copy(workspaceId = to) }
        return tabs.filterNot { it.workspaceId == from } + moved
    }

    /** The tab to activate after closing [closingId] (neighbour at the same position within its workspace), null if none. */
    fun neighbourAfterClose(tabs: List<Tab>, closingId: Long): Long? {
        val closing = tabs.firstOrNull { it.id == closingId } ?: return null
        val own = inWorkspace(tabs, closing.workspaceId)
        val index = own.indexOf(closing)
        val remaining = own.filterNot { it.id == closingId }
        return remaining.getOrNull(minOf(index, remaining.lastIndex))?.id
    }

    /** Previous/next tab within the workspace of [activeId] (no wrap-around), null at the ends. */
    fun relative(tabs: List<Tab>, activeId: Long, delta: Int): Long? {
        val active = tabs.firstOrNull { it.id == activeId } ?: return null
        val own = inWorkspace(tabs, active.workspaceId)
        return own.getOrNull(own.indexOf(active) + delta)?.id
    }
}
