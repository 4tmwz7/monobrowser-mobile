package com.voluene.monobrowser.util

/** Inline run of a rendered line. [url] is set for [Kind.LINK] only (always http/https). */
data class MdSpan(
    val text: String,
    val kind: Kind = Kind.TEXT,
    val url: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    enum class Kind { TEXT, CODE, LINK }
}

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Bullets(val items: List<List<MdSpan>>) : MdBlock
    data class Numbered(val items: List<List<MdSpan>>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * Port of the desktop `markdown-lite.ts`: a tiny, safe Markdown subset. Instead of HTML it produces a block model
 * the UI draws with Compose, so nothing is ever interpreted as markup and only http(s) links are linked.
 * Supported: `#` headings, paragraphs, `-`/`*`/`1.` lists, `>` quotes, `---` rules, fenced code, `` `code` ``,
 * `**bold**`, `*italic*`, `[text](url)` and bare URLs.
 */
object MarkdownLite {
    private val CODE_SPAN = Regex("`([^`\\n]+)`")
    private val LINK = Regex("\\[([^\\]\\n]+)\\]\\((\\S+?)\\)")
    private val BARE_URL = Regex("https?://[^\\s<>\"'`]+")
    private val URL_TRAIL = Regex("[.,;:)\\]]+$")
    private val BOLD = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val ITALIC = Regex("(^|[^*\\w])\\*([^*\\n]+)\\*(?![*\\w])")
    private val SLOT = Regex("\u0000(\\d+)\u0000")
    private val FENCE = Regex("^\\s*```")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val CONTINUATION = Regex("^\\s{2,}\\S")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val URL_IN_TEXT = Regex("https?://[^\\s)<>\\]\"'`]+")
    private val TRAILING_PUNCT = Regex("[.,;:]+$")

    private fun isHttp(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** http(s) URLs in the Markdown (links or bare), unique, in order of appearance. */
    fun extractUrls(markdown: String): List<String> =
        URL_IN_TEXT.findAll(markdown).map { it.value.replace(TRAILING_PUNCT, "") }.distinct().toList()

    /** Inline spans of one line (code spans literal, then links, bare URLs, bold, italic). */
    fun parseInline(raw: String): List<MdSpan> {
        val slots = ArrayList<MdSpan>()
        fun stash(span: MdSpan): String = "\u0000${slots.also { it.add(span) }.size - 1}\u0000"

        var text = CODE_SPAN.replace(raw) { stash(MdSpan(it.groupValues[1], MdSpan.Kind.CODE)) }
        text = LINK.replace(text) {
            val label = it.groupValues[1]
            val url = it.groupValues[2]
            if (isHttp(url)) stash(MdSpan(label, MdSpan.Kind.LINK, url)) else stash(MdSpan("$label ($url)"))
        }
        text = BARE_URL.replace(text) {
            val trail = URL_TRAIL.find(it.value)?.value.orEmpty()
            val clean = if (trail.isEmpty()) it.value else it.value.dropLast(trail.length)
            stash(MdSpan(clean, MdSpan.Kind.LINK, clean)) + trail
        }

        // Bold, then italic, on the remaining text; flags are kept per character so the two can nest.
        val chars = StringBuilder()
        val bold = ArrayList<Boolean>()
        var cursor = 0
        for (m in BOLD.findAll(text)) {
            val content = m.groupValues[1]
            chars.append(text, cursor, m.range.first); repeat(m.range.first - cursor) { bold.add(false) }
            chars.append(content); repeat(content.length) { bold.add(true) }
            cursor = m.range.last + 1
        }
        chars.append(text, cursor, text.length); repeat(text.length - cursor) { bold.add(false) }

        val unbolded = chars.toString()
        val out = StringBuilder()
        val outBold = ArrayList<Boolean>()
        val outItalic = ArrayList<Boolean>()
        cursor = 0
        for (m in ITALIC.findAll(unbolded)) {
            val prefix = m.groupValues[1]
            val content = m.groupValues[2]
            val contentStart = m.range.first + prefix.length + 1
            // everything up to and including the prefix character
            for (i in cursor until m.range.first + prefix.length) { out.append(unbolded[i]); outBold.add(bold[i]); outItalic.add(false) }
            for (i in contentStart until contentStart + content.length) { out.append(unbolded[i]); outBold.add(bold[i]); outItalic.add(true) }
            cursor = m.range.last + 1
        }
        for (i in cursor until unbolded.length) { out.append(unbolded[i]); outBold.add(bold[i]); outItalic.add(false) }

        // Split into runs of equal style and expand the stashed spans.
        val result = ArrayList<MdSpan>()
        val final = out.toString()
        var runStart = 0
        fun flush(end: Int) {
            if (end > runStart) appendExpanded(result, final.substring(runStart, end), outBold[runStart], outItalic[runStart], slots)
            runStart = end
        }
        for (i in 1..final.length) {
            if (i == final.length || outBold[i] != outBold[runStart] || outItalic[i] != outItalic[runStart]) flush(i)
        }
        return result
    }

    private fun appendExpanded(out: MutableList<MdSpan>, run: String, bold: Boolean, italic: Boolean, slots: List<MdSpan>) {
        var cursor = 0
        for (m in SLOT.findAll(run)) {
            if (m.range.first > cursor) out.add(MdSpan(run.substring(cursor, m.range.first), bold = bold, italic = italic))
            slots.getOrNull(m.groupValues[1].toInt())?.let { out.add(it.copy(bold = bold, italic = italic)) }
            cursor = m.range.last + 1
        }
        if (cursor < run.length) out.add(MdSpan(run.substring(cursor), bold = bold, italic = italic))
    }

    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val out = ArrayList<MdBlock>()
        var paragraph = ArrayList<String>()
        var listOrdered = false
        var list: ArrayList<String>? = null
        var quote = ArrayList<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) out.add(MdBlock.Paragraph(parseInline(paragraph.joinToString(" "))))
            paragraph = ArrayList()
        }
        fun flushList() {
            list?.let { items ->
                val parsed = items.map(::parseInline)
                out.add(if (listOrdered) MdBlock.Numbered(parsed) else MdBlock.Bullets(parsed))
            }
            list = null
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) out.add(MdBlock.Quote(parseInline(quote.joinToString(" "))))
            quote = ArrayList()
        }
        fun flushAll() { flushParagraph(); flushList(); flushQuote() }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) {
                flushAll()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !FENCE.containsMatchIn(lines[i])) { code.add(lines[i]); i++ }
                out.add(MdBlock.Code(code.joinToString("\n")))
                i++
                continue
            }
            if (line.isBlank()) { flushAll(); i++; continue }
            val heading = HEADING.find(line)
            if (heading != null) {
                flushAll()
                out.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
                i++
                continue
            }
            if (RULE.matches(line)) { flushAll(); out.add(MdBlock.Rule); i++; continue }
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            if (bullet != null || numbered != null) {
                flushParagraph(); flushQuote()
                val ordered = bullet == null
                if (list != null && listOrdered != ordered) flushList()
                if (list == null) { list = ArrayList(); listOrdered = ordered }
                list!!.add((bullet ?: numbered)!!.groupValues[1])
                i++
                continue
            }
            // Continuation line of a list item (indented text right below it).
            val current = list
            if (current != null && CONTINUATION.containsMatchIn(line)) {
                current[current.lastIndex] = current.last() + " " + line.trim()
                i++
                continue
            }
            val quoted = QUOTE.find(line)
            if (quoted != null) { flushParagraph(); flushList(); quote.add(quoted.groupValues[1]); i++; continue }
            flushList(); flushQuote()
            paragraph.add(line.trim())
            i++
        }
        flushAll()
        return out
    }
}
