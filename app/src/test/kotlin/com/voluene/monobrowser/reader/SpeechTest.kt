package com.voluene.monobrowser.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test fun rates() {
        assertEquals(1f, Speech.nextRate(0.8f))
        assertEquals(1.25f, Speech.nextRate(1f))
        assertEquals(1.5f, Speech.nextRate(1.25f))
        assertEquals(0.8f, Speech.nextRate(1.5f))
    }

    @Test fun splitsLongTextAtSentences() {
        val text = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val parts = Speech.split(text, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf("short"), Speech.split("short", 100))
        // No break at all: hard cut.
        assertTrue(Speech.split("x".repeat(250), 100).all { it.length <= 100 })
    }

    @Test fun chunksSkipCodeAndStartWithTheTitle() {
        val article = ReaderArticle(
            "Title", "", "", "https://a.com",
            listOf(ReaderBlock(BlockKind.PARAGRAPH, "One."), ReaderBlock(BlockKind.CODE, "x = 1"), ReaderBlock(BlockKind.HEADING, "Two")),
        )
        assertEquals(listOf(0 to "Title", 0 to "One.", 2 to "Two"), Speech.chunks(article, 4000))
    }

    @Test fun languageIsValidated() {
        val raw = """{"title":"T","lang":"pl-PL","url":"https://a.com","blocks":[""" +
            (1..6).joinToString(",") { n -> """{"t":"p","x":"${"Lorem ipsum dolor sit amet $n ".repeat(4)}"}""" } + "]}"
        assertEquals("pl-PL", Reader.parse(raw)!!.language)
        assertEquals("", Reader.parse(raw.replace("pl-PL", "<script>"))!!.language)
    }
}
