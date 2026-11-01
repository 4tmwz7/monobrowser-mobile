package com.voluene.monobrowser.notes

/** Pure helpers for note text (unit-tested). */
object NoteText {
    const val MAX_CHARS = 200_000
    private const val MAX_TITLE = 80
    private const val MAX_PREVIEW = 120

    private val LEADING_MARKUP = Regex("^\\s*(#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)+")

    /** First non-blank line without leading Markdown markers; empty for a blank note. */
    fun title(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") } ?: return ""
        return line.replace(LEADING_MARKUP, "").trim().take(MAX_TITLE)
    }

    /** The first content after the title line, flattened to one line. */
    fun preview(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.drop(1).joinToString(" ").take(MAX_PREVIEW)
    }

    /** Appends a link to a page as a list item, keeping the file tidy (single trailing newline). */
    fun appendPage(existing: String, pageTitle: String, url: String): String {
        val label = pageTitle.replace(Regex("\\s+"), " ").trim().replace('[', '(').replace(']', ')')
        val item = if (label.isBlank() || label == url) "- $url" else "- [$label]($url)"
        val base = existing.trimEnd('\n', ' ', '\t')
        return if (base.isEmpty()) "$item\n" else "$base\n$item\n"
    }

    fun clamp(text: String): String = if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text
}
