package com.voluene.monobrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLayoutTest {
    @Test fun defaultShowsEverythingInDeclarationOrder() {
        assertEquals(MenuId.entries.toList(), MenuLayout.visible(emptyList(), emptyList()))
    }

    @Test fun settingsStaysInTheFirstSevenEvenWithTheVideoTile() {
        val order = MenuLayout.visible(emptyList(), emptyList())
        assertTrue(order.take(7).contains(MenuId.SETTINGS))
        assertTrue(order.filter { it != MenuId.PICTURE_IN_PICTURE }.take(7).contains(MenuId.SETTINGS))
    }

    @Test fun unknownAndDuplicateIdsAreIgnoredAndMissingOnesAppended() {
        val order = MenuLayout.order(listOf("SHARE", "FROM_THE_FUTURE", "SHARE", "NEW_TAB"))
        assertEquals(listOf(MenuId.SHARE, MenuId.NEW_TAB), order.take(2))
        assertEquals(MenuId.entries.size, order.size)
        assertEquals(MenuId.entries.size, order.toSet().size)
        // An entry that an old stored order does not know comes last, in default order.
        val old = MenuLayout.order(MenuId.entries.filter { it != MenuId.PAGE_THEME }.map { it.name })
        assertEquals(MenuId.PAGE_THEME, old.last())
    }

    @Test fun settingsCannotBeHidden() {
        assertTrue(MenuId.SETTINGS in MenuLayout.visible(emptyList(), listOf("SETTINGS", "FIND")))
        assertFalse(MenuId.FIND in MenuLayout.visible(emptyList(), listOf("SETTINGS", "FIND")))
        assertEquals(emptyList<String>(), MenuLayout.sanitizeHidden(listOf("SETTINGS", "NOPE")))
        assertEquals(listOf("FIND"), MenuLayout.toggle(listOf("SETTINGS"), MenuId.FIND))
        assertEquals(emptyList<String>(), MenuLayout.toggle(listOf("FIND"), MenuId.FIND))
        assertEquals(emptyList<String>(), MenuLayout.toggle(emptyList(), MenuId.SETTINGS))
    }

    @Test fun moveWorksOnTheFullOrder() {
        val moved = MenuLayout.move(emptyList(), MenuId.SETTINGS, -2)
        val order = MenuLayout.order(moved)
        assertEquals(MenuId.entries.indexOf(MenuId.SETTINGS) - 2, order.indexOf(MenuId.SETTINGS))
        assertEquals(MenuId.NEW_TAB.name, MenuLayout.move(emptyList(), MenuId.NEW_TAB, -5).first())
        assertEquals(MenuId.NEW_TAB.name, MenuLayout.move(emptyList(), MenuId.NEW_TAB, -1).first())
        assertEquals(MenuId.PAGE_THEME.name, MenuLayout.move(emptyList(), MenuId.PAGE_THEME, 5).last())
    }

    @Test fun settingsCodecKeepsTheFields() {
        val s = SettingsCodec.sanitize(AppSettings(menuOrder = listOf("FIND", "BOGUS"), menuHidden = listOf("SHARE", "SETTINGS")))
        assertEquals(MenuId.FIND.name, s.menuOrder.first())
        assertEquals(listOf("SHARE"), s.menuHidden)
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals(emptyList<String>(), SettingsCodec.decode("{}")!!.menuOrder)
    }
}
