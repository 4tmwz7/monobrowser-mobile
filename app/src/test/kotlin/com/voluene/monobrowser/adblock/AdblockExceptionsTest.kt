package com.voluene.monobrowser.adblock

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdblockExceptionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun normalizeHost() {
        assertEquals("example.com", HostUtils.normalizeHost("WWW.Example.com."))
        assertEquals("example.com", HostUtils.normalizeHost("https://www.example.com:8080/path?x=1#h"))
        assertEquals("a.b.c", HostUtils.normalizeHost(" a.b.c "))
        assertNull(HostUtils.normalizeHost(""))
        assertNull(HostUtils.normalizeHost("exa mple.com"))
        assertNull(HostUtils.normalizeHost("example.com/path"))
        assertNull(HostUtils.normalizeHost("user@example.com"))
        assertNull(HostUtils.normalizeHost("example.com:80"))
        assertNull(HostUtils.normalizeHost("-bad-.com"))
        assertNull(HostUtils.normalizeHost("[::1]"))
        assertNull(HostUtils.normalizeHost(null))
    }

    @Test fun hostOfUrl() {
        assertEquals("example.com", HostUtils.hostOfUrl("https://user:pw@Example.com:443/a?b#c"))
        assertEquals("[::1]", HostUtils.hostOfUrl("http://[::1]:8080/"))
        assertNull(HostUtils.hostOfUrl("about:blank"))
        assertNull(HostUtils.hostOfUrl(null))
    }

    @Test fun subdomainsAreExcepted() {
        val ex = AdblockExceptions(null)
        assertTrue(ex.add("https://www.example.com/page"))
        assertTrue(ex.contains("example.com"))
        assertTrue(ex.contains("https://sub.example.com/x"))
        assertTrue(ex.contains("a.b.example.com"))
        assertFalse(ex.contains("notexample.com"))
        assertFalse(ex.contains("com"))
        assertFalse(ex.contains(null))
        assertTrue(ex.remove("example.com"))
        assertFalse(ex.contains("example.com"))
    }

    @Test fun invalidInputRejected() {
        val ex = AdblockExceptions(null)
        assertFalse(ex.add("not a host"))
        assertFalse(ex.remove("a/b"))
        assertNull(ex.toggle("???"))
        assertTrue(ex.list().isEmpty())
    }

    @Test fun toggleFlips() {
        val ex = AdblockExceptions(null)
        assertEquals(true, ex.toggle("example.org"))
        assertTrue(ex.contains("example.org"))
        assertEquals(false, ex.toggle("https://example.org/"))
        assertFalse(ex.contains("example.org"))
    }

    @Test fun persistsAsJson() {
        val file = File(tmp.root, "sub/exceptions.json")
        val ex = AdblockExceptions(file)
        ex.add("b.org"); ex.add("a.com"); ex.add("a.com")
        assertEquals("""{"hosts":["a.com","b.org"]}""", file.readText())
        val reloaded = AdblockExceptions(file)
        assertEquals(listOf("a.com", "b.org"), reloaded.list())
        assertTrue(reloaded.contains("x.a.com"))
    }

    @Test fun parseIsLenient() {
        assertEquals(setOf("a.com", "b.org"), AdblockExceptions.parse("""{"hosts":["B.org","a.com","bad host","a.com"]}"""))
        assertEquals(setOf("a.com"), AdblockExceptions.parse("""["https://a.com/x"]"""))
        assertTrue(AdblockExceptions.parse("garbage").isEmpty())
        assertTrue(AdblockExceptions(File(tmp.root, "missing.json")).list().isEmpty())
    }
}
