package com.voluene.monobrowser.download

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.voluene.monobrowser.util.DataUrl
import com.voluene.monobrowser.util.writeTextAtomically
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

enum class DownloadStatus { PENDING, RUNNING, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val mimeType: String?,
) {
    /** 0..100, or -1 when the size is unknown. */
    val percent: Int get() = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else -1
    val isActive: Boolean get() = status == DownloadStatus.PENDING || status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED
}

/** Hands downloads to the system [DownloadManager] and remembers which ones this app started. */
class DownloadRepository(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val idsFile = File(this.context.filesDir, "downloads.json")

    /** Enqueues an http(s) download; returns (id, file name). */
    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, cookie: String?, referer: String?): Pair<Long, String> {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            // Android 16+: progress is our Live Update (DownloadProgressService); the system only reports the result.
            .setNotificationVisibility(
                if (DownloadProgressService.supported) DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION
                else DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        if (!referer.isNullOrBlank()) request.addRequestHeader("Referer", referer)
        val id = manager.enqueue(request)
        writeIds(listOf(id) + readIds())
        return id to fileName
    }

    /** Saves a `data:` URL into the public Downloads folder. Returns the file name, or null on failure. */
    fun saveDataUrl(url: String, suggestedName: String? = null): String? {
        val data = DataUrl.parse(url) ?: return null
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(data.mimeType) ?: "bin"
        val name = BlobDownload.sanitize(suggestedName)?.let { BlobDownload.withExtension(it, extension) }
            ?: "download-${System.currentTimeMillis()}.$extension"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, data.mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert failed")
                context.contentResolver.openOutputStream(uri)!!.use { it.write(data.bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
                // MediaStore renames duplicates itself; the plain file API would overwrite them.
                var file = File(dir, name)
                var n = 1
                while (file.exists()) file = File(dir, "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.', extension)}")
                file.writeBytes(data.bytes)
                return@runCatching file.name
            }
            name
        }.getOrNull()
    }

    fun items(): List<DownloadItem> {
        val ids = readIds()
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, DownloadItem>()
        manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val mimeCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                byId[id] = DownloadItem(
                    id = id,
                    title = c.getString(titleCol).orEmpty(),
                    status = when (c.getInt(statusCol)) {
                        DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.DONE
                        else -> DownloadStatus.FAILED
                    },
                    downloadedBytes = c.getLong(doneCol),
                    totalBytes = c.getLong(totalCol),
                    mimeType = c.getString(mimeCol),
                )
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    /** Cancels a running download (the system removes the partial file) and drops it from the list. */
    fun cancel(id: Long) {
        manager.remove(id)
        forget(id)
    }

    /** Removes the entry from the list; a finished file stays on disk. */
    fun forget(id: Long) = writeIds(readIds() - id)

    fun forgetFinished() {
        val active = items().filter { it.isActive }.map { it.id }.toSet()
        writeIds(readIds().filter { it in active })
    }

    /** Intent that opens a finished download, or null when the file is gone. */
    fun openIntent(id: Long): Intent? {
        val uri = manager.getUriForDownloadedFile(id) ?: return null
        val mime = manager.getMimeTypeForDownloadedFile(id)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun readIds(): List<Long> = runCatching {
        Json.decodeFromString(ListSerializer(Long.serializer()), idsFile.readText())
    }.getOrDefault(emptyList())

    private fun writeIds(ids: List<Long>) {
        runCatching { writeTextAtomically(idsFile, Json.encodeToString(ListSerializer(Long.serializer()), ids.distinct().take(200))) }
    }
}
