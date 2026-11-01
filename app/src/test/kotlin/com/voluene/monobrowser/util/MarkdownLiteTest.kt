package com.voluene.monobrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the desktop `markdown-lite.test.ts` plus structure tests for the block model. */
class MarkdownLiteTest {
    private fun spans(vararg parts: MdSpan) = parts.toList()
    private fun text(t: String, bold: Boolean = false, italic: Boolean = false) = MdSpan(t, bold = bold, italic = italic)
    private fun allSpans(blocks: List<MdBlock>): List<MdSpan> = blocks.flatMap { block ->
        when (block) {
            is MdBlock.Heading -> block.spans
            is MdBlock.Paragraph -> block.spans
            is MdBlock.Quote -> block.spans
            is MdBlock.Bullets -> block.items.flatten()
            is MdBlock.Numbered -> block.items.flatten()
            else -> emptyList()
        }
    }

    @Test fun rendersHeadingsListsCodeAndLinks() {
        val blocks = MarkdownLite.parse(
            "# Title\n\nSome **bold** and `code` text.\n\n- one\n- two\n\n1. first\n\n```\nlet a = 1 < 2;\n```\n\nSee [site](https://example.com/x) and https://a.org/b.",
        )
        assertEquals(MdBlock.Heading(1, spans(text("Title"))), blocks[0])
        assertEquals(
            MdBlock.Paragraph(spans(text("Some "), text("bold", bold = true), text(" and "), MdSpan("code", MdSpan.Kind.CODE), text(" text."))),
            blocks[1],
        )
        assertEquals(MdBlock.Bullets(listOf(spans(text("one")), spans(text("two")))), blocks[2])
        assertEquals(MdBlock.Numbered(listOf(spans(text("first")))), blocks[3])
        assertEquals(MdBlock.Code("let a = 1 < 2;"), blocks[4])
        val last = (blocks[5] as MdBlock.Paragraph).spans
        assertEquals(MdSpan("site", MdSpan.Kind.LINK, "https://example.com/x"), last[1])
        // A trailing full stop stays outside of a bare URL.
        assertEquals(MdSpan("https://a.org/b", MdSpan.Kind.LINK, "https://a.org/b"), last[3])
        assertEquals(text("."), last[4])
    }

    @Test fun neverLinksNonHttpSchemesAndKeepsMarkupLiteral() {
        val blocks = MarkdownLite.parse("<script>alert(1)</script> [x](javascript:alert(1)) [y](data:text/html,hi)\n\n```\n<img src=x onerror=1>\n```")
        val links = allSpans(blocks).filter { it.kind == MdSpan.Kind.LINK }
        assertTrue(links.none { it.url!!.startsWith("javascript") || it.url!!.startsWith("data") })
        assertTrue(allSpans(blocks).joinToString("") { it.text }.contains("<script>"))
        assertEquals(MdBlock.Code("<img src=x onerror=1>"), blocks.last())
    }

    @Test fun codeSpansKeepTheirContentLiteral() {
        val spans = (MarkdownLite.parse("Use `**not bold** [a](https://x.y)` here").single() as MdBlock.Paragraph).spans
        assertEquals(MdSpan("**not bold** [a](https://x.y)", MdSpan.Kind.CODE), spans[1])
        assertTrue(spans.none { it.bold })
        assertTrue(spans.none { it.kind == MdSpan.Kind.LINK })
    }

    @Test fun extractUrlsListsUniqueHttpUrls() {
        assertEquals(listOf("https://a.org/x", "https://b.org"), MarkdownLite.extractUrls("a https://a.org/x. b (https://b.org) https://a.org/x"))
    }

    @Test fun italicBoldAndNestingAreIndependent() {
        val spans = MarkdownLite.parseInline("a *it* b **bo** c")
        assertEquals(listOf(text("a "), text("it", italic = true), text(" b "), text("bo", bold = true), text(" c")), spans)
        // Underscores and asterisks glued to words are not emphasis (same rule as the desktop regex).
        assertEquals(listOf(text("2*3*4")), MarkdownLite.parseInline("2*3*4"))
        assertEquals(listOf(text("snake_case_name")), MarkdownLite.parseInline("snake_case_name"))
    }

    @Test fun emphasisAroundACodeSpanKeepsTheStyle() {
        val spans = MarkdownLite.parseInline("**`x`**")
        assertEquals(listOf(MdSpan("x", MdSpan.Kind.CODE, bold = true)), spans)
    }

    @Test fun headingLevelsAndClosingHashes() {
        val blocks = MarkdownLite.parse("## Two ##\n###### Six\n####### seven")
        assertEquals(MdBlock.Heading(2, spans(text("Two"))), blocks[0])
        assertEquals(MdBlock.Heading(6, spans(text("Six"))), blocks[1])
        assertTrue(blocks[2] is MdBlock.Paragraph) // seven hashes is not a heading
    }

    @Test fun rulesQuotesAndParagraphJoining() {
        val blocks = MarkdownLite.parse("one\ntwo\n\n---\n\n> quoted\n> more\n\n* * *")
        assertEquals(MdBlock.Paragraph(spans(text("one two"))), blocks[0])
        assertEquals(MdBlock.Rule, blocks[1])
        assertEquals(MdBlock.Quote(spans(text("quoted more"))), blocks[2])
        assertEquals(MdBlock.Rule, blocks[3])
    }

    @Test fun listContinuationAndSwitchingListKinds() {
        val blocks = MarkdownLite.parse("- first\n  continued\n- second\n1. one\n2) two")
        assertEquals(MdBlock.Bullets(listOf(spans(text("first continued")), spans(text("second")))), blocks[0])
        assertEquals(MdBlock.Numbered(listOf(spans(text("one")), spans(text("two")))), blocks[1])
    }

    @Test fun unterminatedFenceTakesTheRestAndCrLfIsNormalised() {
        val blocks = MarkdownLite.parse("intro\r\n```\r\ncode 1\r\ncode 2")
        assertEquals(MdBlock.Paragraph(spans(text("intro"))), blocks[0])
        assertEquals(MdBlock.Code("code 1\ncode 2"), blocks[1])
        assertEquals(emptyList<MdBlock>(), MarkdownLite.parse(""))
        assertFalse(MarkdownLite.parse("   \n\n").isNotEmpty())
    }
}
