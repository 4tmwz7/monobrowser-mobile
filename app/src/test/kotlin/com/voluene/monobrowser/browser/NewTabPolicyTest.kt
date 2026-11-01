package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewTabPolicyTest {
    private fun tab(id: Long, url: String = "", isPrivate: Boolean = false, canGoBack: Boolean = false) =
        Tab(id = id, url = url, isPrivate = isPrivate, canGoBack = canGoBack)

    @Test fun blankTabCountsAsEmpty() {
        assertTrue(NewTabPolicy.isEmptyNewTab(tab(1), null))
        assertTrue(NewTabPolicy.isEmptyNewTab(tab(1, "about:blank"), null))
        assertFalse(NewTabPolicy.isEmptyNewTab(tab(1, "https://a.com"), null))
        assertFalse(NewTabPolicy.isEmptyNewTab(tab(1, "", canGoBack = true), null))
    }

    @Test fun homepageTabCountsAsEmptyOnlyWithoutHistory() {
        assertTrue(NewTabPolicy.isEmptyNewTab(tab(1, "https://home.example/"), "https://home.example/"))
        assertFalse(NewTabPolicy.isEmptyNewTab(tab(1, "https://home.example/", canGoBack = true), "https://home.example/"))
        assertFalse(NewTabPolicy.isEmptyNewTab(tab(1, "https://home.example/"), null))
    }

    @Test fun noEmptyTabMeansCreate() {
        val tabs = listOf(tab(1, "https://a.com"), tab(2, "https://b.com"))
        assertNull(NewTabPolicy.findReusable(tabs, 1, false, null))
    }

    @Test fun reusesTheEmptyTab() {
        val tabs = listOf(tab(1, "https://a.com"), tab(2), tab(3, "https://c.com"))
        assertEquals(2L, NewTabPolicy.findReusable(tabs, 1, false, null)?.id)
    }

    @Test fun activeEmptyTabWinsOverEarlierOne() {
        val tabs = listOf(tab(1), tab(2, "https://b.com"), tab(3))
        assertEquals(3L, NewTabPolicy.findReusable(tabs, 3, false, null)?.id)
        assertEquals(1L, NewTabPolicy.findReusable(tabs, 2, false, null)?.id)
    }

    @Test fun privateAndNormalAreSeparate() {
        val tabs = listOf(tab(1, isPrivate = true), tab(2, "https://b.com"))
        assertNull(NewTabPolicy.findReusable(tabs, 2, isPrivate = false, homepage = null))
        assertEquals(1L, NewTabPolicy.findReusable(tabs, 2, isPrivate = true, homepage = null)?.id)
        val withNormal = tabs + tab(3)
        assertEquals(3L, NewTabPolicy.findReusable(withNormal, 2, isPrivate = false, homepage = null)?.id)
        assertEquals(1L, NewTabPolicy.findReusable(withNormal, 2, isPrivate = true, homepage = null)?.id)
    }
}
