package com.voluene.monobrowser.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.util.MdBlock
import com.voluene.monobrowser.util.MdSpan
import com.voluene.monobrowser.util.MarkdownLite

/** Draws [MarkdownLite] output: monochrome, rectangular; links call [onLink] (always http/https). */
@Composable
fun MarkdownView(markdown: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val blocks = remember(markdown) { MarkdownLite.parse(markdown) }
    val fg = scheme.onBackground
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    annotated(block.spans, onLink, scheme.surfaceVariant), color = fg, fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> 15.sp },
                )
                is MdBlock.Paragraph -> Text(annotated(block.spans, onLink, scheme.surfaceVariant), color = fg, fontSize = 15.sp)
                is MdBlock.Bullets -> block.items.forEach { Item("▪", it, onLink, scheme.surfaceVariant) }
                is MdBlock.Numbered -> block.items.forEachIndexed { index, item -> Item("${index + 1}.", item, onLink, scheme.surfaceVariant) }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.outline))
                    Text(
                        annotated(block.spans, onLink, scheme.surfaceVariant), color = fg.copy(alpha = 0.8f), fontSize = 15.sp,
                        fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().background(scheme.surfaceVariant).border(1.dp, scheme.outlineVariant, RectangleShape)
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                ) { Text(block.text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false) }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            }
        }
    }
}

@Composable
private fun Item(marker: String, spans: List<MdSpan>, onLink: (String) -> Unit, codeBackground: Color) {
    val fg = MaterialTheme.colorScheme.onBackground
    Row {
        Text(marker, color = fg, fontSize = 15.sp, modifier = Modifier.width(24.dp))
        Text(annotated(spans, onLink, codeBackground), color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

private fun annotated(spans: List<MdSpan>, onLink: (String) -> Unit, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val base = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
        )
        when (span.kind) {
            MdSpan.Kind.TEXT -> withStyle(base) { append(span.text) }
            MdSpan.Kind.CODE -> withStyle(base.merge(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground))) { append(span.text) }
            MdSpan.Kind.LINK -> {
                val url = span.url.orEmpty()
                val link = LinkAnnotation.Clickable(
                    tag = url,
                    styles = TextLinkStyles(base.merge(SpanStyle(textDecoration = TextDecoration.Underline))),
                ) { onLink(url) }
                withLink(link) { append(span.text) }
            }
        }
    }
}
