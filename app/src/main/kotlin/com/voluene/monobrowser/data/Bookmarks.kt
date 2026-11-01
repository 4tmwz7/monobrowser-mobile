package com.voluene.monobrowser.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Bookmark(
    val id: Long,
    val url: String,
    val title: String = "",
    /** null = top level. */
    val folderId: Long? = null,
    /** Epoch milliseconds, 0 = unknown. */
    val createdAt: Long = 0,
)

@Serializable
data class BookmarkFolder(val id: Long, val name: String, val parentId: Long? = null)

/** Whole bookmark tree; ids are shared by folders and bookmarks. Stored as `bookmarks.json`. */
@Serializable
data class BookmarkData(
    val version: Int = 1,
    val nextId: Long = 1,
    val folders: List<BookmarkFolder> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)

data class ImportResult(val data: BookmarkData, val added: Int, val skipped: Int)

/** Pure operations on [BookmarkData]; every function returns a new value. */
object BookmarkOps {
    const val MAX_NAME = 200

    fun findByUrl(data: BookmarkData, url: String): Bookmark? = data.bookmarks.firstOrNull { it.url == url }

    fun folder(data: BookmarkData, id: Long?): BookmarkFolder? = id?.let { fid -> data.folders.firstOrNull { it.id == fid } }

    fun childFolders(data: BookmarkData, parent: Long?): List<BookmarkFolder> =
        data.folders.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }

    fun childBookmarks(data: BookmarkData, folder: Long?): List<Bookmark> = data.bookmarks.filter { it.folderId == folder }

    /** Names from the top level down to [folderId] (empty for the top level). */
    fun path(data: BookmarkData, folderId: Long?): List<String> {
        val names = ArrayList<String>()
        var current = folder(data, folderId)
        var guard = 0
        while (current != null && guard++ < 64) {
            names.add(0, current.name)
            current = folder(data, current.parentId)
        }
        return names
    }

    /** [folderId] and every folder below it. */
    fun descendants(data: BookmarkData, folderId: Long): Set<Long> {
        val result = linkedSetOf(folderId)
        var added = true
        while (added) {
            added = false
            for (f in data.folders) if (f.parentId in result && result.add(f.id)) added = true
        }
        return result
    }

    private fun cleanName(name: String) = name.trim().take(MAX_NAME)

    /** Adds a bookmark; the same URL in the same folder is not added twice. */
    fun addBookmark(data: BookmarkData, url: String, title: String, folderId: Long? = null, now: Long = 0): BookmarkData {
        val target = folderId?.takeIf { folder(data, it) != null }
        if (data.bookmarks.any { it.url == url && it.folderId == target }) return data
        val bookmark = Bookmark(data.nextId, url, cleanName(title), target, now)
        return data.copy(nextId = data.nextId + 1, bookmarks = data.bookmarks + bookmark)
    }

    fun removeBookmark(data: BookmarkData, id: Long): BookmarkData = data.copy(bookmarks = data.bookmarks.filterNot { it.id == id })

    /** Removes every bookmark of [url] (the star in the menu). */
    fun removeByUrl(data: BookmarkData, url: String): BookmarkData = data.copy(bookmarks = data.bookmarks.filterNot { it.url == url })

    fun editBookmark(data: BookmarkData, id: Long, title: String, url: String): BookmarkData =
        data.copy(bookmarks = data.bookmarks.map { if (it.id == id) it.copy(title = cleanName(title), url = url.trim().ifEmpty { it.url }) else it })

    fun moveBookmark(data: BookmarkData, id: Long, folderId: Long?): BookmarkData {
        val target = folderId?.takeIf { folder(data, it) != null }
        return data.copy(bookmarks = data.bookmarks.map { if (it.id == id) it.copy(folderId = target) else it })
    }

    fun addFolder(data: BookmarkData, name: String, parentId: Long? = null): Pair<BookmarkData, Long> {
        val parent = parentId?.takeIf { folder(data, it) != null }
        val id = data.nextId
        val label = cleanName(name).ifEmpty { "?" }
        return data.copy(nextId = id + 1, folders = data.folders + BookmarkFolder(id, label, parent)) to id
    }

    fun renameFolder(data: BookmarkData, id: Long, name: String): BookmarkData {
        val label = cleanName(name)
        if (label.isEmpty()) return data
        return data.copy(folders = data.folders.map { if (it.id == id) it.copy(name = label) else it })
    }

    /** Moves a folder; a move into itself or one of its descendants is ignored. */
    fun moveFolder(data: BookmarkData, id: Long, parentId: Long?): BookmarkData {
        if (parentId != null && (parentId in descendants(data, id) || folder(data, parentId) == null)) return data
        return data.copy(folders = data.folders.map { if (it.id == id) it.copy(parentId = parentId) else it })
    }

    /** Deletes the folder with everything inside it. */
    fun deleteFolder(data: BookmarkData, id: Long): BookmarkData {
        val doomed = descendants(data, id)
        return data.copy(
            folders = data.folders.filterNot { it.id in doomed },
            bookmarks = data.bookmarks.filterNot { it.folderId in doomed },
        )
    }

    /** All folders with their display path, for the "move to" picker (sorted by path). */
    fun folderChoices(data: BookmarkData, exclude: Set<Long> = emptySet()): List<Pair<BookmarkFolder, String>> =
        data.folders.filter { it.id !in exclude }.map { it to path(data, it.id).joinToString(" / ") }.sortedBy { it.second.lowercase() }

    /** Folder id for a path, creating missing folders. */
    private fun ensurePath(data: BookmarkData, names: List<String>): Pair<BookmarkData, Long?> {
        var current = data
        var parent: Long? = null
        for (raw in names) {
            val name = cleanName(raw)
            if (name.isEmpty()) continue
            val existing = current.folders.firstOrNull { it.parentId == parent && it.name == name }
            if (existing != null) {
                parent = existing.id
            } else {
                val (next, id) = addFolder(current, name, parent)
                current = next
                parent = id
            }
        }
        return current to parent
    }

    /** Merges imported entries; entries with a non-http(s) URL or already present (same URL + folder) are skipped. */
    fun importInto(data: BookmarkData, entries: List<PortableBookmark>, now: Long = 0): ImportResult {
        var current = data
        var added = 0
        var skipped = 0
        for (entry in entries) {
            val url = entry.url.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) { skipped++; continue }
            val (withFolders, folderId) = ensurePath(current, entry.folder)
            val created = BookmarksIo.parseIso(entry.createdAt) ?: now
            val next = addBookmark(withFolders, url, entry.title.ifBlank { url }, folderId, created)
            if (next === withFolders) { current = withFolders; skipped++ } else { current = next; added++ }
        }
        return ImportResult(current, added, skipped)
    }

    fun toPortable(data: BookmarkData): List<PortableBookmark> {
        // Folder order: top-level bookmarks first, then by folder path, keeping insertion order inside a folder.
        val ordered = data.bookmarks.sortedWith(compareBy({ path(data, it.folderId).isNotEmpty() }, { path(data, it.folderId).joinToString("/").lowercase() }))
        return ordered.map {
            PortableBookmark(it.url, it.title, if (it.createdAt > 0) BookmarksIo.formatIso(it.createdAt) else null, path(data, it.folderId))
        }
    }
}

object BookmarkCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(data: BookmarkData): String = json.encodeToString(BookmarkData.serializer(), sanitize(data))

    fun decode(text: String): BookmarkData? =
        runCatching { sanitize(json.decodeFromString(BookmarkData.serializer(), text)) }.getOrNull()

    /** Drops duplicate ids, orphaned/cyclic folder links and bookmarks without a URL; keeps `nextId` above every id. */
    fun sanitize(data: BookmarkData): BookmarkData {
        val seen = HashSet<Long>()
        val folderIds = data.folders.filter { it.id > 0 && it.name.isNotBlank() }.map { it.id }.toSet()
        var folders = data.folders.filter { it.id in folderIds && seen.add(it.id) }
            .map { if (it.parentId != null && (it.parentId !in folderIds || it.parentId == it.id)) it.copy(parentId = null) else it }
        // Break cycles: a folder whose ancestor chain does not end at the top level is moved to the top.
        val byId = folders.associateBy { it.id }
        folders = folders.map { f ->
            var cur: Long? = f.parentId
            var steps = 0
            while (cur != null && steps++ <= folders.size) cur = byId[cur]?.parentId
            if (cur != null) f.copy(parentId = null) else f
        }
        val ids = folders.map { it.id }.toSet()
        val bookmarks = data.bookmarks.filter { it.id > 0 && it.url.isNotBlank() && seen.add(it.id) }
            .map { if (it.folderId != null && it.folderId !in ids) it.copy(folderId = null) else it }
        val maxId = maxOf(folders.maxOfOrNull { it.id } ?: 0L, bookmarks.maxOfOrNull { it.id } ?: 0L)
        return BookmarkData(nextId = maxOf(data.nextId, maxId + 1, 1L), folders = folders, bookmarks = bookmarks)
    }
}
