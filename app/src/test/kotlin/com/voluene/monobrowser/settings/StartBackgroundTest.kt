package com.voluene.monobrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBackgroundTest {
    @Test fun urlMustBeHttpWithoutSpaces() {
        assertTrue(StartBackground.isValidUrl("https://example.com/a.jpg"))
        assertTrue(StartBackground.isValidUrl("  http://example.com/a.png "))
        assertFalse(StartBackground.isValidUrl("ftp://example.com/a.jpg"))
        assertFalse(StartBackground.isValidUrl("file:///sdcard/a.jpg"))
        assertFalse(StartBackground.isValidUrl("example.com/a.jpg"))
        assertFalse(StartBackground.isValidUrl("https://example.com/a b.jpg"))
        assertFalse(StartBackground.isValidUrl(""))
        assertFalse(StartBackground.isValidUrl("https://example.com/" + "a".repeat(2100)))
    }

    @Test fun targetSizeShrinksTheLongerSideOnly() {
        assertEquals(1000 to 500, StartBackground.targetSize(1000, 500))
        assertEquals(2048 to 1152, StartBackground.targetSize(4096, 2304))
        assertEquals(1152 to 2048, StartBackground.targetSize(2304, 4096))
        assertEquals(2048 to 1, StartBackground.targetSize(8192, 4))
        assertEquals(0 to 0, StartBackground.targetSize(0, 100))
    }

    @Test fun sampleSizeIsAPowerOfTwoThatKeepsEnoughPixels() {
        assertEquals(1, StartBackground.sampleSize(1000, 800))
        assertEquals(1, StartBackground.sampleSize(3000, 2000))
        assertEquals(2, StartBackground.sampleSize(4096, 3000))
        assertEquals(4, StartBackground.sampleSize(8192, 6000))
        assertEquals(8, StartBackground.sampleSize(16384, 100))
        assertEquals(1, StartBackground.sampleSize(0, 0))
        // The decoded long side is never below the target when the source is bigger.
        for (side in listOf(2049, 4095, 4096, 5000, 12000, 20000)) {
            assertTrue(side / StartBackground.sampleSize(side, 10) >= StartBackground.MAX_SIDE)
        }
    }

    @Test fun overlayLevelsAreClamped() {
        assertEquals(0.35f, StartBackground.overlayAlpha(-3), 0f)
        assertEquals(0.8f, StartBackground.overlayAlpha(99), 0f)
        assertEquals(StartBackground.DEFAULT_DIM, StartBackground.clampDim(StartBackground.DEFAULT_DIM))
        assertTrue(StartBackground.DIM_LEVELS.zipWithNext().all { it.first < it.second })
    }

    @Test fun settingsSanitizeAndOldFilesLoad() {
        val old = SettingsCodec.decode("""{"themeMode":"DARK"}""")!!
        assertEquals(0L, old.startBackground)
        assertTrue(old.startBackgroundGrayscale)
        assertTrue(old.showFavicons)
        assertTrue(old.faviconsGrayscale)
        assertTrue(old.showStartBookmarks)
        val odd = SettingsCodec.sanitize(AppSettings(startBackground = -5, startBackgroundDim = 42))
        assertEquals(0L, odd.startBackground)
        assertEquals(StartBackground.DIM_LEVELS.lastIndex, odd.startBackgroundDim)
    }
}
