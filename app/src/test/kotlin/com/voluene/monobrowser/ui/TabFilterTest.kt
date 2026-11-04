package com.voluene.monobrowser.ui

import com.voluene.monobrowser.browser.Tab
import com.voluene.monobrowser.ui.tabs.tabMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabFilterTest {
    private val tab = Tab(1, title = "Łódź – Wikipedia, wolna encyklopedia", url = "https://pl.wikipedia.org/wiki/%C5%81%C3%B3d%C5%BA")

    @Test fun emptyQueryMatchesEverything() {
        assertTrue(tabMatches(tab, ""))
        assertTrue(tabMatches(tab, "   "))
    }

    @Test fun allWordsMustMatchTitleOrUrl() {
        assertTrue(tabMatches(tab, "wiki"))
        assertTrue(tabMatches(tab, "WIKIPEDIA encyklopedia"))
        assertTrue(tabMatches(tab, "pl.wikipedia"))
        assertFalse(tabMatches(tab, "wiki github"))
    }

    @Test fun accentsAreIgnored() {
        assertTrue(tabMatches(tab, "lodz"))
        assertTrue(tabMatches(tab, "łódź"))
    }
}
