package com.voluene.monobrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmeticScriptTest {
    @Test fun jsStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\"", CosmeticScript.jsString("a\"b\\c\n"))
        assertEquals("\"\\u003c/style>\"", CosmeticScript.jsString("</style>"))
        assertEquals("\"\\u2028\"", CosmeticScript.jsString("\u2028"))
        assertEquals("\"\\u0001\"", CosmeticScript.jsString("\u0001"))
    }

    @Test fun injectEmbedsEscapedCss() {
        val js = CosmeticScript.inject(".ad{display:none!important}\n")
        assertTrue(js.contains("\".ad{display:none!important}\\n\""))
        assertTrue(js.contains("adoptedStyleSheets"))
    }

    @Test fun documentStartUsesBridge() {
        val js = CosmeticScript.DOCUMENT_START
        assertTrue(js.contains("window.MonoAdblock"))
        assertTrue(js.contains("cosmeticCss(href)"))
        assertTrue(js.contains("genericCss(href"))
        assertFalse(js.contains("\$BRIDGE_NAME"))
    }
}
