package com.voluene.monobrowser.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTest {
    private val para = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore."

    /** What evaluateJavascript returns for a script that evaluates to a string: a JSON string literal. */
    private fun asJsResult(json: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(json))

    private fun article(blocks: String, title: String = "My title") =
        """{"title":"$title","site":"example.com","byline":"Jan","url":"https://example.com/a","blocks":[$blocks]}"""

    private fun paragraphs(n: Int) = (1..n).joinToString(",") { """{"t":"p","x":"$para $it"}""" }

    @Test fun parsesEvaluateJavascriptResult() {
        val parsed = Reader.parse(asJsResult(article(paragraphs(6))))
        assertNotNull(parsed)
        parsed!!
        assertEquals("My title", parsed.title)
        assertEquals("example.com", parsed.site)
        assertEquals("Jan", parsed.byline)
        assertEquals(6, parsed.blocks.size)
        assertTrue(parsed.blocks.all { it.kind == BlockKind.PARAGRAPH })
    }

    @Test fun acceptsPlainJsonToo() {
        assertNotNull(Reader.parse(article(paragraphs(6))))
    }

    @Test fun nullAndGarbageGiveNull() {
        assertNull(Reader.parse(null))
        assertNull(Reader.parse("null"))
        assertNull(Reader.parse(""))
        assertNull(Reader.parse("not json"))
        assertNull(Reader.parse("\"not json either\""))
    }

    @Test fun tooLittleTextIsNotAnArticle() {
        assertNull(Reader.parse(article("""{"t":"p","x":"short"},{"t":"p","x":"also short"}""")))
        // Plenty of list items but no real paragraphs (a navigation page).
        val items = (1..30).joinToString(",") { """{"t":"li","x":"$para"}""" }
        assertNull(Reader.parse(article(items)))
    }

    @Test fun mapsKindsAndDropsTitleHeadingAndTrailingHeadings() {
        val blocks = listOf(
            """{"t":"h","x":"My Title"}""",
            """{"t":"p","x":"$para"}""",
            """{"t":"h","x":"Section"}""",
            """{"t":"li","x":"item"}""",
            """{"t":"q","x":"quoted"}""",
            """{"t":"pre","x":"code  line\n  indented  "}""",
            """{"t":"p","x":"$para $para $para"}""",
            """{"t":"p","x":"$para $para"}""",
            """{"t":"h","x":"Related articles"}""",
        ).joinToString(",")
        val parsed = Reader.parse(article(blocks))!!
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.LIST_ITEM, BlockKind.QUOTE, BlockKind.CODE, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH),
            parsed.blocks.map { it.kind },
        )
        // Code keeps its inner whitespace, only the trailing part is trimmed.
        assertEquals("code  line\n  indented", parsed.blocks[4].text)
    }

    @Test fun collapsesWhitespaceAndDuplicates() {
        val blocks = """{"t":"p","x":"  a   lot\t of   space  $para"},{"t":"p","x":"$para"},{"t":"p","x":"$para"},{"t":"p","x":"$para $para $para"}"""
        val parsed = Reader.parse(article(blocks))!!
        assertEquals("a lot of space $para", parsed.blocks[0].text)
        assertEquals(3, parsed.blocks.size)
    }

    @Test fun readingTime() {
        val words = (1..660).joinToString(" ") { "word" }
        val parsed = Reader.parse(article("""{"t":"p","x":"$words"},{"t":"p","x":"$para"}"""))!!
        assertEquals(3, parsed.readingMinutes)
        val short = Reader.parse(article(paragraphs(5)))!!
        assertEquals(1, short.readingMinutes)
    }

    @Test fun scriptIsSelfContainedExpression() {
        assertTrue(Reader.SCRIPT.startsWith("(function"))
        assertTrue(Reader.SCRIPT.trimEnd().endsWith("})()"))
        assertTrue(Reader.SCRIPT.contains("blocks.length > 2000"))
    }
}
