package com.voluene.monobrowser.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.reader.BlockKind
import com.voluene.monobrowser.reader.ReaderArticle
import com.voluene.monobrowser.reader.ReaderBlock
import com.voluene.monobrowser.settings.AppSettings

/**
 * Reader view: the article's text only, in one column with a comfortable measure. The header holds text size
 * (A- / A+), serif/sans and close; a thin line under it shows how far you have read. Text is selectable.
 */
@Composable
fun ReaderScreen(
    article: ReaderArticle,
    fontSize: Int,
    serif: Boolean,
    onFontSize: (Int) -> Unit,
    onSerif: (Boolean) -> Unit,
    onClose: () -> Unit,
    /** Saved in the reading list (offline); null hides the button. */
    saved: Boolean? = null,
    onToggleSave: () -> Unit = {},
    /** Opening a saved article offline: the site label opens the original page. */
    onOpenOriginal: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = scheme.onBackground
    val family = if (serif) FontFamily.Serif else FontFamily.SansSerif
    val list = rememberLazyListState()
    val total = article.blocks.size + 1
    val progress by remember(total) {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf 0f
            if (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset) 1f
            else (list.firstVisibleItemIndex.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                article.site.ifBlank { stringResource(R.string.menu_reader) }, color = fg.copy(alpha = 0.7f), fontSize = 13.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(
                    if (onOpenOriginal != null) Modifier.clickable(onClickLabel = stringResource(R.string.reader_original), onClick = onOpenOriginal) else Modifier,
                ),
            )
            if (saved != null) {
                IconButton(onClick = onToggleSave) {
                    Icon(
                        if (saved) com.voluene.monobrowser.ui.browser.MenuIcons.Bookmark else com.voluene.monobrowser.ui.browser.MenuIcons.BookmarkOutline,
                        stringResource(if (saved) R.string.reading_remove else R.string.reading_save), tint = fg,
                    )
                }
            }
            ToolButton("A−", stringResource(R.string.reader_smaller), fontSize > AppSettings.READER_FONT_RANGE.first) { onFontSize(fontSize - 2) }
            ToolButton("A+", stringResource(R.string.reader_larger), fontSize < AppSettings.READER_FONT_RANGE.last) { onFontSize(fontSize + 2) }
            ToolButton(
                "Aa", stringResource(if (serif) R.string.reader_font_sans else R.string.reader_font_serif), true,
                fontFamily = if (serif) FontFamily.SansSerif else FontFamily.Serif,
            ) { onSerif(!serif) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.reader_close), tint = fg) }
        }
        // Reading progress.
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(fg))
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                item(key = "head") {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)) {
                        Text(
                            article.title, color = fg, fontFamily = family, fontWeight = FontWeight.Bold,
                            fontSize = (fontSize * 1.45f).sp, lineHeight = (fontSize * 1.85f).sp,
                        )
                        val meta = listOfNotNull(
                            article.byline.takeIf { it.isNotBlank() },
                            stringResource(R.string.reader_minutes, article.readingMinutes),
                        ).joinToString("  ·  ")
                        Text(meta, color = fg.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                        Box(Modifier.padding(top = 20.dp).width(48.dp).height(2.dp).background(fg))
                    }
                }
                itemsIndexed(article.blocks, key = { index, _ -> index }) { _, block ->
                    Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                        Block(block, fontSize, family)
                    }
                }
                item(key = "end") { Box(Modifier.navigationBarsPadding().height(48.dp)) }
            }
        }
    }
}

@Composable
private fun Block(block: ReaderBlock, fontSize: Int, family: FontFamily) {
    val scheme = MaterialTheme.colorScheme
    val fg = scheme.onBackground
    val body = fontSize.sp
    val line = (fontSize * 1.6f).sp
    when (block.kind) {
        BlockKind.HEADING -> Text(
            block.text, color = fg, fontFamily = family, fontWeight = FontWeight.Bold,
            fontSize = (fontSize * 1.2f).sp, lineHeight = (fontSize * 1.5f).sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        BlockKind.PARAGRAPH -> Text(
            block.text, color = fg, fontFamily = family, fontSize = body, lineHeight = line, modifier = Modifier.padding(vertical = 8.dp),
        )
        BlockKind.LIST_ITEM -> Row(Modifier.padding(vertical = 4.dp)) {
            // Square bullet, centred on the first line.
            Box(Modifier.padding(top = (fontSize * 0.65f).dp, end = 12.dp).size(5.dp).background(fg))
            Text(block.text, color = fg, fontFamily = family, fontSize = body, lineHeight = line)
        }
        BlockKind.QUOTE -> Row(Modifier.padding(vertical = 8.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(fg))
            Text(
                block.text, color = fg.copy(alpha = 0.8f), fontFamily = family, fontSize = body, lineHeight = line,
                fontWeight = FontWeight.Normal, modifier = Modifier.padding(start = 14.dp),
            )
        }
        BlockKind.CODE -> Text(
            block.text, color = fg, fontFamily = FontFamily.Monospace, fontSize = (fontSize * 0.8f).sp, lineHeight = (fontSize * 1.2f).sp,
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().background(scheme.surfaceVariant)
                .border(1.dp, scheme.outlineVariant, RectangleShape).padding(12.dp),
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    description: String,
    enabled: Boolean,
    fontFamily: FontFamily = FontFamily.SansSerif,
    onClick: () -> Unit,
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Box(
        Modifier.padding(horizontal = 2.dp).size(width = 44.dp, height = 36.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily)
    }
}
