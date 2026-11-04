package com.voluene.monobrowser.reader

import com.voluene.monobrowser.util.writeTextAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A saved article in the list (without its text). */
data class SavedArticleInfo(val id: String, val url: String, val title: String, val site: String, val minutes: Int, val savedAt: Long)

/**
 * "Read later": reader-view articles saved as JSON files (`<dir>/<id>.json`, id = hash of the URL, so saving a
 * page twice replaces it). Everything needed to read offline is in the file. The codec is pure and unit-tested.
 */
object ReadingListCodec {
    @Serializable
    private data class StoredBlock(val k: String, val t: String)

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val url: String,
        val title: String = "",
        val site: String = "",
        val byline: String = "",
        val savedAt: Long = 0,
        val blocks: List<StoredBlock> = emptyList(),
        val lang: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun idFor(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun encode(article: ReaderArticle, savedAt: Long): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            url = article.url, title = article.title, site = article.site, byline = article.byline, savedAt = savedAt,
            blocks = article.blocks.map { StoredBlock(it.kind.name, it.text) }, lang = article.language,
        ),
    )

    /** The article and when it was saved; null for a damaged file. Unknown block kinds read as paragraphs. */
    fun decode(text: String): Pair<ReaderArticle, Long>? {
        val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() ?: return null
        if (stored.url.isBlank()) return null
        val blocks = stored.blocks.map { b ->
            ReaderBlock(BlockKind.entries.firstOrNull { it.name == b.k } ?: BlockKind.PARAGRAPH, b.t)
        }
        return ReaderArticle(stored.title, stored.site, stored.byline, stored.url, blocks, stored.lang) to stored.savedAt
    }
}

class ReadingList(private val dir: File, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<SavedArticleInfo>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<SavedArticleInfo>> = _items.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { reload() }
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun reload() {
        _items.value = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val (article, savedAt) = runCatching { ReadingListCodec.decode(f.readText()) }.getOrNull() ?: return@mapNotNull null
            SavedArticleInfo(f.name.removeSuffix(".json"), article.url, article.title, article.site, article.readingMinutes, savedAt)
        }.sortedByDescending { it.savedAt }
    }

    fun isSaved(url: String): Boolean = _items.value.any { it.url == url }

    fun save(article: ReaderArticle) {
        scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            runCatching { writeTextAtomically(file(ReadingListCodec.idFor(article.url)), ReadingListCodec.encode(article, System.currentTimeMillis())) }
            reload()
        }
    }

    fun remove(url: String) {
        scope.launch(Dispatchers.IO) {
            file(ReadingListCodec.idFor(url)).delete()
            reload()
        }
    }

    /** Sync: url -> stored article JSON, for every saved article (blocking). */
    fun snapshot(): Map<String, String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            ReadingListCodec.decode(text)?.first?.url?.let { it to text }
        }.toMap()

    /** Sync: makes the list match [articles] (url -> article JSON; blocking). */
    fun applySynced(articles: Map<String, String>) {
        dir.mkdirs()
        val current = snapshot()
        articles.forEach { (url, text) ->
            if (current[url] != text && ReadingListCodec.decode(text) != null) runCatching { writeTextAtomically(file(ReadingListCodec.idFor(url)), text) }
        }
        current.keys.filter { it !in articles }.forEach { file(ReadingListCodec.idFor(it)).delete() }
        reload()
    }

    /** Blocking read of a saved article (call off the main thread). */
    fun load(id: String): ReaderArticle? =
        if (!Regex("^[0-9a-f]{24}$").matches(id)) null
        else runCatching { ReadingListCodec.decode(file(id).readText())?.first }.getOrNull()
}
