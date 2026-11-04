package com.voluene.monobrowser.sync

import com.voluene.monobrowser.data.BookmarkData
import com.voluene.monobrowser.data.BookmarkOps
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkSyncTest {
    private fun sample(): BookmarkData {
        var d = BookmarkData()
        d = BookmarkOps.addBookmark(d, "https://top.com/", "Top")
        val (withWork, work) = BookmarkOps.addFolder(d, "Work", null)
        d = withWork
        val (withDev, dev) = BookmarkOps.addFolder(d, "Dev", work)
        d = withDev
        d = BookmarkOps.addBookmark(d, "https://kotlinlang.org/", "Kotlin", dev)
        d = BookmarkOps.addBookmark(d, "https://jira.example/", "Jira", work)
        return d
    }

    @Test fun roundTripKeepsIdsAndTree() {
        val d = sample()
        val (folders, bookmarks) = BookmarkSync.toCollections(d)
        assertEquals(2, folders.size)
        assertEquals(3, bookmarks.size)
        val rebuilt = BookmarkSync.fromCollections(d, folders, bookmarks, now = 1)
        assertEquals(d.folders.toSet(), rebuilt.folders.toSet())
        assertEquals(d.bookmarks, rebuilt.bookmarks)
    }

    @Test fun anotherDevicesTreeIsRebuiltWithLocalIds() {
        val (folders, bookmarks) = BookmarkSync.toCollections(sample())
        val fresh = BookmarkSync.fromCollections(BookmarkData(), folders, bookmarks, now = 7)
        assertEquals(listOf("Work", "Dev"), BookmarkOps.path(fresh, fresh.bookmarks.first { it.url.contains("kotlin") }.folderId))
        assertEquals(listOf("Work"), BookmarkOps.path(fresh, fresh.bookmarks.first { it.url.contains("jira") }.folderId))
        assertEquals(null, fresh.bookmarks.first { it.url.contains("top") }.folderId)
        assertEquals(5L, fresh.folders.size + fresh.bookmarks.size.toLong())
        // Ids are unique.
        val ids = fresh.folders.map { it.id } + fresh.bookmarks.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun removedBookmarkAndRenamedTitle() {
        val d = sample()
        val (folders, bookmarks) = BookmarkSync.toCollections(d)
        val jiraKey = bookmarks.keys.first { it.endsWith("https://jira.example/") }
        val kotlinKey = bookmarks.keys.first { it.endsWith("https://kotlinlang.org/") }
        val rebuilt = BookmarkSync.fromCollections(d, folders, bookmarks - jiraKey + (kotlinKey to "Kotlin docs"), now = 1)
        assertEquals(listOf("Top", "Kotlin docs"), rebuilt.bookmarks.map { it.title })
    }
}
