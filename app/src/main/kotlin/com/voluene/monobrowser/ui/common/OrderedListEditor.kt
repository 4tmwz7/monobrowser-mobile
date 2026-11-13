package com.voluene.monobrowser.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R

/** One row of an [OrderedListEditor]. */
data class EditorEntry(
    val id: String,
    val label: String,
    val enabled: Boolean,
    /** The switch cannot be turned off (the row stays enabled). */
    val locked: Boolean = false,
    /** The row has up / down buttons (disabled rows of a list that only orders its enabled part have none). */
    val movable: Boolean = true,
)

/**
 * A list with a square checkbox and up / down buttons per row, plus "Restore defaults": the one editor behind the
 * address bar layout and the main menu layout. It only reports intents; the caller keeps the data (and decides what
 * a move or toggle means, e.g. re-validating the layout). [header] is shown above the rows (a live preview).
 */
@Composable
fun OrderedListEditor(
    entries: List<EditorEntry>,
    onToggle: (String) -> Unit,
    onMove: (id: String, delta: Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        header()
        entries.forEachIndexed { index, entry ->
            val dim = if (entry.enabled) 1f else 0.5f
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).toggleable(value = entry.enabled, enabled = !entry.locked, role = Role.Checkbox) { onToggle(entry.id) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).border(2.dp, scheme.outline.copy(alpha = if (entry.locked) 0.5f else 1f), RectangleShape).padding(4.dp)) {
                        if (entry.enabled) Box(Modifier.fillMaxSize().background(scheme.onBackground.copy(alpha = if (entry.locked) 0.5f else 1f)))
                    }
                    Text(
                        entry.label, color = scheme.onBackground.copy(alpha = dim), fontSize = 16.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                    )
                }
                if (entry.movable) {
                    MoveButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.editor_move_up), entries.getOrNull(index - 1)?.movable == true) { onMove(entry.id, -1) }
                    MoveButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.editor_move_down), entries.getOrNull(index + 1)?.movable == true) { onMove(entry.id, 1) }
                }
            }
            HairLine()
        }
        Box(Modifier.padding(16.dp)) { MonoButton(stringResource(R.string.editor_restore_defaults), onReset, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun MoveButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.padding(horizontal = 2.dp).size(44.dp).border(1.dp, scheme.outline.copy(alpha = if (enabled) 1f else 0.3f), RectangleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = scheme.onBackground.copy(alpha = if (enabled) 1f else 0.3f)) }
}
