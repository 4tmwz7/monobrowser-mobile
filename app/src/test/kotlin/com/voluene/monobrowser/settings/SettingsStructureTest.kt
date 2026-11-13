package com.voluene.monobrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStructureTest {
    @Test fun everyItemIsInExactlyOneCategory() {
        val all = SettingsStructure.categories.flatMap(SettingsStructure::items)
        assertEquals(all.size, all.toSet().size) // no duplicates
        assertEquals(SettingsItem.entries.toSet(), all.toSet()) // nothing missing
    }

    @Test fun everyCategoryIsListedOnceAndHasSeveralItems() {
        assertEquals(SettingsCategory.entries, SettingsStructure.categories)
        SettingsCategory.entries.forEach { c ->
            assertTrue("$c has too few items", SettingsStructure.items(c).size >= 2)
        }
    }

    @Test fun categoryOfAgreesWithItems() {
        SettingsCategory.entries.forEach { c ->
            SettingsStructure.items(c).forEach { assertEquals(c, SettingsStructure.categoryOf(it)) }
        }
    }

    @Test fun settingsSitWhereOneWouldLookForThem() {
        assertEquals(SettingsCategory.GENERAL, SettingsStructure.categoryOf(SettingsItem.SEARCH_ENGINE))
        assertEquals(SettingsCategory.START_PAGE, SettingsStructure.categoryOf(SettingsItem.START_BACKGROUND))
        assertEquals(SettingsCategory.APPEARANCE, SettingsStructure.categoryOf(SettingsItem.BAR_LAYOUT))
        assertEquals(SettingsCategory.PAGES, SettingsStructure.categoryOf(SettingsItem.SITE_THEMES))
        assertEquals(SettingsCategory.PRIVACY, SettingsStructure.categoryOf(SettingsItem.ADBLOCK))
        assertEquals(SettingsCategory.PRIVACY, SettingsStructure.categoryOf(SettingsItem.ADBLOCK_WIFI_ONLY))
        assertEquals(SettingsCategory.ABOUT, SettingsStructure.categoryOf(SettingsItem.UPDATE_CHECK_NOW))
    }
}
