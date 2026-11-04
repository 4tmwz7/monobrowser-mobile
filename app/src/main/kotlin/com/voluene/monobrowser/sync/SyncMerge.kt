package com.voluene.monobrowser.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One synced value; [v] == null is a deletion (tombstone), [t] when it was last changed (epoch ms). */
@Serializable
data class SyncEntry(val v: String? = null, val t: Long = 0)

/**
 * The sync file (`monobrowser-sync.json`, kept wherever the user put it: Google Drive, OneDrive, Nextcloud ...):
 * named collections of key -> entry. Readable JSON so other MonoBrowser builds (desktop) can share it.
 */
@Serializable
data class SyncFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val updatedAt: Long = 0,
    /** Device that wrote it last (shown in settings). */
    val updatedBy: String = "",
    val collections: Map<String, Map<String, SyncEntry>> = emptyMap(),
) {
    companion object {
        const val FORMAT = "monobrowser-sync"
    }
}

object SyncCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(file: SyncFile): String = json.encodeToString(SyncFile.serializer(), file)

    /** An empty (new) file is an empty sync; anything that is not a MonoBrowser sync file is an error (null). */
    fun decode(text: String): SyncFile? {
        if (text.isBlank()) return SyncFile()
        val file = runCatching { json.decodeFromString(SyncFile.serializer(), text) }.getOrNull() ?: return null
        return file.takeIf { it.format == SyncFile.FORMAT }
    }

    @Serializable
    private data class Base(val collections: Map<String, Map<String, String>> = emptyMap())

    /** The state after the last sync (stored on the device), per collection. */
    fun encodeBase(base: Map<String, Map<String, String>>): String = json.encodeToString(Base.serializer(), Base(base))
    fun decodeBase(text: String): Map<String, Map<String, String>> =
        runCatching { json.decodeFromString(Base.serializer(), text).collections }.getOrDefault(emptyMap())
}

/**
 * Three-way merge of one collection: [base] = what both sides agreed on at the last sync, [local] = this device
 * now, [remote] = the file now. A key changed here since the last sync goes out (stamped [now]); otherwise the
 * file's value comes in (a tombstone deletes it here). Nothing is lost when the file is new or was reset: what
 * this device has and the file lacks is written back. Tombstones older than [tombstoneTtlMs] are dropped. Pure.
 */
object SyncMerge {
    const val TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000

    data class Result(
        /** The merged collection for this device (also the next base). */
        val local: Map<String, String>,
        val remote: Map<String, SyncEntry>,
        val localChanged: Boolean,
        val remoteChanged: Boolean,
    )

    fun merge(
        base: Map<String, String>,
        local: Map<String, String>,
        remote: Map<String, SyncEntry>,
        now: Long,
        tombstoneTtlMs: Long = TOMBSTONE_TTL_MS,
    ): Result {
        val outLocal = LinkedHashMap<String, String>()
        val outRemote = LinkedHashMap<String, SyncEntry>()
        val keys = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); addAll(base.keys) }
        for (key in keys) {
            val b = base[key]
            val l = local[key]
            val r = remote[key]
            when {
                // Changed on this device since the last sync (added, edited or deleted): this device's version goes out.
                l != b -> {
                    if (l != null) outLocal[key] = l
                    if (l != null || r != null || b != null) outRemote[key] = SyncEntry(l, now)
                }
                // Unchanged here and the file knows the key: the file's version wins (it may be newer or a deletion).
                r != null -> {
                    if (r.v != null) outLocal[key] = r.v
                    outRemote[key] = r
                }
                // Unchanged here, unknown to the file (new or reset file): keep it and write it back.
                l != null -> {
                    outLocal[key] = l
                    outRemote[key] = SyncEntry(l, now)
                }
            }
        }
        // Old deletions have reached every device by now.
        outRemote.entries.removeAll { (_, e) -> e.v == null && now - e.t > tombstoneTtlMs }
        return Result(outLocal, outRemote, localChanged = outLocal != local, remoteChanged = outRemote != remote)
    }
}
