package com.voluene.monobrowser.sync

import com.voluene.monobrowser.data.Bookmark
import com.voluene.monobrowser.data.BookmarkData
import com.voluene.monobrowser.data.BookmarkFolder
import com.voluene.monobrowser.data.BookmarkOps

/**
 * Bookmarks as sync collections: local ids differ between devices, so folders are keyed by their path of names
 * and bookmarks by folder path + URL. Moving or renaming becomes delete + add, which merges cleanly. Pure.
 */
object BookmarkSync {
    private const val SEP = "\u001F" // between path parts and before the URL; never typed in names

    fun folderKey(path: List<String>) = path.joinToString(SEP)
    fun bookmarkKey(path: List<String>, url: String) = folderKey(path) + SEP + SEP + url

    /** folders: path -> "" ; bookmarks: path + url -> title. */
    fun toCollections(data: BookmarkData): Pair<Map<String, String>, Map<String, String>> {
        val folders = LinkedHashMap<String, String>()
        data.folders.forEach { f -> folders[folderKey(BookmarkOps.path(data, f.id))] = "" }
        val bookmarks = LinkedHashMap<String, String>()
        data.bookmarks.forEach { b -> bookmarks[bookmarkKey(BookmarkOps.path(data, b.folderId), b.url)] = b.title }
        return folders to bookmarks
    }

    /**
     * Rebuilds the tree from merged collections, keeping the ids (and order) of what already exists in [current];
     * new folders and bookmarks are appended. Folders that a bookmark lives in are created even if missing.
     */
    fun fromCollections(current: BookmarkData, folders: Map<String, String>, bookmarks: Map<String, String>, now: Long): BookmarkData {
        var nextId = current.nextId
        val existingFolders = current.folders.associateBy { folderKey(BookmarkOps.path(current, it.id)) }
        val pathToId = HashMap<String, Long>()
        val outFolders = ArrayList<BookmarkFolder>()

        fun ensureFolder(path: List<String>): Long? {
            if (path.isEmpty()) return null
            val key = folderKey(path)
            pathToId[key]?.let { return it }
            val parent = ensureFolder(path.dropLast(1))
            val existing = existingFolders[key]
            val id = existing?.id ?: nextId++
            outFolders.add(BookmarkFolder(id, path.last(), parent))
            pathToId[key] = id
            return id
        }

        // Existing folders first (their order), then new ones.
        val wantedFolders = LinkedHashSet<String>()
        existingFolders.keys.filter { it in folders }.forEach(wantedFolders::add)
        folders.keys.forEach(wantedFolders::add)
        wantedFolders.forEach { key -> ensureFolder(key.split(SEP).filter { it.isNotEmpty() }) }

        val existingBookmarks = current.bookmarks.associateBy { bookmarkKey(BookmarkOps.path(current, it.folderId), it.url) }
        val orderedKeys = LinkedHashSet<String>()
        existingBookmarks.keys.filter { it in bookmarks }.forEach(orderedKeys::add)
        bookmarks.keys.forEach(orderedKeys::add)
        val outBookmarks = orderedKeys.map { key ->
            val pathPart = key.substringBefore(SEP + SEP)
            val url = key.substringAfter(SEP + SEP)
            val folderId = ensureFolder(pathPart.split(SEP).filter { it.isNotEmpty() })
            val title = bookmarks.getValue(key)
            val existing = existingBookmarks[key]
            existing?.copy(title = title, folderId = folderId) ?: Bookmark(nextId++, url, title, folderId, now)
        }
        // The start page selection is device-local; what the merge removed leaves it.
        return BookmarkOps.pruneStart(current.copy(nextId = nextId, folders = outFolders, bookmarks = outBookmarks))
    }
}
