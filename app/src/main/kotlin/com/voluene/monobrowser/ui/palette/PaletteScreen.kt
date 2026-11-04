package com.voluene.monobrowser.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed interface PaletteRow {
    data class Header(val section: PaletteSection) : PaletteRow
    data class Item(val result: PaletteResult, val index: Int) : PaletteRow
}

/**
 * Full-screen command palette: one input; results grouped as Go/Search, open tabs, bookmarks, history and
 * actions ([PaletteEngine]). The keyboard "Go" key runs the first result. With the toolbar at the bottom the
 * input is at the bottom and the list grows upwards (first result next to the input); with the toolbar on
 * top it is the other way round.
 */
@Composable
fun PaletteScreen(
    initialText: String,
    toolbarTop: Boolean,
    tabs: List<PaletteTab>,
    bookmarks: List<PaletteLink>,
    actions: List<PaletteAction>,
    historyLookup: suspend (String) -> List<PaletteLink>,
    /** Search suggestions of the engine; null = off (setting, private tab, custom engine). */
    suggestionLookup: (suspend (String) -> List<String>)?,
    onResult: (PaletteResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var field by remember { mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length))) }
    var history by remember { mutableStateOf<List<PaletteLink>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val query = field.text

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        // Commands do not search history; an empty field asks for recent pages right away.
        if (PaletteEngine.isCommand(query)) { history = emptyList(); return@LaunchedEffect }
        if (query.isNotBlank()) delay(80) // debounce typing
        history = withContext(Dispatchers.IO) { historyLookup(query.trim()) }
    }

    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(query) {
        val lookup = suggestionLookup
        if (lookup == null || query.isBlank() || PaletteEngine.isCommand(query) || !PaletteEngine.isSearchInput(query)) { suggestions = emptyList(); return@LaunchedEffect }
        delay(150) // debounce: only ask once typing pauses
        suggestions = lookup(query.trim())
    }
    val results = remember(query, history, tabs, bookmarks, actions, suggestions) {
        PaletteEngine.addressBar(query, tabs, bookmarks, history, actions, suggestions)
    }
    // "Refine": the suggestion goes into the field so it can be extended.
    val refine: (String) -> Unit = { text -> field = TextFieldValue("$text ", TextRange(text.length + 1)) }
    val rows = remember(results, toolbarTop) { buildRows(results, headersAfterItems = !toolbarTop) }

    val input: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().background(scheme.background).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close), tint = scheme.onBackground)
            }
            Box(Modifier.weight(1f).height(44.dp).border(1.dp, scheme.outline, RectangleShape).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.onBackground),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(onResult) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.palette_hint), color = scheme.onBackground.copy(alpha = 0.5f), fontSize = 16.sp, maxLines = 1)
                        inner()
                    },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { field = TextFieldValue("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.palette_clear), tint = scheme.onBackground)
                }
            }
        }
    }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(modifier.fillMaxWidth(), reverseLayout = !toolbarTop) {
            items(rows, key = { row ->
                when (row) { is PaletteRow.Header -> "h-${row.section}"; is PaletteRow.Item -> "i-${row.index}" }
            }) { row ->
                when (row) {
                    is PaletteRow.Header -> SectionLabel(row.section)
                    is PaletteRow.Item -> ResultRow(row.result, first = row.index == 0, onRefine = refine) { onResult(row.result) }
                }
            }
            // The edge between the results and the page (after the last row, whichever way the list grows).
            if (rows.isNotEmpty()) item(key = "edge") { Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline)) }
        }
    }

    // Only the field and the results are opaque: the page stays visible around them, and a tap on it closes.
    val outside = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f))
            .clickable(interactionSource = outside, indication = null, onClick = onDismiss)
            .then(if (toolbarTop) Modifier.statusBarsPadding() else Modifier.statusBarsPadding().navigationBarsPadding().imePadding()),
    ) {
        if (toolbarTop) {
            input()
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            list(Modifier.weight(1f).navigationBarsPadding().imePadding())
        } else {
            list(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            input()
        }
    }
}

private fun buildRows(results: List<PaletteResult>, headersAfterItems: Boolean): List<PaletteRow> {
    val rows = ArrayList<PaletteRow>()
    var i = 0
    while (i < results.size) {
        val section = results[i].section()
        var j = i
        while (j < results.size && results[j].section() == section) j++
        val items = (i until j).map { PaletteRow.Item(results[it], it) }
        // Go/Search needs no header. In the reversed (bottom) layout the header goes after its items so it is drawn above them.
        if (section == PaletteSection.GO) rows.addAll(items)
        else if (headersAfterItems) { rows.addAll(items); rows.add(PaletteRow.Header(section)) }
        else { rows.add(PaletteRow.Header(section)); rows.addAll(items) }
        i = j
    }
    return rows
}

@Composable
private fun SectionLabel(section: PaletteSection) {
    val scheme = MaterialTheme.colorScheme
    val label = when (section) {
        PaletteSection.GO -> R.string.palette_section_go
        PaletteSection.TABS -> R.string.palette_section_tabs
        PaletteSection.BOOKMARKS -> R.string.palette_section_bookmarks
        PaletteSection.HISTORY -> R.string.palette_section_history
        PaletteSection.ACTIONS -> R.string.palette_section_actions
    }
    Text(
        stringResource(label).uppercase(), color = scheme.onBackground.copy(alpha = 0.6f), fontSize = 11.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ResultRow(result: PaletteResult, first: Boolean, onRefine: (String) -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (title, subtitle) = when (result) {
        is PaletteResult.Go ->
            stringResource(if (result.isSearch) R.string.palette_search_for else R.string.palette_go_to, result.input) to ""
        is PaletteResult.Suggestion -> result.text to ""
        is PaletteResult.OpenTab -> result.tab.title.ifBlank { UrlUtils.shortUrlLabel(result.tab.url).ifBlank { stringResource(R.string.tab_untitled) } } to
            listOfNotNull(
                result.tab.workspace.takeIf { it.isNotBlank() },
                stringResource(R.string.tab_private_marker).takeIf { result.tab.isPrivate },
                UrlUtils.shortUrlLabel(result.tab.url).takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
        is PaletteResult.Bookmark -> result.link.title.ifBlank { UrlUtils.shortUrlLabel(result.link.url) } to UrlUtils.shortUrlLabel(result.link.url)
        is PaletteResult.History -> result.link.title.ifBlank { UrlUtils.shortUrlLabel(result.link.url) } to UrlUtils.shortUrlLabel(result.link.url)
        is PaletteResult.Run -> result.action.label to
            listOf(result.action.hint, "/" + PaletteEngine.slug(result.action.label)).filter { it.isNotBlank() }.joinToString("  \u00b7  ")
    }
    Row(
        Modifier.fillMaxWidth().background(if (first) scheme.surfaceVariant else scheme.background).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Square marker: filled for the row that the Go key executes.
        Box(Modifier.size(8.dp).border(1.dp, scheme.onBackground, RectangleShape).background(if (first) scheme.onBackground else scheme.background))
        Column(Modifier.weight(1f)) {
            Text(title, color = scheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (result is PaletteResult.Suggestion) {
            val label = stringResource(R.string.palette_refine, result.text)
            Box(
                Modifier.size(36.dp).clickable(onClickLabel = label) { onRefine(result.text) },
                contentAlignment = Alignment.Center,
            ) { Text("\u2196", color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 18.sp) }
        }
    }
}
