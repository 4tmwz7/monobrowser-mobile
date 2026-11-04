package com.voluene.monobrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopSitesTest {
    private fun v(url: String, visits: Int, last: Long = 0, title: String = "") = VisitCount(url, title, visits, last)

    @Test fun hostKey() {
        assertEquals("example.com", TopSites.hostKey("https://www.example.com/a?b"))
        assertEquals("news.example.com", TopSites.hostKey("http://News.Example.com"))
        assertNull(TopSites.hostKey("about:blank"))
        assertNull(TopSites.hostKey("file:///sdcard/x.html"))
        assertNull(TopSites.hostKey("not a url"))
    }

    @Test fun groupsBySiteAndSortsByTotalVisits() {
        val sites = TopSites.pick(
            listOf(
                v("https://a.com/1", 3), v("https://a.com/2", 3), // a.com: 6
                v("https://www.b.com/", 5), // b.com: 5
                v("https://c.com/x", 9), // c.com: 9
            ),
            hidden = emptySet(),
        )
        assertEquals(listOf("c.com", "a.com", "b.com"), sites.map { it.host })
    }

    @Test fun prefersTheRootPageOfASite() {
        val sites = TopSites.pick(
            listOf(v("https://a.com/article", 10, title = "Article"), v("https://a.com/", 2, title = "A home")),
            hidden = emptySet(),
        )
        assertEquals("https://a.com/", sites.single().url)
        assertEquals("A home", sites.single().title)
    }

    @Test fun mostVisitedUrlWhenNoRootPage() {
        val sites = TopSites.pick(
            listOf(v("https://a.com/x", 1, last = 5), v("https://a.com/y", 4, last = 1), v("https://a.com/?q=1", 7)),
            hidden = emptySet(),
        )
        assertEquals("https://a.com/?q=1", sites.single().url)
    }

    @Test fun ties() {
        val sites = TopSites.pick(listOf(v("https://a.com/", 2, last = 1), v("https://b.com/", 2, last = 9)), hidden = emptySet())
        assertEquals(listOf("b.com", "a.com"), sites.map { it.host })
    }

    @Test fun skipsHiddenAndNonWebAndHonoursLimit() {
        val counts = (1..20).map { v("https://site$it.com/", it) } + v("about:blank", 100) + v("https://www.hidden.com/", 99)
        val sites = TopSites.pick(counts, hidden = setOf("hidden.com"), limit = 4)
        assertEquals(listOf("site20.com", "site19.com", "site18.com", "site17.com"), sites.map { it.host })
        assertTrue(sites.none { it.host == "hidden.com" })
    }

    @Test fun labels() {
        assertEquals("ycombinator", TopSites.label("news.ycombinator.com"))
        assertEquals("example", TopSites.label("example.co.uk"))
        assertEquals("wikipedia", TopSites.label("pl.wikipedia.org"))
        assertEquals("localhost", TopSites.label("localhost"))
        assertEquals("onet", TopSites.label("onet.pl"))
    }

    @Test fun pinnedTilesComeFirstAndReplaceTheSameSite() {
        val pinned = listOf(TopSite("https://b.com/x", "B", "b.com"), TopSite("https://z.com/", "Z", "z.com"))
        val frequent = listOf(TopSite("https://a.com/", "A", "a.com"), TopSite("https://b.com/", "B home", "b.com"), TopSite("https://c.com/", "C", "c.com"))
        val merged = TopSites.merge(pinned, frequent, max = 4)
        assertEquals(listOf("b.com", "z.com", "a.com", "c.com"), merged.map { it.host })
        assertEquals(listOf(true, true, false, false), merged.map { it.pinned })
        assertEquals("https://b.com/x", merged.first().url)
        assertEquals(3, TopSites.merge(pinned, frequent, max = 3).size)
    }
}
