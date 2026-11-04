package com.voluene.monobrowser.ui.browser

import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import androidx.compose.ui.draw.clip
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.ui.common.Scrim

/**
 * One row of the bottom-sheet menu. To extend the menu (Bookmarks, Settings, ...) add an entry to the list
 * built in `BrowserScreen`; nothing else changes.
 */
data class MenuEntry(
    @param:StringRes val label: Int,
    val enabled: Boolean = true,
    val args: List<Any> = emptyList(),
    val onClick: () -> Unit,
)

/** Monochrome bottom sheet listing [entries]; tapping outside or an entry closes it. [header] is an info line (not clickable). */
@Composable
fun BrowserMenu(entries: List<MenuEntry>, onDismiss: () -> Unit, header: String? = null) {
    val scheme = MaterialTheme.colorScheme
    // The sheet's bottom corners follow the screen's rounded corners.
    val corners = rememberScreenAwareShape()
    Scrim(onDismiss) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(corners.modifier).clip(corners.shape).background(scheme.background, corners.shape).border(1.dp, scheme.outline, corners.shape)
                .clickable(enabled = false) {}.navigationBarsPadding(),
        ) {
            if (header != null) {
                Text(
                    header, color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                Text(
                    stringResource(entry.label, *entry.args.toTypedArray()),
                    color = scheme.onBackground.copy(alpha = if (entry.enabled) 1f else 0.35f),
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = entry.enabled) { onDismiss(); entry.onClick() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            }
        }
    }
}

/** A tile of the main menu grid. */
data class MenuTile(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val enabled: Boolean = true,
    /** A toggle that is on (desktop site, bookmarked page): drawn inverted. */
    val active: Boolean = false,
    val onClick: () -> Unit,
)

/** Info row of the main menu: the blocked counter and an optional toggle button (adblock on this site). */
data class MenuInfo(val text: String, val toggleLabel: String? = null, val toggleEnabled: Boolean = true, val onToggle: () -> Unit = {})

/**
 * Main menu: a compact bottom sheet with an info row and a grid of icon + label tiles ([columns] per row), sized to its
 * content so it fits a phone screen without scrolling; it only scrolls (last resort) on very small screens.
 */
@Composable
fun BrowserMenuGrid(tiles: List<MenuTile>, info: MenuInfo?, onDismiss: () -> Unit, columns: Int = 4) {
    val scheme = MaterialTheme.colorScheme
    // The sheet's bottom corners follow the screen's rounded corners.
    val corners = rememberScreenAwareShape()
    Scrim(onDismiss) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(corners.modifier).clip(corners.shape).background(scheme.background, corners.shape).border(1.dp, scheme.outline, corners.shape)
                .clickable(enabled = false) {}.navigationBarsPadding(),
        ) {
            if (info != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        info.text, color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    if (info.toggleLabel != null) {
                        Text(
                            info.toggleLabel, color = scheme.onBackground.copy(alpha = if (info.toggleEnabled) 1f else 0.35f),
                            fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.border(1.dp, scheme.outline, RectangleShape)
                                .clickable(enabled = info.toggleEnabled) { onDismiss(); info.onToggle() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            }
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                tiles.chunked(columns).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                    Row(Modifier.fillMaxWidth().height(80.dp)) {
                        for (i in 0 until columns) {
                            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(scheme.outlineVariant))
                            val tile = row.getOrNull(i)
                            if (tile == null) {
                                Box(Modifier.weight(1f).fillMaxHeight())
                            } else {
                                val base = if (tile.active) scheme.background else scheme.onBackground
                                val color = base.copy(alpha = if (tile.enabled) 1f else 0.35f)
                                Column(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .then(if (tile.active) Modifier.background(scheme.onBackground) else Modifier)
                                        .clickable(enabled = tile.enabled) { onDismiss(); tile.onClick() }
                                        .padding(horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(tile.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                                    Text(
                                        stringResource(tile.label), color = color, fontSize = 11.sp, textAlign = TextAlign.Center,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
