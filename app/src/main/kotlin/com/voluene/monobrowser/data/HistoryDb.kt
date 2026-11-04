package com.voluene.monobrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Browsing history in plain SQLite (no Room: it would pull KSP into the build for one table).
 * Methods block on disk IO; call them off the main thread. Private tabs never reach this class.
 */
class HistoryDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (_id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', visited_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX history_visited ON history (visited_at)")
        db.execSQL("CREATE INDEX history_url ON history (url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Adds a visit; a repeat of the newest URL within [DEDUPE_MS] (reloads, redirects) only refreshes its row. */
    fun recordVisit(url: String, title: String, now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.rawQuery("SELECT _id, url, visited_at FROM history ORDER BY _id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst() && c.getString(1) == url && now - c.getLong(2) < DEDUPE_MS) {
                val values = ContentValues().apply { put("visited_at", now); if (title.isNotEmpty()) put("title", title) }
                db.update("history", values, "_id = ?", arrayOf(c.getLong(0).toString()))
                return
            }
        }
        db.insert("history", null, ContentValues().apply { put("url", url); put("title", title); put("visited_at", now) })
        db.execSQL("DELETE FROM history WHERE _id <= (SELECT MAX(_id) FROM history) - $MAX_ROWS")
    }

    /** The title often arrives after the page finished; attach it to the newest visit of that URL. */
    fun updateTitle(url: String, title: String) {
        if (title.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE history SET title = ? WHERE _id = (SELECT MAX(_id) FROM history WHERE url = ?)",
            arrayOf(title, url),
        )
    }

    /** Newest first; [query] filters by URL or title (case-insensitive substring). */
    fun search(query: String = "", limit: Int = 500): List<HistoryEntry> {
        val like = "%${escapeLike(query.trim())}%"
        val sql = if (query.isBlank()) {
            "SELECT _id, url, title, visited_at FROM history ORDER BY visited_at DESC, _id DESC LIMIT $limit"
        } else {
            "SELECT _id, url, title, visited_at FROM history WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' " +
                "ORDER BY visited_at DESC, _id DESC LIMIT $limit"
        }
        val args = if (query.isBlank()) null else arrayOf(like, like)
        return readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    /** One entry per URL, most visited/recent first, for the address-bar dropdown. */
    fun suggest(query: String, limit: Int = 5): List<HistoryEntry> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val like = "%${escapeLike(text)}%"
        return readableDatabase.rawQuery(
            "SELECT MAX(_id), url, title, MAX(visited_at) AS t FROM history " +
                "WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' " +
                "GROUP BY url ORDER BY COUNT(*) DESC, t DESC LIMIT $limit",
            arrayOf(like, like),
        ).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    /** Visit counts per URL since [since] (newest visits first when counts tie), for the start page's top sites. */
    fun mostVisited(since: Long, limit: Int = 200): List<VisitCount> =
        readableDatabase.rawQuery(
            "SELECT url, (SELECT h2.title FROM history h2 WHERE h2.url = h.url AND h2.title != '' ORDER BY h2._id DESC LIMIT 1), " +
                "COUNT(*) AS c, MAX(visited_at) AS t FROM history h WHERE visited_at >= ? " +
                "GROUP BY url ORDER BY c DESC, t DESC LIMIT $limit",
            arrayOf(since.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(VisitCount(c.getString(0), c.getString(1).orEmpty(), c.getInt(2), c.getLong(3))) }
        }

    /** The most recently visited pages, one entry per URL, newest first. */
    fun recent(limit: Int = 5): List<HistoryEntry> =
        readableDatabase.rawQuery(
            "SELECT MAX(_id), url, title, MAX(visited_at) AS t FROM history GROUP BY url ORDER BY t DESC LIMIT $limit",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    fun delete(id: Long) {
        writableDatabase.delete("history", "_id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
    }

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val DEDUPE_MS = 5_000L
        const val MAX_ROWS = 10_000
    }
}
