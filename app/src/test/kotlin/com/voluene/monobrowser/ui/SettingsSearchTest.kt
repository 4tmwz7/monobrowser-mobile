package com.voluene.monobrowser.ui

import com.voluene.monobrowser.ui.settings.settingMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun matching() {
        assertTrue(settingMatches("", "Anything", null))
        assertTrue(settingMatches("ciemny", "Tryb ciemny stron", "Strony, które go obsługują"))
        assertTrue(settingMatches("obsluguja", "Tryb ciemny stron", "Strony, które go obsługują"))
        assertTrue(settingMatches("TRYB stron", "Tryb ciemny stron", null))
        assertFalse(settingMatches("reklamy", "Tryb ciemny stron", "Strony"))
    }
}
