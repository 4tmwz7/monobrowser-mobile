package com.voluene.monobrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataUrlTest {
    @Test fun parsesBase64() {
        val d = DataUrl.parse("data:text/plain;base64,SGVsbG8gKyB3b3JsZA==")!!
        assertEquals("text/plain", d.mimeType)
        assertEquals("Hello + world", String(d.bytes))
    }

    @Test fun parsesPercentEncodedAndKeepsLiteralPlus() {
        val d = DataUrl.parse("data:text/csv;charset=utf-8,a%2Cb%0A1+2")!!
        assertEquals("text/csv", d.mimeType)
        assertEquals("a,b\n1+2", String(d.bytes))
    }

    @Test fun defaultsToTextPlain() {
        assertEquals("text/plain", DataUrl.parse("data:,hi")!!.mimeType)
    }

    @Test fun rejectsMalformedInput() {
        assertNull(DataUrl.parse("https://example.com"))
        assertNull(DataUrl.parse("data:text/plain;base64"))
        assertNull(DataUrl.parse("data:text/plain;base64,%%%"))
    }
}
