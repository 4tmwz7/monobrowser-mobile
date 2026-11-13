package com.voluene.monobrowser.browser

import com.voluene.monobrowser.browser.FaviconPolicy.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaviconPolicyTest {
    @Test fun fileNameIsAPlainHostOrNothing() {
        assertEquals("example.com.png", FaviconPolicy.fileName("example.com"))
        assertEquals("news.example-site.co.uk.png", FaviconPolicy.fileName(" News.Example-Site.co.uk "))
        assertNull(FaviconPolicy.fileName(""))
        assertNull(FaviconPolicy.fileName("../etc/passwd"))
        assertNull(FaviconPolicy.fileName("a/b"))
        assertNull(FaviconPolicy.fileName("a..b"))
        assertNull(FaviconPolicy.fileName(".hidden"))
        assertNull(FaviconPolicy.fileName("exa mple.com"))
        assertNull(FaviconPolicy.fileName("a".repeat(201)))
    }

    @Test fun scaledSizeShrinksButNeverEnlarges() {
        assertEquals(16 to 16, FaviconPolicy.scaledSize(16, 16))
        assertEquals(96 to 96, FaviconPolicy.scaledSize(96, 96))
        assertEquals(96 to 96, FaviconPolicy.scaledSize(512, 512))
        assertEquals(96 to 48, FaviconPolicy.scaledSize(400, 200))
        assertEquals(1 to 96, FaviconPolicy.scaledSize(1, 960))
        assertEquals(0 to 0, FaviconPolicy.scaledSize(0, 10))
    }

    @Test fun pruneDropsTheOldestBeyondTheLimit() {
        val files = (1..5).map { Entry("f$it.png", it * 100L) }.shuffled()
        assertEquals(emptyList<String>(), FaviconPolicy.toPrune(files, 5))
        assertEquals(listOf("f1.png", "f2.png"), FaviconPolicy.toPrune(files, 3))
        assertEquals(5, FaviconPolicy.toPrune(files, 0).size)
    }

    @Test fun freshnessWindow() {
        val now = 10L * FaviconPolicy.REFRESH_MS
        assertTrue(FaviconPolicy.isFresh(now - 1000, now))
        assertFalse(FaviconPolicy.isFresh(now - FaviconPolicy.REFRESH_MS - 1, now))
        assertFalse(FaviconPolicy.isFresh(0, now))
    }
}
