package com.voluene.monobrowser.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Kind of a reader block: heading, paragraph, list item, quote, preformatted text. */
enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, QUOTE, CODE }

data class ReaderBlock(val kind: BlockKind, val text: String)

/** A page reduced to its text ("Reader view"). */
data class ReaderArticle(
    val title: String,
    val site: String,
    val byline: String,
    val url: String,
    val blocks: List<ReaderBlock>,
    /** The page's language (`<html lang>`, e.g. `pl` or `en-GB`); empty when unknown. */
    val language: String = "",
) {
    val wordCount: Int get() = blocks.sumOf { b -> b.text.split(WHITESPACE).count { it.isNotEmpty() } }

    /** Reading time at ~220 words a minute, at least one minute. */
    val readingMinutes: Int get() = maxOf(1, (wordCount + 110) / 220)

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Reader view: [SCRIPT] runs in the page (WebView.evaluateJavascript) and returns the main text as JSON; [parse]
 * turns that into a [ReaderArticle] or null when the page does not look like an article. Parsing is pure and
 * unit-tested; the extraction heuristic is a small cousin of Readability (the container with the most paragraph text).
 */
object Reader {
    /** Minimum amount of text (characters) for a page to count as readable. */
    const val MIN_TEXT = 400
    private const val MAX_BLOCKS = 2000

    @Serializable
    private data class RawBlock(val t: String = "p", val x: String = "")

    @Serializable
    private data class RawArticle(
        val title: String = "",
        val site: String = "",
        val byline: String = "",
        val url: String = "",
        val lang: String = "",
        val blocks: List<RawBlock> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * @param raw what evaluateJavascript hands back: the script's string result encoded as a JSON string literal
     * (`"{\"title\":...}"`), or `null`. A plain JSON object is accepted as well.
     */
    fun parse(raw: String?): ReaderArticle? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        val payload = runCatching {
            val element = json.parseToJsonElement(text)
            if (element is JsonPrimitive && element.isString) element.jsonPrimitive.content else text
        }.getOrNull() ?: return null
        val article = runCatching { json.decodeFromString(RawArticle.serializer(), payload) }.getOrNull() ?: return null
        return clean(article)
    }

    private fun kindOf(tag: String): BlockKind = when (tag) {
        "h" -> BlockKind.HEADING
        "li" -> BlockKind.LIST_ITEM
        "q" -> BlockKind.QUOTE
        "pre" -> BlockKind.CODE
        else -> BlockKind.PARAGRAPH
    }

    private fun normalize(text: String) = text.replace(Regex("[\\t\\x0B\\f\\r ]+"), " ").replace(Regex(" *\\n *"), "\n").trim()

    private fun clean(raw: RawArticle): ReaderArticle? {
        val title = normalize(raw.title).replace('\n', ' ')
        val blocks = ArrayList<ReaderBlock>()
        for (block in raw.blocks.take(MAX_BLOCKS)) {
            val kind = kindOf(block.t)
            val text = if (kind == BlockKind.CODE) block.x.trimEnd() else normalize(block.x).replace('\n', ' ')
            if (text.isBlank()) continue
            // The page's own <h1> usually repeats the title shown above the text.
            if (kind == BlockKind.HEADING && blocks.none { it.kind != BlockKind.HEADING } && text.equals(title, ignoreCase = true)) continue
            // Consecutive duplicates (responsive markup often repeats a block).
            if (blocks.lastOrNull()?.let { it.kind == kind && it.text == text } == true) continue
            blocks.add(ReaderBlock(kind, text))
        }
        // Trailing headings have no text under them (end-of-article widgets).
        while (blocks.lastOrNull()?.kind == BlockKind.HEADING) blocks.removeAt(blocks.lastIndex)
        val bodyText = blocks.filter { it.kind != BlockKind.HEADING }.sumOf { it.text.length }
        val paragraphs = blocks.count { it.kind == BlockKind.PARAGRAPH }
        if (bodyText < MIN_TEXT || paragraphs < 2) return null
        return ReaderArticle(
            title = title,
            site = normalize(raw.site).replace('\n', ' '),
            byline = normalize(raw.byline).replace('\n', ' '),
            url = raw.url.trim(),
            language = raw.lang.trim().takeIf { Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$").matches(it) }.orEmpty(),
            blocks = blocks,
        )
    }

    /**
     * Cheap check run after every page load: enough paragraph text for reader view to be worth offering
     * (same threshold idea as [MIN_TEXT], a little stricter to avoid offering it on index pages).
     */
    const val PROBE_SCRIPT: String =
        "(function(){var n=0,c=0,ps=document.getElementsByTagName('p');for(var i=0;i<ps.length&&i<400;i++){" +
            "var l=(ps[i].textContent||'').length;if(l>=80){n+=l;c++}}return c>=3&&n>=1200})()"

    /** Extraction script; evaluates to a JSON string (see [parse]). */
    val SCRIPT: String = """
        (function () {
          // Block text without footnote markers, edit links and scripts.
          function clean(n) {
            var k = n.cloneNode(true);
            var junk = k.querySelectorAll('sup.reference, .mw-editsection, .reference, script, style, noscript, button');
            for (var i = 0; i < junk.length; i++) junk[i].remove();
            return (k.textContent || '').replace(/[ \t\r\n\u00a0]+/g, ' ').trim();
          }
          function meta(sel) { var m = document.querySelector(sel); return m && m.content ? m.content : ''; }
          var doc = document;
          // Every paragraph's text is added to all its ancestors (textContent, so collapsed sections count). The root
          // is found by walking down from <body> while one child still holds 80 % of the paragraph text.
          var acc = new Map(), total = 0;
          var ps = doc.getElementsByTagName('p');
          for (var i = 0; i < ps.length; i++) {
            var len = (ps[i].textContent || '').replace(/\s+/g, ' ').trim().length;
            if (len < 40) continue;
            total += len;
            for (var el = ps[i].parentElement; el; el = el.parentElement) acc.set(el, (acc.get(el) || 0) + len);
          }
          var root = doc.body;
          for (;;) {
            var here = acc.get(root) || 0, next = null;
            for (var c = root.firstElementChild; c && here > 0; c = c.nextElementSibling) {
              if ((acc.get(c) || 0) >= here * 0.8) { next = c; break; }
            }
            if (!next) break;
            root = next;
          }
          // An element holding a good part of the article is never dropped as noise or as hidden.
          function major(c) { return (acc.get(c) || 0) >= Math.max(300, total * 0.25); }
          // Hidden menus and dialogs are skipped; hidden text that matters (collapsed sections) is kept.
          function hidden(c) {
            var style = window.getComputedStyle ? window.getComputedStyle(c) : null;
            return !!style && (style.display === 'none' || style.visibility === 'hidden');
          }
          var blocks = [];
          var SKIP = /^(nav|aside|footer|form|script|style|noscript|button|svg|iframe|select|textarea|figcaption)$/i;
          var NOISE = /(^|[\s_-])(comment|share|social|related|promo|advert|ads?|newsletter|cookie|sidebar|subscribe|paywall|breadcrumb|references?|reflist|navbox|catlinks|footnotes?|infobox)/i;
          function walk(n) {
            for (var c = n.firstElementChild; c; c = c.nextElementSibling) {
              if (blocks.length > $MAX_BLOCKS) return;
              var tag = c.tagName.toLowerCase();
              if (SKIP.test(tag)) continue;
              var cls = (typeof c.className === 'string' ? c.className : '') + ' ' + (c.id || '');
              // Class-name noise applies to containers only: a <p class="h1-related"> is still a paragraph.
              var block = /^(p|h[1-6]|li|blockquote|pre)$/.test(tag);
              if (!major(c) && (hidden(c) || (!block && NOISE.test(cls)))) continue;
              var x;
              if (/^h[1-6]$/.test(tag)) { x = clean(c); if (x) blocks.push({ t: 'h', x: x }); }
              else if (tag === 'p') { x = clean(c); if (x.length > 1) blocks.push({ t: 'p', x: x }); }
              else if (tag === 'li') { x = clean(c); if (x) blocks.push({ t: 'li', x: x }); }
              else if (tag === 'blockquote') { x = clean(c); if (x) blocks.push({ t: 'q', x: x }); }
              else if (tag === 'pre') { x = (c.textContent || '').replace(/\s+$/, ''); if (x) blocks.push({ t: 'pre', x: x }); }
              else walk(c);
            }
          }
          walk(root);
          return JSON.stringify({
            title: meta('meta[property="og:title"]') || doc.title || '',
            site: meta('meta[property="og:site_name"]') || location.hostname.replace(/^www\./, ''),
            byline: meta('meta[name="author"]'),
            lang: doc.documentElement.lang || '',
            url: location.href,
            blocks: blocks
          });
        })()
    """.trimIndent()
}
