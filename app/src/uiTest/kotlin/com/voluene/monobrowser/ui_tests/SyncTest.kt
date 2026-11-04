package com.voluene.monobrowser.ui_tests

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.data.BookmarkData
import com.voluene.monobrowser.data.BookmarkOps
import com.voluene.monobrowser.sync.BookmarkSync
import com.voluene.monobrowser.sync.SyncCodec
import com.voluene.monobrowser.sync.SyncEntry
import com.voluene.monobrowser.sync.SyncFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Sync end to end with the real TabManager and a file "written by another device". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncTest {
    private fun syncNow(manager: TabManager) {
        // The sync hops to the main thread for UI state: run it off the test (main) thread and keep the looper going.
        var done = false
        var error: Throwable? = null
        Thread { runCatching { runBlocking { manager.sync.syncNow() } }.onFailure { error = it }; done = true }.start()
        val until = System.currentTimeMillis() + 20_000
        while (!done && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        error?.let { throw it }
        assertTrue("sync finished", done)
        assertNull("no sync error", manager.sync.status.value.error)
    }

    @Test fun twoDevicesThroughTheFile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // What the other device wrote: a bookmark in Work/Dev, a pinned site, a note.
        var other = BookmarkData()
        val (withWork, work) = BookmarkOps.addFolder(other, "Work", null); other = withWork
        val (withDev, dev) = BookmarkOps.addFolder(other, "Dev", work); other = withDev
        other = BookmarkOps.addBookmark(other, "https://kotlinlang.org/", "Kotlin", dev)
        val (folders, bookmarks) = BookmarkSync.toCollections(other)
        val remoteBookmarks = folders.mapKeys { "f\u001E" + it.key }.mapValues { SyncEntry(it.value, 1) } +
            bookmarks.mapKeys { "b\u001E" + it.key }.mapValues { SyncEntry(it.value, 1) }
        val file = File(app.filesDir, "monobrowser-sync.json")
        file.writeText(
            SyncCodec.encode(
                SyncFile(
                    updatedAt = 1, updatedBy = "Desktop",
                    collections = mapOf(
                        "bookmarks" to remoteBookmarks,
                        "pinned" to mapOf("https://github.com/" to SyncEntry("GitHub", 1)),
                        "notes" to mapOf("n-desktop-1" to SyncEntry("# From the desktop\nhello", 1)),
                    ),
                ),
            ),
        )

        lateinit var manager: TabManager
        manager = TabManager(app)
        manager.editBookmarks { BookmarkOps.addBookmark(it, "https://phone.example/", "From the phone") }
        manager.updateSettings { it.copy(syncUri = Uri.fromFile(file).toString()) }

        syncNow(manager)
        shadowOf(Looper.getMainLooper()).idle()

        // This device got everything from the file...
        val local = manager.bookmarks.value
        assertEquals(setOf("https://phone.example/", "https://kotlinlang.org/"), local.bookmarks.map { it.url }.toSet())
        val kotlin = local.bookmarks.first { it.url == "https://kotlinlang.org/" }
        assertEquals(listOf("Work", "Dev"), BookmarkOps.path(local, kotlin.folderId))
        assertEquals(listOf("https://github.com/"), manager.settings.value.pinnedSites.map { it.url })
        assertEquals("# From the desktop\nhello", manager.notes.read("n-desktop-1"))

        // ...and the file got this device's bookmark.
        var written = SyncCodec.decode(file.readText())!!
        assertTrue(written.collections.getValue("bookmarks").any { (k, e) -> k.endsWith("https://phone.example/") && e.v == "From the phone" })

        // Deleting here leaves a tombstone, so the other device deletes it too.
        manager.editBookmarks { BookmarkOps.removeByUrl(it, "https://kotlinlang.org/") }
        syncNow(manager)
        written = SyncCodec.decode(file.readText())!!
        val entry = written.collections.getValue("bookmarks").entries.first { it.key.endsWith("https://kotlinlang.org/") }.value
        assertNull(entry.v)
        assertEquals(1, manager.bookmarks.value.bookmarks.size)
    }
}
