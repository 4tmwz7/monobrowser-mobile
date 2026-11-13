package com.voluene.monobrowser.data

import com.voluene.monobrowser.sync.BookmarkSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkStartPageTest {
    private class Fixture {
        var d = BookmarkData()
        val a: Long; val b: Long; val c: Long; val news: Long; val tech: Long; val inTech: Long
        init {
            d = BookmarkOps.addBookmark(d, "https://a.com", "A")
            a = d.bookmarks.last().id
            d = BookmarkOps.addFolder(d, "News").let { (x, id) -> news = id; x }
            d = BookmarkOps.addFolder(d, "Tech", news).let { (x, id) -> tech = id; x }
            d = BookmarkOps.addBookmark(d, "https://b.com", "B", news)
            b = d.bookmarks.last().id
            d = BookmarkOps.addBookmark(d, "https://c.com", "C", news)
            c = d.bookmarks.last().id
            d = BookmarkOps.addBookmark(d, "https://t.com", "T", tech)
            inTech = d.bookmarks.last().id
        }
    }

    @Test fun selectionKeepsTheOrderAndIgnoresDuplicatesAndUnknownIds() {
        val f = Fixture()
        var d = BookmarkOps.addToStart(f.d, f.news)
        d = BookmarkOps.addToStart(d, f.a)
        d = BookmarkOps.addToStart(d, f.news)
        d = BookmarkOps.addToStart(d, 9999)
        assertEquals(listOf(f.news, f.a), d.startPage)
        assertTrue(BookmarkOps.isOnStart(d, f.a))
        assertEquals(listOf(f.a), BookmarkOps.toggleStart(d, f.news).startPage)
    }

    @Test fun contentSplitsBookmarksAndFolderSections() {
        val f = Fixture()
        var d = BookmarkOps.addToStart(f.d, f.news)
        d = BookmarkOps.addToStart(d, f.a)
        val content = BookmarkOps.startContent(d)
        assertEquals(listOf("A"), content.bookmarks.map { it.title })
        val section = content.folders.single()
        assertEquals("News", section.folder.name)
        assertEquals(listOf("B", "C"), section.bookmarks.map { it.title })
        assertEquals(listOf("Tech"), section.subfolders.map { it.name })
        assertFalse(content.isEmpty)
        assertTrue(BookmarkOps.startContent(BookmarkData()).isEmpty)
    }

    @Test fun deletingDropsItFromTheSelection() {
        val f = Fixture()
        val picked = listOf(f.a, f.news, f.tech, f.inTech).fold(f.d) { acc, id -> BookmarkOps.addToStart(acc, id) }
        assertEquals(listOf(f.news, f.tech, f.inTech), BookmarkOps.removeBookmark(picked, f.a).startPage.filter { it != f.a })
        assertEquals(listOf(f.a), BookmarkOps.deleteFolder(picked, f.news).startPage) // folder, its sub-folder and their bookmarks
        assertEquals(listOf(f.news, f.tech, f.inTech), BookmarkOps.removeByUrl(picked, "https://a.com").startPage)
    }

    @Test fun codecLoadsOldFilesAndCleansUnknownIds() {
        val old = BookmarkCodec.decode("""{"version":1,"nextId":3,"bookmarks":[{"id":1,"url":"https://a.com","title":"A"}]}""")
        assertNotNull(old)
        assertEquals(emptyList<Long>(), old!!.startPage)
        val dirty = BookmarkCodec.decode("""{"nextId":3,"bookmarks":[{"id":1,"url":"https://a.com"}],"startPage":[1,77,1,2]}""")!!
        assertEquals(listOf(1L), dirty.startPage)
        val roundTrip = BookmarkCodec.decode(BookmarkCodec.encode(dirty))!!
        assertEquals(dirty, roundTrip)
    }

    @Test fun syncKeepsTheSelectionAndPrunesWhatTheMergeRemoved() {
        val f = Fixture()
        val picked = listOf(f.a, f.news, f.c).fold(f.d) { acc, id -> BookmarkOps.addToStart(acc, id) }
        val (folders, bookmarks) = BookmarkSync.toCollections(picked)
        // The other device deleted bookmark C: it leaves the merged collection and the selection.
        val withoutC = bookmarks.filterKeys { !it.endsWith("https://c.com") }
        val merged = BookmarkSync.fromCollections(picked, folders, withoutC, 0)
        assertEquals(listOf(f.a, f.news), merged.startPage)
        // Unchanged collections keep it as it is.
        assertEquals(picked.startPage, BookmarkSync.fromCollections(picked, folders, bookmarks, 0).startPage)
    }

    @Test fun exportDoesNotCarryTheSelection() {
        val f = Fixture()
        val picked = BookmarkOps.addToStart(f.d, f.a)
        assertEquals(BookmarkOps.toPortable(f.d), BookmarkOps.toPortable(picked))
        assertSame(picked, BookmarkOps.addToStart(picked, f.a))
    }
}
