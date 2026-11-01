package com.voluene.monobrowser.util

import com.voluene.monobrowser.util.UrlUtils.displayUrl
import com.voluene.monobrowser.util.UrlUtils.hostInputToUrl
import com.voluene.monobrowser.util.UrlUtils.isAboutBlankUrl
import com.voluene.monobrowser.util.UrlUtils.isHttpUrl
import com.voluene.monobrowser.util.UrlUtils.isInternalSchemeInput
import com.voluene.monobrowser.util.UrlUtils.isLocalHost
import com.voluene.monobrowser.util.UrlUtils.isLoopbackHost
import com.voluene.monobrowser.util.UrlUtils.looksLikeHost
import com.voluene.monobrowser.util.UrlUtils.recoverEmbeddedUrl
import com.voluene.monobrowser.util.UrlUtils.resolveInput
import com.voluene.monobrowser.util.UrlUtils.shortUrlLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun looksLikeHostRecognizesHosts() {
        for (v in listOf("example.com", "sub.example.co.uk/path", "localhost:3000", "192.168.0.1:8080/x", "wp.pl")) {
            assertTrue(v, looksLikeHost(v))
        }
    }

    @Test
    fun looksLikeHostRejectsQueries() {
        for (v in listOf("", "   ", "hello world", "example.com is down", "kot", "a.b", "foo.")) {
            assertFalse(v, looksLikeHost(v))
        }
    }

    @Test
    fun httpAndAboutBlank() {
        assertTrue(isHttpUrl("HTTPS://a.pl"))
        assertFalse(isHttpUrl("file:///etc/passwd"))
        assertFalse(isHttpUrl("javascript:alert(1)"))
        assertTrue(isAboutBlankUrl("about:blank#x"))
        assertFalse(isAboutBlankUrl("about:config"))
    }

    @Test
    fun displayUrlStripsLoneTrailingSlashOfBareOriginOnly() {
        assertEquals("http://nonexistent.invalid", displayUrl("http://nonexistent.invalid/"))
        assertEquals("https://example.com/a/", displayUrl("https://example.com/a/"))
        assertEquals("https://example.com/?q=1", displayUrl("https://example.com/?q=1"))
    }

    @Test
    fun recoverEmbeddedUrlAndShortLabel() {
        assertEquals("https://example.com", recoverEmbeddedUrl("monobrowser://settingshttps://example.com"))
        assertEquals("http://a.pl/x?y=1", recoverEmbeddedUrl("  monobrowser://http://a.pl/x?y=1 "))
        assertNull(recoverEmbeddedUrl("monobrowser://settingsfoo"))
        assertNull(recoverEmbeddedUrl("monobrowser://settings"))
        assertTrue(isInternalSchemeInput(" MonoBrowser://x"))
        assertFalse(isInternalSchemeInput("https://monobrowser://x"))
        assertEquals("example.com", shortUrlLabel("https://www.example.com/"))
        assertEquals("example.com/a/b?c=1", shortUrlLabel("https://example.com/a/b?c=1"))
    }

    @Test
    fun hostInputToUrlOpensLocalHostsOverHttp() {
        val local = listOf(
            "localhost", "localhost:3000/api", "app.localhost:5173", "127.0.0.1:8080", "0.0.0.0:4000",
            "192.168.1.10", "10.0.0.2:3000", "172.20.0.5", "[::1]:8000/x", "raspberrypi.local",
        )
        for (v in local) {
            assertTrue(v, looksLikeHost(v))
            assertTrue(v, isLocalHost(v))
            assertEquals(v, "http://$v", hostInputToUrl(v))
        }
        for (v in listOf("example.com", "172.32.0.1", "8.8.8.8", "localhost.example.com")) {
            assertFalse(v, isLocalHost(v))
            assertEquals(v, "https://$v", hostInputToUrl(v))
        }
    }

    @Test
    fun isLoopbackHostAcceptsOnlyLoopback() {
        for (v in listOf("localhost", "localhost:5173", "app.localhost", "127.0.0.1", "127.0.0.1:8443", "[::1]", "[::1]:3000", "[0:0:0:0:0:0:0:1]")) {
            assertTrue(v, isLoopbackHost(v))
        }
        for (v in listOf("192.168.0.2", "10.0.0.1", "172.16.0.1", "0.0.0.0", "printer.local", "example.com", "localhost.evil.com", "evil-localhost", "[2001:db8::1]")) {
            assertFalse(v, isLoopbackHost(v))
        }
    }

    @Test
    fun resolveInputRoutesToUrlOrSearch() {
        assertNull(resolveInput("   "))
        assertEquals("https://a.pl/x", resolveInput(" https://a.pl/x "))
        assertEquals("about:blank", resolveInput("about:blank"))
        assertEquals("https://example.com", resolveInput("example.com"))
        assertEquals("http://localhost:3000", resolveInput("localhost:3000"))
        assertEquals("https://www.google.com/search?q=hello%20world", resolveInput("hello world"))
        assertEquals("https://www.google.com/search?q=a%26b%3Dc%2Bd", resolveInput("a&b=c+d"))
        assertEquals("https://duckduckgo.com/?q=kot", resolveInput("kot", "https://duckduckgo.com/?q={query}"))
        assertEquals("https://example.com", resolveInput("monobrowser://settingshttps://example.com"))
        assertNull(resolveInput("monobrowser://settings"))
    }

    @Test
    fun resolveInputNeverNavigatesToDangerousSchemes() {
        assertTrue(resolveInput("javascript:alert(1)")!!.startsWith("https://www.google.com/search?q="))
        assertTrue(resolveInput("file:///etc/passwd")!!.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun encodeUriComponentMatchesJavaScript() {
        assertEquals("a%20b!~'()*-_.", UrlUtils.encodeUriComponent("a b!~'()*-_."))
        assertEquals("%C5%BC%C3%B3%C5%82w", UrlUtils.encodeUriComponent("żółw"))
    }
}
