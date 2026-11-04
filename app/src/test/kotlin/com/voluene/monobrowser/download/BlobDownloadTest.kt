package com.voluene.monobrowser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobDownloadTest {
    @Test fun dispositionNames() {
        assertEquals("report.csv", BlobDownload.fileNameFromDisposition("attachment; filename=\"report.csv\""))
        assertEquals("report.csv", BlobDownload.fileNameFromDisposition("attachment; filename=report.csv; size=10"))
        assertEquals("Łódź plan.pdf", BlobDownload.fileNameFromDisposition("attachment; filename=\"x.pdf\"; filename*=UTF-8''%C5%81%C3%B3d%C5%BA%20plan.pdf"))
        assertNull(BlobDownload.fileNameFromDisposition("inline"))
        assertNull(BlobDownload.fileNameFromDisposition(null))
    }

    @Test fun sanitizeStripsPathsAndBadCharacters() {
        assertEquals("passwd", BlobDownload.sanitize("../../etc/passwd"))
        assertEquals("a_b_.txt", BlobDownload.sanitize("a<b>.txt"))
        assertEquals("evil.txt", BlobDownload.sanitize("C:\\temp\\evil.txt"))
        assertNull(BlobDownload.sanitize(" .. "))
        assertNull(BlobDownload.sanitize(""))
    }

    @Test fun extensions() {
        assertEquals("file.csv", BlobDownload.withExtension("file", "csv"))
        assertEquals("file.txt", BlobDownload.withExtension("file.txt", "csv"))
        assertEquals("file", BlobDownload.withExtension("file", null))
    }

    @Test fun scriptQuotesItsInputs() {
        val script = BlobDownload.script("blob:https://a.com/1234-5678\"');alert(1)//", "tok\"en")
        assertTrue(script.contains("fetch(\"blob:https://a.com/1234-5678\\\"');alert(1)//\")"))
        assertTrue(script.contains("var t = \"tok\\\"en\";"))
        assertFalse(script.contains("fetch(blob:"))
        assertTrue(script.contains("MonoBlob.deliver(t, reader.result"))
    }
}
