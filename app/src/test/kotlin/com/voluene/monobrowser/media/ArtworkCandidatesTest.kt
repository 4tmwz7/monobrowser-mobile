package com.voluene.monobrowser.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCandidatesTest {
    @Test fun youtubeThumbnailsSharpestFirst() {
        val expected = listOf("maxresdefault", "sddefault", "hqdefault").map { "https://i.ytimg.com/vi/dQw4w9WgXcQ/$it.jpg" }
        assertEquals(expected, Artwork.candidates("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertEquals(expected, Artwork.candidates("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/mqdefault.webp?sqp=abc"))
    }

    @Test fun otherImagesAsTheyAre() {
        assertEquals(listOf("https://example.com/cover.png"), Artwork.candidates("https://example.com/cover.png"))
        assertEquals(listOf("https://i.ytimg.com/an_webp/x/mqdefault_6s.webp"), Artwork.candidates("https://i.ytimg.com/an_webp/x/mqdefault_6s.webp"))
    }
}
