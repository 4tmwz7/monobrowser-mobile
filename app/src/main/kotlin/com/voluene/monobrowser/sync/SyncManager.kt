package com.voluene.monobrowser.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.voluene.monobrowser.util.writeTextAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One kind of synced data: read this device's collection, take the merged one back. */
interface SyncSource {
    val name: String
    /** Called on the IO dispatcher. */
    suspend fun read(): Map<String, String>
    /** Called on the IO dispatcher; switch to Main inside when the data lives in UI state. */
    suspend fun apply(merged: Map<String, String>)
}

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    /** Error to show (null = fine). */
    val error: SyncError? = null,
    /** Device that wrote the file last. */
    val lastWriter: String = "",
)

enum class SyncError { NO_ACCESS, NOT_A_SYNC_FILE, IO }

/**
 * Sync through a file the user picked (Storage Access Framework): Google Drive, OneDrive, Nextcloud ... keep
 * it in their cloud, Drive for desktop makes it a local file the desktop app can use too. Each sync reads the file,
 * merges every [SyncSource] three-way with the state of the last sync (stored on the device), applies what came
 * in and writes the file back when something went out.
 */
class SyncManager(
    context: Context,
    private val scope: CoroutineScope,
    private val sources: List<SyncSource>,
    private val uri: () -> String,
) {
    private val app = context.applicationContext
    private val baseFile = File(app.filesDir, "sync_base.json")
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val enabled: Boolean get() = uri().isNotBlank()

    /** Syncs unless one ran in the last [minGapMs] (app start / leave), or now when [force]. */
    fun sync(force: Boolean = false, minGapMs: Long = 30_000) {
        if (!enabled) return
        if (!force && System.currentTimeMillis() - _status.value.lastSyncAt < minGapMs) return
        scope.launch { syncNow() }
    }

    suspend fun syncNow() = mutex.withLock {
        val target = uri().takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@withLock
        _status.value = _status.value.copy(running = true)
        val result = withContext(Dispatchers.IO) { runCatching { syncOnce(target) } }
        _status.value = result.fold(
            onSuccess = { writer -> SyncStatus(lastSyncAt = System.currentTimeMillis(), lastWriter = writer) },
            onFailure = { e ->
                val error = when (e) {
                    is SecurityException -> SyncError.NO_ACCESS
                    is NotSyncFile -> SyncError.NOT_A_SYNC_FILE
                    else -> SyncError.IO
                }
                _status.value.copy(running = false, error = error)
            },
        )
    }

    private class NotSyncFile : Exception()

    /** Returns who wrote the file last (this device when it wrote). */
    private suspend fun syncOnce(target: Uri): String {
        val resolver = app.contentResolver
        val text = resolver.openInputStream(target)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val remote = SyncCodec.decode(text) ?: throw NotSyncFile()
        val base = if (baseFile.isFile) SyncCodec.decodeBase(baseFile.readText()) else emptyMap()
        val now = System.currentTimeMillis()
        val newBase = HashMap<String, Map<String, String>>()
        val newRemote = HashMap(remote.collections)
        var remoteChanged = false
        for (source in sources) {
            val merged = SyncMerge.merge(base[source.name].orEmpty(), source.read(), remote.collections[source.name].orEmpty(), now)
            if (merged.localChanged) source.apply(merged.local)
            if (merged.remoteChanged) remoteChanged = true
            newRemote[source.name] = merged.remote
            newBase[source.name] = merged.local
        }
        var writer = remote.updatedBy
        if (remoteChanged || remote.format.isEmpty()) {
            writer = deviceName()
            val out = SyncCodec.encode(SyncFile(updatedAt = now, updatedBy = writer, collections = newRemote))
            // "wt" truncates; some providers only know "w" (which they truncate as well).
            val stream = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull() ?: resolver.openOutputStream(target, "w")
            stream?.use { it.write(out.toByteArray(Charsets.UTF_8)) } ?: throw java.io.IOException("cannot write")
        }
        writeTextAtomically(baseFile, SyncCodec.encodeBase(newBase))
        return writer
    }

    /** Takes the picked file: keeps the permission across restarts. Returns false when the provider refuses. */
    fun adopt(picked: Uri): Boolean = runCatching {
        app.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        baseFile.delete() // a different file: the first sync unites both sides
        _status.value = SyncStatus()
    }.isSuccess

    /** Stops syncing (the file and this device's data stay as they are). */
    fun release(current: String) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(current), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        baseFile.delete()
        _status.value = SyncStatus()
    }

    /** "monobrowser-sync.json" and where it lives ("Google Drive", "OneDrive" ...) for the settings row. */
    fun describe(value: String): Pair<String, String> {
        val target = runCatching { Uri.parse(value) }.getOrNull() ?: return "" to ""
        val name = runCatching {
            app.contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
        return name to SyncProviders.label(target.authority.orEmpty())
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").take(60)
}

/** Friendly names of common document providers. Pure. */
object SyncProviders {
    fun label(authority: String): String = when {
        authority.startsWith("com.google.android.apps.docs") -> "Google Drive"
        authority.startsWith("com.microsoft.skydrive") -> "OneDrive"
        authority.startsWith("com.dropbox") -> "Dropbox"
        authority.startsWith("com.nextcloud") || authority.startsWith("org.nextcloud") -> "Nextcloud"
        authority.startsWith("com.android.externalstorage") -> "Device storage"
        else -> ""
    }
}
