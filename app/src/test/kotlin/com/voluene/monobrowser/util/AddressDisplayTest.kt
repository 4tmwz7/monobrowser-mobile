package com.voluene.monobrowser.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressDisplayTest {
    @Test fun webPages() {
        assertEquals(AddressDisplay("example.com", "/a/b?c=1#d", true), AddressDisplay.of("https://www.Example.com/a/b?c=1#d"))
        assertEquals(AddressDisplay("example.com", "", true), AddressDisplay.of("https://example.com/"))
        assertEquals(AddressDisplay("example.com:8080", "/x", false), AddressDisplay.of("http://user:pw@example.com:8080/x"))
        assertEquals(AddressDisplay("pl.wikipedia.org", "/wiki/Łódź", true), AddressDisplay.of("https://pl.wikipedia.org/wiki/Łódź"))
        assertEquals(AddressDisplay("example.com", "", true), AddressDisplay.of("https://example.com:443"))
    }

    @Test fun otherPages() {
        assertEquals(AddressDisplay("about:blank", "", null), AddressDisplay.of("about:blank"))
        assertEquals(AddressDisplay("", "", null), AddressDisplay.of(""))
    }
}
