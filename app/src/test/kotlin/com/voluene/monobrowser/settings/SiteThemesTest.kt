package com.voluene.monobrowser.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteThemesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun resolverPrefersOverrideThenGlobalMode() {
        assertTrue(PageThemes.isDark(ThemeMode.LIGHT, systemDark = false, override = SiteTheme.DARK))
        assertFalse(PageThemes.isDark(ThemeMode.DARK, systemDark = true, override = SiteTheme.LIGHT))
        assertTrue(PageThemes.isDark(ThemeMode.SYSTEM, systemDark = true, override = null))
        assertFalse(PageThemes.isDark(ThemeMode.SYSTEM, systemDark = false, override = null))
        assertTrue(PageThemes.isDark(ThemeMode.DARK, systemDark = false, override = null))
        assertFalse(PageThemes.isDark(ThemeMode.LIGHT, systemDark = true, override = null))
    }

    @Test fun subdomainsInheritAndMostSpecificWins() {
        val t = SiteThemes(null)
        assertTrue(t.set("https://www.example.com/page", SiteTheme.DARK))
        assertEquals(SiteTheme.DARK, t.lookup("example.com"))
        assertEquals(SiteTheme.DARK, t.lookup("https://a.b.example.com/x"))
        assertNull(t.lookup("notexample.com"))
        assertNull(t.lookup("com"))
        assertNull(t.lookup(null))
        t.set("news.example.com", SiteTheme.LIGHT)
        assertEquals(SiteTheme.LIGHT, t.lookup("x.news.example.com"))
        assertEquals(SiteTheme.DARK, t.lookup("example.com"))
    }

    @Test fun nullRemovesTheEntryThatApplies() {
        val t = SiteThemes(null)
        t.set("example.com", SiteTheme.DARK)
        t.set("news.example.com", null) // inherited from the parent: the parent entry goes
        assertTrue(t.state.value.isEmpty())
        assertTrue(t.set("other.org", null)) // nothing to remove is not an error
        assertFalse(t.set("not a host", SiteTheme.DARK))
    }

    @Test fun persistsAsJsonAndReloads() {
        val file = File(tmp.root, "sub/site-themes.json")
        val t = SiteThemes(file)
        t.set("b.org", SiteTheme.LIGHT); t.set("a.com", SiteTheme.DARK)
        assertEquals("""{"sites":{"a.com":"dark","b.org":"light"}}""", file.readText())
        val reloaded = SiteThemes(file)
        assertEquals(SiteTheme.DARK, reloaded.lookup("x.a.com"))
        assertEquals(listOf("a.com", "b.org"), reloaded.state.value.keys.toList())
        reloaded.set("a.com", null)
        assertEquals("""{"sites":{"b.org":"light"}}""", file.readText())
    }

    @Test fun parseIsLenient() {
        val parsed = SiteThemes.parse("""{"sites":{"B.org":"LIGHT","a.com":"dark","bad host":"dark","c.com":"purple","d.com":5}}""")
        assertEquals(mapOf("a.com" to SiteTheme.DARK, "b.org" to SiteTheme.LIGHT), parsed)
        assertTrue(SiteThemes.parse("garbage").isEmpty())
        assertTrue(SiteThemes.parse("""{"other":1}""").isEmpty())
        assertTrue(SiteThemes(File(tmp.root, "missing.json")).state.value.isEmpty())
    }

    @Test fun privateChangesStayInMemory() {
        val file = File(tmp.root, "site-themes.json")
        val t = SiteThemes(file)
        t.set("saved.com", SiteTheme.DARK)
        val onDisk = file.readText()
        t.set("secret.org", SiteTheme.DARK, forPrivate = true)
        t.set("saved.com", SiteTheme.LIGHT, forPrivate = true)
        assertEquals(onDisk, file.readText())
        assertEquals(SiteTheme.DARK, t.lookup("secret.org", forPrivate = true))
        assertNull(t.lookup("secret.org"))
        assertEquals(SiteTheme.LIGHT, t.lookup("saved.com", forPrivate = true))
        assertEquals(SiteTheme.DARK, t.lookup("saved.com"))
        assertFalse("secret.org" in t.state.value)
        t.set("saved.com", null, forPrivate = true) // hides the stored entry for private tabs only
        assertNull(t.lookup("saved.com", forPrivate = true))
        assertEquals(SiteTheme.DARK, t.lookup("saved.com"))
        t.clearPrivate()
        assertEquals(SiteTheme.DARK, t.lookup("saved.com", forPrivate = true))
        assertNull(t.lookup("secret.org", forPrivate = true))
    }
}
