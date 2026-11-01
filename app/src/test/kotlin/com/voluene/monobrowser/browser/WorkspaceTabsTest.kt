package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceTabsTest {
    private fun tab(id: Long, ws: String, url: String = "https://t$id.example/") = Tab(id = id, url = url, workspaceId = ws)

    private val tabs = listOf(tab(1, "main"), tab(2, "work"), tab(3, "main"), tab(4, "work"), tab(5, "work"))

    @Test fun inWorkspaceFilters() {
        assertEquals(listOf(2L, 4L, 5L), WorkspaceTabs.inWorkspace(tabs, "work").map { it.id })
        assertEquals(emptyList<Tab>(), WorkspaceTabs.inWorkspace(tabs, "ghost"))
    }

    @Test fun restoreTargetPrefersLastActiveThenLastTab() {
        assertEquals(4L, WorkspaceTabs.restoreTarget(tabs, "work", 4))
        assertEquals(5L, WorkspaceTabs.restoreTarget(tabs, "work", 1)) // 1 belongs to another workspace
        assertEquals(5L, WorkspaceTabs.restoreTarget(tabs, "work", null))
        assertNull(WorkspaceTabs.restoreTarget(tabs, "empty", null))
    }

    @Test fun moveTabGoesToTheEndAndChangesWorkspace() {
        val moved = WorkspaceTabs.moveTab(tabs, 1, "work")
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), moved.map { it.id })
        assertEquals("work", moved.last().workspaceId)
        assertEquals(tabs, WorkspaceTabs.moveTab(tabs, 1, "main")) // same workspace
        assertEquals(tabs, WorkspaceTabs.moveTab(tabs, 99, "work")) // unknown tab
    }

    @Test fun moveAllJoinsMainAfterItsTabs() {
        val moved = WorkspaceTabs.moveAll(tabs, "work")
        assertEquals(listOf(1L, 3L, 2L, 4L, 5L), moved.map { it.id })
        assertEquals(setOf("main"), moved.map { it.workspaceId }.toSet())
    }

    @Test fun neighbourAfterCloseStaysInTheWorkspace() {
        assertEquals(5L, WorkspaceTabs.neighbourAfterClose(tabs, 4)) // next one in "work"
        assertEquals(4L, WorkspaceTabs.neighbourAfterClose(tabs, 5)) // last: previous one
        assertEquals(3L, WorkspaceTabs.neighbourAfterClose(tabs, 1))
        assertNull(WorkspaceTabs.neighbourAfterClose(listOf(tab(1, "main"), tab(2, "work")), 2)) // only tab of its workspace
        assertNull(WorkspaceTabs.neighbourAfterClose(tabs, 99))
    }

    @Test fun relativeSwitchingStaysInTheWorkspace() {
        assertEquals(4L, WorkspaceTabs.relative(tabs, 2, 1))
        assertEquals(3L, WorkspaceTabs.relative(tabs, 1, 1))
        assertNull(WorkspaceTabs.relative(tabs, 2, -1)) // first of "work"; tab 1 is in another workspace
        assertNull(WorkspaceTabs.relative(tabs, 5, 1))
    }

    @Test fun newTabDedupeIsPerWorkspace() {
        val blank = listOf(tab(1, "main", ""), tab(2, "work", "https://x.example/"))
        // An empty tab exists in "main" only: asking from "work" must not reuse it.
        assertNull(NewTabPolicy.findReusable(WorkspaceTabs.inWorkspace(blank, "work"), 2, isPrivate = false, homepage = null))
        assertEquals(1L, NewTabPolicy.findReusable(WorkspaceTabs.inWorkspace(blank, "main"), 1, isPrivate = false, homepage = null)?.id)
    }
}
