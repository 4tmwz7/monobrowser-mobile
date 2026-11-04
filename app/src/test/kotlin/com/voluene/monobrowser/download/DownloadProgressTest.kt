package com.voluene.monobrowser.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTest {
    private fun item(id: Long, done: Long, total: Long) = DownloadItem(id, "file$id.zip", DownloadStatus.RUNNING, done, total, null)

    @Test fun combinesDownloads() {
        val s = DownloadProgress.summary(listOf(item(1, 50, 100), item(2, 25, 300)))
        assertEquals(75L, s.done)
        assertEquals(400L, s.total)
        assertEquals(18, s.percent)
        assertEquals("file1.zip", s.title)
        assertEquals(listOf(1L, 2L), s.ids)
    }

    @Test fun unknownSizeIsIndeterminate() {
        val s = DownloadProgress.summary(listOf(item(1, 50, 100), item(2, 10, -1)))
        assertEquals(-1, s.percent)
        assertEquals(0L, s.total)
    }
}
