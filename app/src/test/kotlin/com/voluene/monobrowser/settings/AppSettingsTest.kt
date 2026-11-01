package com.voluene.monobrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun defaults() {
        val s = AppSettings()
        assertEquals(ToolbarPosition.BOTTOM, s.toolbarPosition)
        assertEquals(SearchEngine.GOOGLE, s.searchEngine)
        assertTrue(s.adblockEnabled)
        assertFalse(s.wifiOnlyListUpdates)
        assertFalse(s.onboardingDone)
        assertEquals("https://www.google.com/search?q={query}", s.searchTemplate)
        assertNull(s.newTabUrl)
    }

    @Test fun roundTrip() {
        val s = AppSettings(
            searchEngine = SearchEngine.CUSTOM, customSearchUrl = "https://search.example/?q={query}",
            startupMode = StartupMode.NEWTAB, newTabPage = NewTabPage.HOMEPAGE, homepageUrl = "https://home.example/",
            language = AppLanguage.PL, toolbarPosition = ToolbarPosition.TOP, themeId = "nord", themeMode = ThemeMode.DARK,
            adblockEnabled = false, wifiOnlyListUpdates = true, onboardingDone = true,
        )
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals("https://search.example/?q={query}", s.searchTemplate)
        assertEquals("https://home.example/", s.newTabUrl)
    }

    @Test fun duckDuckGoTemplate() {
        assertEquals("https://duckduckgo.com/?q={query}", AppSettings(searchEngine = SearchEngine.DUCKDUCKGO).searchTemplate)
    }

    @Test fun invalidJsonIsNull_unknownValuesFallBackToDefaults() {
        assertNull(SettingsCodec.decode("{oops"))
        val s = SettingsCodec.decode("""{"toolbarPosition":"LEFT","searchEngine":"BING","themeId":"nope","futureKey":1,"adblockEnabled":false}""")!!
        assertEquals(ToolbarPosition.BOTTOM, s.toolbarPosition)
        assertEquals(SearchEngine.GOOGLE, s.searchEngine)
        assertEquals(Themes.DEFAULT_ID, s.themeId)
        assertFalse(s.adblockEnabled)
    }

    @Test fun customEngineNeedsValidTemplate() {
        assertTrue(SettingsCodec.isValidSearchUrl("https://a.example/s?q={query}"))
        assertFalse(SettingsCodec.isValidSearchUrl("https://a.example/s?q="))
        assertFalse(SettingsCodec.isValidSearchUrl("ftp://a.example/{query}"))
        assertFalse(SettingsCodec.isValidSearchUrl("https://a.example/ {query}"))
        // Custom engine with an invalid address degrades to Google instead of keeping a broken template.
        val s = SettingsCodec.sanitize(AppSettings(searchEngine = SearchEngine.CUSTOM, customSearchUrl = "nonsense"))
        assertEquals(SearchEngine.GOOGLE, s.searchEngine)
    }

    @Test fun homepageValidation() {
        assertTrue(SettingsCodec.isValidHomepage("https://a.example"))
        assertFalse(SettingsCodec.isValidHomepage("javascript:alert(1)"))
        val s = SettingsCodec.sanitize(AppSettings(newTabPage = NewTabPage.HOMEPAGE, homepageUrl = "x"))
        assertEquals(NewTabPage.BLANK, s.newTabPage)
        assertEquals("", s.homepageUrl)
    }

    @Test fun linkLongPressDefaultsToGlanceAndRoundTrips() {
        assertEquals(LinkLongPress.GLANCE, AppSettings().linkLongPress)
        val s = AppSettings(linkLongPress = LinkLongPress.MENU)
        assertEquals(LinkLongPress.MENU, SettingsCodec.decode(SettingsCodec.encode(s))!!.linkLongPress)
    }

    @Test fun linkLongPressMissingOrUnknownFallsBackToGlance() {
        assertEquals(LinkLongPress.GLANCE, SettingsCodec.decode("""{"adblockEnabled":false}""")!!.linkLongPress) // settings from before phase 4
        assertEquals(LinkLongPress.GLANCE, SettingsCodec.decode("""{"linkLongPress":"EXPLODE"}""")!!.linkLongPress)
    }
}
