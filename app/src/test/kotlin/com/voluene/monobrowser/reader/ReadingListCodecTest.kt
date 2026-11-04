package com.voluene.monobrowser.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingListCodecTest {
    private val article = ReaderArticle(
        "Tytuł", "example.com", "Jan", "https://example.com/a",
        listOf(ReaderBlock(BlockKind.HEADING, "H"), ReaderBlock(BlockKind.PARAGRAPH, "Łódź \"quoted\""), ReaderBlock(BlockKind.CODE, "a\n  b")),
    )

    @Test fun roundTrip() {
        val (decoded, savedAt) = ReadingListCodec.decode(ReadingListCodec.encode(article, 1234L))!!
        assertEquals(article, decoded)
        assertEquals(1234L, savedAt)
    }

    @Test fun ids() {
        assertEquals(24, ReadingListCodec.idFor("https://a.com").length)
        assertEquals(ReadingListCodec.idFor("https://a.com"), ReadingListCodec.idFor(" https://a.com "))
        assertNotEquals(ReadingListCodec.idFor("https://a.com"), ReadingListCodec.idFor("https://b.com"))
    }

    @Test fun damagedFiles() {
        assertNull(ReadingListCodec.decode("nope"))
        assertNull(ReadingListCodec.decode("""{"url":""}"""))
        val unknownKind = ReadingListCodec.decode("""{"url":"https://a.com","blocks":[{"k":"VIDEO","t":"x"}]}""")!!.first
        assertEquals(BlockKind.PARAGRAPH, unknownKind.blocks.single().kind)
    }
}
