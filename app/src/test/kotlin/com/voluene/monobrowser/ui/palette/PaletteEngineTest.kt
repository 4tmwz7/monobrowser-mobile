package com.voluene.monobrowser.ui.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteEngineTest {
    private val tabs = listOf(
        PaletteTab(1, "Wikipedia", "https://en.wikipedia.org/wiki/Main", false),
        PaletteTab(2, "Mail", "https://mail.example.com/", true),
    )
    private val bookmarks = listOf(
        PaletteLink("Wikipedia", "https://en.wikipedia.org/wiki/Main"), // same URL as an open tab
        PaletteLink("Rust blog", "https://blog.rust-lang.org/"),
        PaletteLink("Zażółć gęślą", "https://pl.example/zazolc"),
    )
    private val actions = listOf(
        PaletteAction(PaletteActionId.NEW_TAB, "New tab", "nowa karta"),
        PaletteAction(PaletteActionId.OPEN_SETTINGS, "Open settings", "ustawienia preferences"),
        PaletteAction(PaletteActionId.OPEN_HISTORY, "History", "historia"),
        PaletteAction(PaletteActionId.TOGGLE_ADBLOCK_SITE, "Disable adblock on this site", "reklamy ads"),
    )

    private fun search(q: String, history: List<PaletteLink> = emptyList()) =
        PaletteEngine.search(q, tabs, bookmarks, history, actions)

    @Test fun firstResultIsGoOrSearch() {
        val url = search("example.org").first() as PaletteResult.Go
        assertFalse(url.isSearch)
        val query = search("kotlin flow").first() as PaletteResult.Go
        assertTrue(query.isSearch)
        assertEquals("kotlin flow", query.input)
        assertTrue((search("https://a.com/x").first() as PaletteResult.Go).let { !it.isSearch })
    }

    @Test fun emptyQueryHasNoGoRowAndListsTabsAndActions() {
        val r = search("")
        assertTrue(r.none { it is PaletteResult.Go })
        assertEquals(2, r.count { it is PaletteResult.OpenTab })
        assertTrue(r.any { it is PaletteResult.Run })
        assertTrue(r.none { it is PaletteResult.History })
    }

    @Test fun groupsAreOrderedGoTabsBookmarksHistoryActions() {
        val r = search("e", history = listOf(PaletteLink("Hist e", "https://hist.example/")))
        val sections = r.map { it.section() }
        assertEquals(sections.sortedBy { it.ordinal }, sections)
        assertTrue(sections.containsAll(listOf(PaletteSection.GO, PaletteSection.TABS, PaletteSection.HISTORY, PaletteSection.ACTIONS)))
    }

    @Test fun bookmarkAlreadyOpenAsTabIsNotRepeated() {
        val r = search("wiki")
        assertEquals(1, r.count { it is PaletteResult.OpenTab })
        assertEquals(0, r.count { it is PaletteResult.Bookmark })
    }

    @Test fun historyDuplicatesOfTabsAndBookmarksAreDropped() {
        val h = listOf(PaletteLink("Rust", "https://blog.rust-lang.org/"), PaletteLink("Other rust", "https://other.example/rust"))
        val r = search("rust", h)
        assertEquals(1, r.count { it is PaletteResult.Bookmark })
        assertEquals(listOf("https://other.example/rust"), r.filterIsInstance<PaletteResult.History>().map { it.link.url })
    }

    @Test fun matchingIgnoresCaseAndDiacritics() {
        assertEquals(1, search("ZAZOLC").count { it is PaletteResult.Bookmark })
        assertEquals(1, search("gesla").count { it is PaletteResult.Bookmark })
    }

    @Test fun allWordsMustMatchAndKeywordsCount() {
        assertEquals(listOf(PaletteActionId.OPEN_SETTINGS), search("preferences").filterIsInstance<PaletteResult.Run>().map { it.action.id })
        assertEquals(listOf(PaletteActionId.TOGGLE_ADBLOCK_SITE), search("adblock site").filterIsInstance<PaletteResult.Run>().map { it.action.id })
        assertEquals(0, search("adblock history").count { it is PaletteResult.Run })
    }

    @Test fun fuzzyFallbackForActionsOnly() {
        val r = search("ostg") // subsequence of "open settings"
        assertEquals(listOf(PaletteActionId.OPEN_SETTINGS), r.filterIsInstance<PaletteResult.Run>().map { it.action.id })
    }

    @Test fun titlePrefixRanksBeforeUrlOnlyMatch() {
        val links = listOf(PaletteLink("About rust", "https://a.example/"), PaletteLink("Rust blog", "https://b.example/"))
        val r = PaletteEngine.search("rust", emptyList(), links, emptyList(), emptyList()).filterIsInstance<PaletteResult.Bookmark>()
        assertEquals("Rust blog", r.first().link.title)
    }

    @Test fun limitsPerGroup() {
        val many = (1..20).map { PaletteLink("Item $it", "https://x.example/$it") }
        val r = PaletteEngine.search("item", emptyList(), many, many, emptyList())
        assertEquals(PaletteEngine.MAX_BOOKMARKS, r.count { it is PaletteResult.Bookmark })
        assertEquals(PaletteEngine.MAX_HISTORY, r.count { it is PaletteResult.History })
        // History never repeats a URL that is already shown as a bookmark.
        val shown = r.filterIsInstance<PaletteResult.Bookmark>().map { it.link.url }.toSet()
        assertTrue(r.filterIsInstance<PaletteResult.History>().none { it.link.url in shown })
    }

    @Test fun tabsOfOtherWorkspacesAreSearchableByWorkspaceName() {
        val all = listOf(
            PaletteTab(1, "Docs", "https://docs.example/", false, workspace = "Main"),
            PaletteTab(2, "Tracker", "https://tracker.example/", false, workspace = "Praca"),
        )
        val byName = PaletteEngine.search("praca", all, emptyList(), emptyList(), emptyList())
            .filterIsInstance<PaletteResult.OpenTab>()
        assertEquals(listOf(2L), byName.map { it.tab.id })
        assertEquals("Praca", byName.single().tab.workspace)
        // Title search still works across workspaces.
        assertEquals(listOf(1L), PaletteEngine.search("docs", all, emptyList(), emptyList(), emptyList()).filterIsInstance<PaletteResult.OpenTab>().map { it.tab.id })
    }

    @Test fun workspaceActionsKeepTheirArgument() {
        val switch = PaletteAction(PaletteActionId.SWITCH_WORKSPACE, "Switch to workspace Praca", "przestrzen przelacz workspace", arg = "ws-praca")
        val hit = PaletteEngine.search("przelacz", emptyList(), emptyList(), emptyList(), listOf(switch) + actions)
            .filterIsInstance<PaletteResult.Run>().single()
        assertEquals("ws-praca", hit.action.arg)
        assertEquals(PaletteActionId.SWITCH_WORKSPACE, hit.action.id)
    }
}
