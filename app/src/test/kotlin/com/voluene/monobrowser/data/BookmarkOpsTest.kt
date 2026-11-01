package com.voluene.monobrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BookmarkOpsTest {
    private fun folderData(): Triple<BookmarkData, Long, Long> {
        var d = BookmarkData()
        val (d1, news) = BookmarkOps.addFolder(d, "News")
        val (d2, tech) = BookmarkOps.addFolder(d1, "Tech", news)
        return Triple(d2, news, tech)
    }

    @Test fun addIgnoresDuplicateUrlInSameFolderOnly() {
        var d = BookmarkOps.addBookmark(BookmarkData(), "https://a.com", "A", null, 5)
        assertSame(d, BookmarkOps.addBookmark(d, "https://a.com", "A again"))
        val (withFolder, f) = BookmarkOps.addFolder(d, "F")
        d = BookmarkOps.addBookmark(withFolder, "https://a.com", "A", f)
        assertEquals(2, d.bookmarks.size)
    }

    @Test fun unknownFolderFallsBackToTopLevel() {
        val d = BookmarkOps.addBookmark(BookmarkData(), "https://a.com", "A", 999)
        assertNull(d.bookmarks.single().folderId)
    }

    @Test fun removeByUrlAndFind() {
        val d = BookmarkOps.addBookmark(BookmarkData(), "https://a.com", "A")
        assertNotNull(BookmarkOps.findByUrl(d, "https://a.com"))
        assertEquals(0, BookmarkOps.removeByUrl(d, "https://a.com").bookmarks.size)
    }

    @Test fun pathAndMove() {
        val (d, news, tech) = folderData()
        assertEquals(listOf("News", "Tech"), BookmarkOps.path(d, tech))
        val withBookmark = BookmarkOps.addBookmark(d, "https://a.com", "A", tech)
        val id = withBookmark.bookmarks.single().id
        val moved = BookmarkOps.moveBookmark(withBookmark, id, news)
        assertEquals(news, moved.bookmarks.single().folderId)
    }

    @Test fun folderCannotMoveIntoItsOwnDescendant() {
        val (d, news, tech) = folderData()
        assertSame(d, BookmarkOps.moveFolder(d, news, tech))
        assertSame(d, BookmarkOps.moveFolder(d, news, news))
        assertNull(BookmarkOps.folder(BookmarkOps.moveFolder(d, tech, null), tech)!!.parentId)
    }

    @Test fun deleteFolderRemovesContents() {
        val (d, news, tech) = folderData()
        val full = BookmarkOps.addBookmark(BookmarkOps.addBookmark(d, "https://a.com", "A", tech), "https://b.com", "B", null)
        val after = BookmarkOps.deleteFolder(full, news)
        assertEquals(0, after.folders.size)
        assertEquals(listOf("https://b.com"), after.bookmarks.map { it.url })
    }

    @Test fun renameRejectsBlank() {
        val (d, news, _) = folderData()
        assertSame(d, BookmarkOps.renameFolder(d, news, "  "))
        assertEquals("Fresh", BookmarkOps.folder(BookmarkOps.renameFolder(d, news, " Fresh "), news)!!.name)
    }

    @Test fun editKeepsOldUrlWhenBlank() {
        val d = BookmarkOps.addBookmark(BookmarkData(), "https://a.com", "A")
        val e = BookmarkOps.editBookmark(d, d.bookmarks.single().id, "New", " ")
        assertEquals("https://a.com", e.bookmarks.single().url)
        assertEquals("New", e.bookmarks.single().title)
    }

    @Test fun importBuildsFoldersSkipsDuplicatesAndBadSchemes() {
        val entries = listOf(
            PortableBookmark("https://a.com/", "A", "2024-01-02T03:04:05.000Z", listOf("News", "Tech")),
            PortableBookmark("https://a.com/", "A", null, listOf("News", "Tech")),
            PortableBookmark("javascript:alert(1)", "bad"),
            PortableBookmark("https://top.com/", ""),
        )
        val result = BookmarkOps.importInto(BookmarkData(), entries, now = 77)
        assertEquals(2, result.added)
        assertEquals(2, result.skipped)
        assertEquals(2, result.data.folders.size)
        val a = result.data.bookmarks.first { it.url == "https://a.com/" }
        assertEquals(listOf("News", "Tech"), BookmarkOps.path(result.data, a.folderId))
        assertEquals(1704164645000L, a.createdAt)
        assertEquals("https://top.com/", result.data.bookmarks.first { it.url == "https://top.com/" }.title)
        // Importing again changes nothing.
        assertEquals(0, BookmarkOps.importInto(result.data, entries).added)
    }

    @Test fun exportThenImportKeepsStructure() {
        val (d, _, tech) = folderData()
        val data = BookmarkOps.addBookmark(BookmarkOps.addBookmark(d, "https://a.com/", "A", tech, 1704164645000L), "https://top.com/", "Top")
        val portable = BookmarkOps.toPortable(data)
        val back = BookmarkOps.importInto(BookmarkData(), BookmarksIo.parseImport(BookmarksIo.exportHtml(portable))).data
        assertEquals(
            data.bookmarks.map { it.url to BookmarkOps.path(data, it.folderId) }.toSet(),
            back.bookmarks.map { it.url to BookmarkOps.path(back, it.folderId) }.toSet(),
        )
    }

    @Test fun codecRoundTripAndRepairsBrokenData() {
        val (d, _, tech) = folderData()
        val data = BookmarkOps.addBookmark(d, "https://a.com/", "A", tech)
        assertEquals(data, BookmarkCodec.decode(BookmarkCodec.encode(data)))
        val broken = BookmarkData(
            nextId = 1,
            folders = listOf(BookmarkFolder(5, "A", 6), BookmarkFolder(6, "B", 5), BookmarkFolder(7, "C", 99)),
            bookmarks = listOf(Bookmark(8, "https://x.com", "X", folderId = 123), Bookmark(8, "https://dup.com"), Bookmark(9, "")),
        )
        val fixed = BookmarkCodec.sanitize(broken)
        assertNull(fixed.folders.first { it.id == 7L }.parentId)
        // The 5 <-> 6 cycle is broken: at least one of them is top level.
        assertNotNull(fixed.folders.firstOrNull { it.parentId == null && (it.id == 5L || it.id == 6L) })
        assertEquals(listOf(8L), fixed.bookmarks.map { it.id })
        assertNull(fixed.bookmarks.single().folderId)
        assertEquals(9L, fixed.nextId)
        assertNull(BookmarkCodec.decode("not json"))
    }
}
