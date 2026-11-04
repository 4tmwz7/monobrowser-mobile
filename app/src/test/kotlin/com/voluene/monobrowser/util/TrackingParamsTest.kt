package com.voluene.monobrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TrackingParamsTest {
    @Test fun removesTrackers() {
        assertEquals("https://a.com/x", TrackingParams.clean("https://a.com/x?utm_source=fb&utm_medium=social&fbclid=abc"))
        assertEquals("https://a.com/x?id=5&page=2", TrackingParams.clean("https://a.com/x?id=5&gclid=1&page=2&UTM_Campaign=z"))
        assertEquals("https://a.com/x?id=5#part", TrackingParams.clean("https://a.com/x?id=5&utm_source=n#part"))
        assertEquals("https://a.com/x#part", TrackingParams.clean("https://a.com/x?msclkid=1#part"))
    }

    @Test fun hostSpecific() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", TrackingParams.clean("https://youtu.be/dQw4w9WgXcQ?si=XYZ&t=42"))
        assertEquals("https://www.youtube.com/watch?v=abc", TrackingParams.clean("https://www.youtube.com/watch?v=abc&si=1&pp=2"))
        assertEquals("https://open.spotify.com/track/1", TrackingParams.clean("https://open.spotify.com/track/1?si=abc"))
        // `si`, `s`, `t` mean something else on other sites.
        val other = "https://example.com/search?si=1&s=term&t=3"
        assertSame(other, TrackingParams.clean(other))
    }

    @Test fun leavesOtherUrlsAlone() {
        for (url in listOf("https://a.com/", "https://a.com/x?q=kotlin", "about:blank", "mailto:a@b.c?subject=utm_source", "https://a.com/x#utm_source=1")) {
            assertSame(url, TrackingParams.clean(url))
        }
    }
}
