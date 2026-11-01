package com.voluene.monobrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    @Test fun presetsMatchDesktopList() {
        assertEquals(14, Themes.PRESETS.size)
        assertEquals(Themes.PRESETS.size, Themes.PRESETS.map { it.id }.toSet().size)
        assertNotNull(Themes.find("rose-pine-dawn"))
        assertTrue(Themes.isKnown("default"))
        assertFalse(Themes.isKnown("custom"))
    }

    @Test fun schemeFollowsLuminance() {
        assertTrue(Themes.find("dracula")!!.tokens.dark)
        assertFalse(Themes.find("solarized-light")!!.tokens.dark)
        assertFalse(Themes.find("gruvbox-light")!!.tokens.dark)
    }

    @Test fun mutedTextIsReadableOnEverySurface() {
        for (p in Themes.PRESETS) {
            val t = p.tokens
            for (surface in listOf(t.bg, t.surface, t.surface2)) {
                assertTrue("${p.id} muted on $surface", Themes.contrastRatio(t.muted, surface) >= 4.5)
            }
        }
    }

    @Test fun colorMath() {
        assertEquals("#808080", Themes.mixHex("#000000", "#ffffff", 0.5))
        assertEquals(21.0, Themes.contrastRatio("#000000", "#ffffff"), 0.01)
        assertEquals("#0a0a0a", Themes.pickOnAccent("#ffffff", "#ffffff", "#ffffff"))
    }

    @Test fun defaultThemeIsStrictMonochromeAndFollowsMode() {
        val light = Themes.resolve("default", ThemeMode.LIGHT, systemDark = true)
        val dark = Themes.resolve("default", ThemeMode.DARK, systemDark = false)
        assertFalse(light.dark)
        assertTrue(dark.dark)
        assertEquals("#ffffff", light.background)
        assertEquals("#000000", dark.background)
        assertTrue(Themes.resolve("default", ThemeMode.SYSTEM, systemDark = true).dark)
        assertFalse(Themes.resolve("default", ThemeMode.SYSTEM, systemDark = false).dark)
        // Strictly grey: r == g == b for every default colour.
        for (hex in listOf(light.background, light.onBackground, light.surfaceVariant, light.outline, dark.surfaceVariant, dark.outlineVariant)) {
            assertEquals(hex, hex.substring(0, 3) + hex.substring(1, 3) + hex.substring(1, 3))
        }
    }

    @Test fun presetsIgnoreModeAndMapTokens() {
        val nord = Themes.find("nord")!!.tokens
        for (mode in ThemeMode.entries) {
            val r = Themes.resolve("nord", mode, systemDark = false)
            assertTrue(r.dark)
            assertEquals(nord.bg, r.background)
            assertEquals(nord.fg, r.onBackground)
            assertEquals(nord.accent, r.primary)
        }
    }
}
