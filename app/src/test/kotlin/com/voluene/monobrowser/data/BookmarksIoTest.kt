package com.voluene.monobrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of the desktop `bookmarks-io.test.ts` plus byte-exact format checks. */
class BookmarksIoTest {
    private val sample = listOf(
        PortableBookmark("https://example.com/?a=1&b=2", "Tom & \"Jerry\" <3", "2024-01-02T03:04:05.000Z"),
        PortableBookmark("https://wp.pl", ""),
    )

    @Test fun htmlExportAndImportRoundTrip() {
        val parsed = BookmarksIo.parseImport(BookmarksIo.exportHtml(sample))
        assertEquals(2, parsed.size)
        assertEquals(sample[0].url, parsed[0].url)
        assertEquals(sample[0].title, parsed[0].title)
        assertEquals(sample[0].createdAt, parsed[0].createdAt)
        assertEquals("https://wp.pl", parsed[1].title)
    }

    @Test fun jsonExportAndImportRoundTrip() {
        val parsed = BookmarksIo.parseImport(BookmarksIo.exportJson(sample))
        assertEquals(sample.map { it.url }, parsed.map { it.url })
        assertEquals(sample[0].createdAt, parsed[0].createdAt)
    }

    @Test fun htmlImportFromChromeFirefoxWithNestedFoldersAndAttributes() {
        val html = """<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><p>
    <DT><H3 ADD_DATE="1">Folder</H3><DL><p>
      <DT><A HREF="https://a.com/" ADD_DATE="1700000000" ICON="data:image/png;base64,xx">A <b>site</b></A>
    </DL><p><DT><a href='https://b.com/x?y=1&amp;z=2'>B</a></DL>"""
        val parsed = BookmarksIo.parseImport(html)
        assertEquals(
            listOf("https://a.com/" to "A site", "https://b.com/x?y=1&z=2" to "B"),
            parsed.map { it.url to it.title },
        )
        assertEquals("2023-11-14T22:13:20.000Z", parsed[0].createdAt)
        // Mobile extension: folders are kept.
        assertEquals(listOf("Folder"), parsed[0].folder)
        assertEquals(emptyList<String>(), parsed[1].folder)
    }

    @Test fun jsonImportOfChromiumTree() {
        val tree = """{"roots":{"bookmark_bar":{"children":[{"name":"X","url":"https://x.com"}]}}}"""
        assertEquals(listOf("https://x.com"), BookmarksIo.parseImport(tree).map { it.url })
        assertEquals("X", BookmarksIo.parseImport(tree)[0].title) // falls back to "name"
    }

    @Test fun malformedJsonThrowsAndEmptyHtmlGivesEmptyList() {
        try {
            BookmarksIo.parseImport("[oops")
            fail("expected an exception")
        } catch (_: Exception) {
        }
        assertEquals(emptyList<PortableBookmark>(), BookmarksIo.parseImport("<html></html>"))
    }

    @Test fun htmlExportIsByteIdenticalToDesktop() {
        val expected = listOf(
            "<!DOCTYPE NETSCAPE-Bookmark-file-1>",
            "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">",
            "<TITLE>Bookmarks</TITLE>",
            "<H1>Bookmarks</H1>",
            "<DL><p>",
            "    <DT><A HREF=\"https://example.com/?a=1&amp;b=2\" ADD_DATE=\"1704164645\">Tom &amp; &quot;Jerry&quot; &lt;3</A>",
            "    <DT><A HREF=\"https://wp.pl\">https://wp.pl</A>",
            "</DL><p>",
            "",
        ).joinToString("\n")
        assertEquals(expected, BookmarksIo.exportHtml(sample))
    }

    @Test fun jsonExportMatchesJsonStringifyWithTwoSpaceIndent() {
        val expected = """[
  {
    "url": "https://example.com/?a=1&b=2",
    "title": "Tom & \"Jerry\" <3",
    "createdAt": "2024-01-02T03:04:05.000Z"
  },
  {
    "url": "https://wp.pl",
    "title": ""
  }
]"""
        assertEquals(expected, BookmarksIo.exportJson(sample))
        assertEquals("[]", BookmarksIo.exportJson(emptyList()))
    }

    @Test fun jsonEscapesControlCharactersLikeJavaScript() {
        val json = BookmarksIo.exportJson(listOf(PortableBookmark("https://a.com/", "line\nbreak\ttab \u0001 zażółć/")))
        assertTrue(json, json.contains("\"line\\nbreak\\ttab \\u0001 zażółć/\""))
    }

    @Test fun foldersSurviveHtmlRoundTrip() {
        val items = listOf(
            PortableBookmark("https://top.com/", "Top"),
            PortableBookmark("https://a.com/", "A", folder = listOf("News")),
            PortableBookmark("https://b.com/", "B", folder = listOf("News", "Tech")),
        )
        val html = BookmarksIo.exportHtml(items)
        assertTrue(html.contains("<DT><H3>News</H3>"))
        val parsed = BookmarksIo.parseImport(html)
        assertEquals(items.map { it.url to it.folder }.toSet(), parsed.map { it.url to it.folder }.toSet())
    }

    @Test fun foldersSurviveJsonRoundTrip() {
        val items = listOf(PortableBookmark("https://b.com/", "B", "2024-01-02T03:04:05.000Z", listOf("News", "Tech")))
        assertEquals(items, BookmarksIo.parseImport(BookmarksIo.exportJson(items)))
    }

    @Test fun numericEntitiesAndBadDates() {
        val html = """<A HREF="https://a.com/?q=&#x41;&#66;&#0;" ADD_DATE="abc">&#x1F600; &unknown; &lt;b&gt;</A>"""
        val parsed = BookmarksIo.parseImport(html)
        assertEquals("https://a.com/?q=AB&#0;", parsed[0].url)
        assertEquals("😀 &unknown; <b>", parsed[0].title)
        assertEquals(null, parsed[0].createdAt)
    }

    @Test fun anchorsWithoutHrefAreSkipped() {
        assertEquals(emptyList<PortableBookmark>(), BookmarksIo.parseImport("""<A NAME="x">no link</A><A HREF="">empty</A>"""))
    }

    @Test fun bomIsStripped() {
        assertEquals(1, BookmarksIo.parseImport("﻿[{\"url\":\"https://a.com\"}]").size)
    }
}
