package com.voluene.monobrowser.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Bookmark exchange entry (desktop `PortableBookmark`: url, title, optional ISO `createdAt`).
 * [folder] is a mobile extension (folder path, top level = empty): written as `<H3>` folders in HTML and as an
 * extra `folder` array in JSON; the desktop importer ignores both, so the files stay compatible.
 */
data class PortableBookmark(
    val url: String,
    val title: String,
    val createdAt: String? = null,
    val folder: List<String> = emptyList(),
)

/**
 * Port of the desktop `bookmarks-io.ts`: Netscape bookmark HTML and the desktop JSON array, plus lenient
 * parsing of HTML/JSON exported by other browsers (including the Chromium bookmark tree).
 */
object BookmarksIo {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `Date.prototype.toISOString` equivalent. */
    fun formatIso(epochMs: Long): String = ISO.format(Instant.ofEpochMilli(epochMs))

    /** Epoch ms of an ISO timestamp, null when unparsable. */
    fun parseIso(value: String?): Long? =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun escapeHtml(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val ENTITY = Regex("&(#x[0-9a-f]+|#\\d+|amp|lt|gt|quot|apos);", RegexOption.IGNORE_CASE)

    private fun decodeEntities(value: String): String = ENTITY.replace(value) { match ->
        val entity = match.groupValues[1].lowercase()
        when (entity) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x")) entity.substring(2).toLongOrNull(16) else entity.substring(1).toLongOrNull()
                if (code != null && code > 0 && code <= 0x10ffff) String(Character.toChars(code.toInt())) else match.value
            }
        }
    }

    private fun anchor(bookmark: PortableBookmark, indent: String): String {
        val seconds = parseIso(bookmark.createdAt)?.let { Math.floorDiv(it, 1000L) }
        val addDate = if (seconds != null) " ADD_DATE=\"$seconds\"" else ""
        return "$indent<DT><A HREF=\"${escapeHtml(bookmark.url)}\"$addDate>${escapeHtml(bookmark.title.ifEmpty { bookmark.url })}</A>"
    }

    /** Netscape Bookmark File. Without folders the output is byte-identical to the desktop export. */
    fun exportHtml(bookmarks: List<PortableBookmark>): String {
        val rows = ArrayList<String>()
        // Folder tree as nested DLs; entries are grouped by folder path in order of first appearance.
        class Node(val name: String) {
            val items = ArrayList<PortableBookmark>()
            val children = LinkedHashMap<String, Node>()
        }
        val root = Node("")
        for (b in bookmarks) {
            var node = root
            for (name in b.folder) node = node.children.getOrPut(name) { Node(name) }
            node.items.add(b)
        }
        fun emit(node: Node, level: Int) {
            val indent = "    ".repeat(level)
            node.items.forEach { rows.add(anchor(it, indent)) }
            node.children.values.forEach { child ->
                rows.add("$indent<DT><H3>${escapeHtml(child.name)}</H3>")
                rows.add("$indent<DL><p>")
                emit(child, level + 1)
                rows.add("$indent</DL><p>")
            }
        }
        emit(root, 1)
        return (listOf(
            "<!DOCTYPE NETSCAPE-Bookmark-file-1>",
            "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">",
            "<TITLE>Bookmarks</TITLE>",
            "<H1>Bookmarks</H1>",
            "<DL><p>",
        ) + rows + listOf("</DL><p>", "")).joinToString("\n")
    }

    /** `JSON.stringify(list, null, 2)` of `{url, title, createdAt}` objects (plus `folder` when set). */
    fun exportJson(bookmarks: List<PortableBookmark>): String {
        if (bookmarks.isEmpty()) return "[]"
        return bookmarks.joinToString(",\n", prefix = "[\n", postfix = "\n]") { b ->
            val fields = ArrayList<String>()
            fields.add("    \"url\": ${jsString(b.url)}")
            fields.add("    \"title\": ${jsString(b.title)}")
            if (b.createdAt != null) fields.add("    \"createdAt\": ${jsString(b.createdAt)}")
            if (b.folder.isNotEmpty()) fields.add(b.folder.joinToString(", ", prefix = "    \"folder\": [", postfix = "]") { jsString(it) })
            fields.joinToString(",\n", prefix = "  {\n", postfix = "\n  }")
        }
    }

    /** JSON string literal with the same escaping as `JSON.stringify` (non-ASCII and `/` stay as they are). */
    private fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun parseJson(text: String): List<PortableBookmark> {
        val result = ArrayList<PortableBookmark>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonArray -> node.forEach(::visit)
                is JsonObject -> {
                    val url = (node["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (url != null) {
                        fun str(key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val folder = (node["folder"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
                        result.add(PortableBookmark(url, str("title") ?: str("name") ?: "", str("createdAt"), folder))
                    }
                    // Nested structures (e.g. the Chromium bookmark tree).
                    node.values.forEach { if (it is JsonObject || it is JsonArray) visit(it) }
                }
                else -> Unit
            }
        }
        visit(Json.parseToJsonElement(text)) // throws on malformed JSON, like JSON.parse
        return result
    }

    private val TOKEN = Regex(
        "<h3\\b[^>]*>([\\s\\S]*?)</h3>|<dl\\b[^>]*>|</dl\\s*>|<a\\b([^>]*)>([\\s\\S]*?)</a>",
        RegexOption.IGNORE_CASE,
    )
    private val HREF = Regex("\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val ADD_DATE = Regex("\\badd_date\\s*=\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")

    private fun parseHtml(text: String): List<PortableBookmark> {
        val result = ArrayList<PortableBookmark>()
        val stack = ArrayList<String?>() // one entry per open <DL>: its folder name (null = unnamed)
        var pending: String? = null
        for (m in TOKEN.findAll(text)) {
            val tag = m.value.lowercase()
            when {
                tag.startsWith("<h3") -> pending = decodeEntities(m.groupValues[1].replace(TAGS, "")).trim()
                tag.startsWith("<dl") -> { stack.add(pending); pending = null }
                tag.startsWith("</dl") -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> {
                    val href = HREF.find(m.groupValues[2])
                    val url = decodeEntities((href?.groupValues?.get(1)?.ifEmpty { null } ?: href?.groupValues?.get(2) ?: "").trim())
                    if (url.isEmpty()) continue
                    val seconds = ADD_DATE.find(m.groupValues[2])?.groupValues?.get(1)?.toLongOrNull()
                    val createdAt = if (seconds != null && seconds > 0 && seconds < 8.64e12 / 1000) formatIso(seconds * 1000) else null
                    val title = decodeEntities(m.groupValues[3].replace(TAGS, "")).trim()
                    result.add(PortableBookmark(url, title, createdAt, stack.filterNotNull().filter { it.isNotEmpty() }))
                }
            }
        }
        return result
    }

    /** Parses a Netscape HTML or JSON file; entries are not validated. Throws on malformed JSON. */
    fun parseImport(text: String): List<PortableBookmark> {
        val trimmed = text.removePrefix("\uFEFF").trim()
        return if (trimmed.startsWith("[") || trimmed.startsWith("{")) parseJson(trimmed) else parseHtml(trimmed)
    }
}
