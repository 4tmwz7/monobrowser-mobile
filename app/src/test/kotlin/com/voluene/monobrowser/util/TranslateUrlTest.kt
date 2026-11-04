package com.voluene.monobrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslateUrlTest {
    @Test fun buildsProxyUrl() {
        assertEquals(
            "https://translate.google.com/translate?sl=auto&tl=pl&u=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2",
            TranslateUrl.of("https://example.com/a?b=1&c=2", "PL"),
        )
        assertEquals("https://translate.google.com/translate?sl=auto&tl=en&u=https%3A%2F%2Fa.com", TranslateUrl.of("https://a.com", "??"))
    }
}
