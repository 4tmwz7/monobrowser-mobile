package com.voluene.monobrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceTypesTest {
    private fun guess(path: String?, vararg headers: Pair<String, String>, main: Boolean = false) =
        ResourceTypes.guess(main, path, headers.toMap())

    @Test fun mainFrameIsDocument() = assertEquals(ResourceType.DOCUMENT, guess("/x.js", main = true))

    @Test fun byExtension() {
        assertEquals(ResourceType.SCRIPT, guess("/a/b.js"))
        assertEquals(ResourceType.SCRIPT, guess("/a/b.MJS"))
        assertEquals(ResourceType.STYLESHEET, guess("/s.css"))
        assertEquals(ResourceType.FONT, guess("/f.woff2"))
        assertEquals(ResourceType.MEDIA, guess("/v.mp4"))
        assertEquals(ResourceType.IMAGE, guess("/i.jpeg"))
        assertEquals(ResourceType.IMAGE, guess("/i.svg"))
    }

    @Test fun byAcceptHeader() {
        assertEquals(ResourceType.IMAGE, guess("/pixel", "Accept" to "image/avif,image/webp,*/*"))
        assertEquals(ResourceType.STYLESHEET, guess("/x", "accept" to "text/css,*/*;q=0.1"))
        assertEquals(ResourceType.SUBDOCUMENT, guess("/frame", "Accept" to "text/html,application/xhtml+xml"))
    }

    @Test fun xhrHeuristics() {
        assertEquals(ResourceType.XHR, guess("/api", "X-Requested-With" to "XMLHttpRequest"))
        assertEquals(ResourceType.XHR, guess("/api/v1/data", "Accept" to "*/*"))
        assertEquals(ResourceType.OTHER, guess("/file.bin", "Accept" to "*/*"))
        assertEquals(ResourceType.OTHER, guess(null))
    }

    @Test fun secFetchDestWins() {
        assertEquals(ResourceType.SCRIPT, guess("/x.png", "Sec-Fetch-Dest" to "script"))
        assertEquals(ResourceType.SUBDOCUMENT, guess("/x", "sec-fetch-dest" to "iframe"))
        assertEquals(ResourceType.MEDIA, guess("/x", "Sec-Fetch-Dest" to "video"))
    }

    @Test fun queryIsIgnored() {
        assertEquals(ResourceType.SCRIPT, guess("/lib.js?v=1.2.3"))
    }

    @Test fun engineNames() {
        assertEquals("sub_frame", ResourceTypes.engineName(ResourceType.SUBDOCUMENT))
        assertEquals("xhr", ResourceTypes.engineName(ResourceType.XHR))
        assertEquals(ResourceType.entries.size, ResourceType.entries.map(ResourceTypes::engineName).toSet().size)
    }
}
