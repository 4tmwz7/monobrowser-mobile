package com.voluene.monobrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BarLayoutTest {
    @Test fun defaultIsTheOldFixedRow() {
        assertEquals(
            listOf(BarItem.BACK, BarItem.FORWARD, BarItem.ADDRESS, BarItem.RELOAD, BarItem.MENU, BarItem.TABS),
            BarLayout.sanitize(BarLayout.DEFAULT),
        )
        assertEquals(BarLayout.DEFAULT, BarLayout.parse(AppSettings().barItems))
        assertEquals(BarLayout.DEFAULT, AppSettings().barLayout)
    }

    @Test fun duplicatesAndUnknownNamesAreDropped() {
        val parsed = BarLayout.parse(listOf("BACK", "FUTURE", "BACK", "ADDRESS", "ADDRESS", "MENU", ""))
        assertEquals(listOf(BarItem.BACK, BarItem.ADDRESS, BarItem.MENU), parsed)
    }

    @Test fun addressAndMenuAreAlwaysThere() {
        assertEquals(listOf(BarItem.BACK, BarItem.FORWARD, BarItem.ADDRESS, BarItem.TABS, BarItem.MENU), BarLayout.sanitize(listOf(BarItem.BACK, BarItem.FORWARD, BarItem.TABS)))
        val empty = BarLayout.sanitize(emptyList())
        assertEquals(listOf(BarItem.ADDRESS, BarItem.MENU), empty)
        assertEquals(listOf(BarItem.ADDRESS, BarItem.MENU), BarLayout.parse(listOf("NOPE")))
        assertEquals(1, BarLayout.sanitize(listOf(BarItem.ADDRESS, BarItem.BACK, BarItem.ADDRESS, BarItem.MENU)).count { it == BarItem.ADDRESS })
    }

    @Test fun buttonCountIsLimitedAndMandatoryItemsSurvive() {
        val all = BarItem.entries.filter { it != BarItem.ADDRESS && it != BarItem.MENU } + listOf(BarItem.ADDRESS, BarItem.MENU)
        val result = BarLayout.sanitize(all)
        assertEquals(BarLayout.MAX_BUTTONS, result.count { it != BarItem.ADDRESS })
        assertTrue(BarItem.ADDRESS in result)
        assertTrue(BarItem.MENU in result)
        // Surplus optional items come off the end of the row.
        assertEquals(BarItem.BACK, result.first())
    }

    @Test fun removeKeepsMandatoryItems() {
        assertSame(BarLayout.DEFAULT, BarLayout.remove(BarLayout.DEFAULT, BarItem.ADDRESS))
        assertSame(BarLayout.DEFAULT, BarLayout.remove(BarLayout.DEFAULT, BarItem.MENU))
        assertFalse(BarItem.TABS in BarLayout.remove(BarLayout.DEFAULT, BarItem.TABS))
    }

    @Test fun addRespectsTheLimit() {
        val full = BarLayout.DEFAULT // 5 buttons + address
        val seven = BarLayout.add(full, BarItem.SHARE)
        assertTrue(BarItem.SHARE in seven)
        assertFalse(BarLayout.canAdd(seven))
        assertSame(seven, BarLayout.add(seven, BarItem.NEW_TAB))
        assertSame(full, BarLayout.add(full, BarItem.BACK))
        assertEquals(listOf(BarItem.NEW_TAB, BarItem.BOOKMARKS, BarItem.BOOKMARK, BarItem.SHARE), BarLayout.available(BarLayout.DEFAULT))
    }

    @Test fun moveClampsToTheRow() {
        val moved = BarLayout.move(BarLayout.DEFAULT, 0, 1)
        assertEquals(listOf(BarItem.FORWARD, BarItem.BACK, BarItem.ADDRESS, BarItem.RELOAD, BarItem.MENU, BarItem.TABS), moved)
        assertSame(BarLayout.DEFAULT, BarLayout.move(BarLayout.DEFAULT, 0, -1))
        assertSame(BarLayout.DEFAULT, BarLayout.move(BarLayout.DEFAULT, 5, 3)) // already last
        assertEquals(BarItem.TABS, BarLayout.move(BarLayout.DEFAULT, 5, -9).first())
        assertSame(BarLayout.DEFAULT, BarLayout.move(BarLayout.DEFAULT, 99, 1))
    }

    @Test fun settingsSanitizeAndOldFilesLoad() {
        val old = SettingsCodec.decode("""{"toolbarPosition":"TOP"}""")!!
        assertEquals(BarLayout.DEFAULT, old.barLayout)
        val broken = SettingsCodec.decode("""{"barItems":["SHARE","BOGUS","SHARE"]}""")!!
        assertTrue(BarItem.ADDRESS in broken.barLayout && BarItem.MENU in broken.barLayout)
        assertEquals(broken.barLayout, SettingsCodec.decode(SettingsCodec.encode(broken))!!.barLayout)
    }
}
