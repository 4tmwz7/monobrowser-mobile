package com.voluene.monobrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockToggleSiteTest {
    @Test fun togglesHostFromUrl() {
        val ex = AdblockExceptions(null)
        assertEquals(true, ex.toggleSite("https://www.example.com/page?x=1"))
        assertEquals(listOf("example.com"), ex.list())
        assertEquals(false, ex.toggleSite("https://example.com/"))
        assertTrue(ex.list().isEmpty())
    }

    @Test fun subdomainOfExceptedSiteRemovesTheParentEntry() {
        val ex = AdblockExceptions(null)
        ex.add("example.com")
        assertTrue(ex.contains("https://news.example.com/"))
        assertEquals(false, ex.toggleSite("https://news.example.com/"))
        assertFalse(ex.contains("https://news.example.com/"))
    }

    @Test fun invalidHostIsRejected() {
        assertNull(AdblockExceptions(null).toggleSite("not a host"))
    }

    @Test fun hostsFlowTracksChanges() {
        val ex = AdblockExceptions(null)
        ex.add("b.com"); ex.add("a.com")
        assertEquals(listOf("a.com", "b.com"), ex.hostsFlow.value)
        ex.remove("a.com")
        assertEquals(listOf("b.com"), ex.hostsFlow.value)
    }
}
