package com.voluene.monobrowser.ui

import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.settings.SettingsCodec
import com.voluene.monobrowser.ui.browser.textZoomStep
import com.voluene.monobrowser.ui.start.monogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextZoomStepTest {
    @Test fun stepsUpAndDown() {
        assertEquals(115, textZoomStep(100, up = true))
        assertEquals(85, textZoomStep(100, up = false))
        // Between steps (a value from an older version or another device).
        assertEquals(115, textZoomStep(110, up = true))
        assertEquals(100, textZoomStep(110, up = false))
        // Clamped at the ends.
        assertEquals(200, textZoomStep(200, up = true))
        assertEquals(75, textZoomStep(75, up = false))
    }

    @Test fun settingsAreSanitized() {
        val s = SettingsCodec.sanitize(
            AppSettings(textZoom = 5000, readerFontSize = 2, hiddenTopSites = listOf(" A.com ", "a.com", "", "b.com")),
        )
        assertEquals(300, s.textZoom)
        assertEquals(AppSettings.READER_FONT_RANGE.first, s.readerFontSize)
        assertEquals(listOf("a.com", "b.com"), s.hiddenTopSites)
    }

    @Test fun newFieldsSurviveRoundTripAndOldFilesGetDefaults() {
        val s = AppSettings(textZoom = 130, showTopSites = false, newTabFocusAddress = true, readerSerif = false)
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        val old = SettingsCodec.decode("""{"version":1,"searchEngine":"DUCKDUCKGO"}""")!!
        assertEquals(100, old.textZoom)
        assertTrue(old.showTopSites)
        assertFalse(old.newTabFocusAddress)
    }

    @Test fun monograms() {
        assertEquals("G", monogram("github"))
        assertEquals("MS", monogram("my-site"))
        assertEquals("?", monogram(""))
    }

    @Test fun pinnedSitesAreSanitized() {
        val pins = listOf(
            com.voluene.monobrowser.settings.PinnedSite("https://a.com/", "A"),
            com.voluene.monobrowser.settings.PinnedSite("https://a.com/", "dup"),
            com.voluene.monobrowser.settings.PinnedSite("javascript:alert(1)", "bad"),
        ) + List(20) { com.voluene.monobrowser.settings.PinnedSite("https://s$it.com/", "S") }
        val s = SettingsCodec.sanitize(AppSettings(pinnedSites = pins))
        assertEquals(AppSettings.MAX_PINNED, s.pinnedSites.size)
        assertEquals("A", s.pinnedSites.first().title)
        assertTrue(s.pinnedSites.none { it.url.startsWith("javascript") })
    }
}
