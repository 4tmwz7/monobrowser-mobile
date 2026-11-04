package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingIntentTest {
    @Test fun view() {
        assertEquals(IncomingRequest.Open("https://a.com/x"), IncomingIntent.parse("android.intent.action.VIEW", "https://a.com/x", null))
        assertEquals(IncomingRequest.None, IncomingIntent.parse("android.intent.action.VIEW", "file:///sdcard/a.html", null))
        assertEquals(IncomingRequest.None, IncomingIntent.parse("android.intent.action.VIEW", null, null))
    }

    @Test fun sharedTextWithALinkOpensTheLink() {
        assertEquals(
            IncomingRequest.Open("https://example.com/article?id=1"),
            IncomingIntent.parse("android.intent.action.SEND", null, "Look at this (https://example.com/article?id=1). Cool!"),
        )
    }

    @Test fun plainTextIsSearched() {
        assertEquals(IncomingRequest.Open("pierogi ruskie przepis"), IncomingIntent.parse("android.intent.action.PROCESS_TEXT", null, "  pierogi\nruskie   przepis "))
        assertEquals(IncomingRequest.Open("kotlin"), IncomingIntent.parse("android.intent.action.WEB_SEARCH", null, "kotlin"))
        assertEquals(IncomingRequest.None, IncomingIntent.parse("android.intent.action.SEND", null, "   "))
    }

    @Test fun shortcuts() {
        assertEquals(IncomingRequest.NewTab(false), IncomingIntent.parse(IncomingIntent.ACTION_NEW_TAB, null, null))
        assertEquals(IncomingRequest.NewTab(true), IncomingIntent.parse(IncomingIntent.ACTION_NEW_PRIVATE_TAB, null, null))
        assertEquals(IncomingRequest.None, IncomingIntent.parse("android.intent.action.MAIN", null, null))
    }

    @Test fun widgetSearch() {
        assertEquals(IncomingRequest.NewTab(isPrivate = false, focusAddress = true), IncomingIntent.parse(IncomingIntent.ACTION_SEARCH, null, null))
        assertEquals(IncomingRequest.NewTab(isPrivate = true, focusAddress = true), IncomingIntent.parse(IncomingIntent.ACTION_SEARCH_PRIVATE, null, null))
    }
}
