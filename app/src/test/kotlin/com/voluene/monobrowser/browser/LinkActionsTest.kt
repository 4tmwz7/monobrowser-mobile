package com.voluene.monobrowser.browser

import com.voluene.monobrowser.settings.LinkLongPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkActionsTest {
    @Test fun webLinks() {
        assertTrue(LinkActions.isWebLink("https://example.com/a"))
        assertTrue(LinkActions.isWebLink(" http://example.com "))
        assertFalse(LinkActions.isWebLink("mailto:a@b.c"))
        assertFalse(LinkActions.isWebLink("javascript:alert(1)"))
        assertFalse(LinkActions.isWebLink(null))
    }

    @Test fun longPressFollowsTheSettingForWebLinks() {
        assertEquals(LongPressOutcome.GLANCE, LinkActions.onLongPress("https://a.example/", LinkLongPress.GLANCE))
        assertEquals(LongPressOutcome.MENU, LinkActions.onLongPress("https://a.example/", LinkLongPress.MENU))
    }

    @Test fun nonWebLinksAlwaysGetTheMenuAndBlankLinksNothing() {
        assertEquals(LongPressOutcome.MENU, LinkActions.onLongPress("mailto:a@b.c", LinkLongPress.GLANCE))
        assertEquals(LongPressOutcome.MENU, LinkActions.onLongPress("tel:123", LinkLongPress.GLANCE))
        assertEquals(LongPressOutcome.NONE, LinkActions.onLongPress("", LinkLongPress.GLANCE))
        assertEquals(LongPressOutcome.NONE, LinkActions.onLongPress(null, LinkLongPress.MENU))
        assertEquals(LongPressOutcome.NONE, LinkActions.onLongPress("   ", LinkLongPress.GLANCE))
    }

    @Test fun menuForWebLink() {
        assertEquals(
            listOf(LinkMenuAction.OPEN_TAB, LinkMenuAction.OPEN_BACKGROUND, LinkMenuAction.OPEN_PRIVATE, LinkMenuAction.GLANCE, LinkMenuAction.COPY, LinkMenuAction.SHARE),
            LinkActions.menu("https://a.example/", sourceIsPrivate = false),
        )
    }

    @Test fun privateSourceHasNoPrivateEntry() {
        val menu = LinkActions.menu("https://a.example/", sourceIsPrivate = true)
        assertFalse(LinkMenuAction.OPEN_PRIVATE in menu)
        assertTrue(LinkMenuAction.OPEN_TAB in menu)
    }

    @Test fun nonWebLinkMenuIsCopyAndShareOnly() {
        assertEquals(listOf(LinkMenuAction.COPY, LinkMenuAction.SHARE), LinkActions.menu("mailto:a@b.c", false))
    }

    @Test fun privateSourceAlwaysOpensPrivateTabs() {
        assertTrue(LinkActions.opensPrivate(sourceIsPrivate = true, explicitPrivate = false))
        assertTrue(LinkActions.opensPrivate(sourceIsPrivate = false, explicitPrivate = true))
        assertFalse(LinkActions.opensPrivate(sourceIsPrivate = false, explicitPrivate = false))
    }
}
