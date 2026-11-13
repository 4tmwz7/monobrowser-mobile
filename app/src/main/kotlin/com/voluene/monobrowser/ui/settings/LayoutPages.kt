package com.voluene.monobrowser.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.settings.BarItem
import com.voluene.monobrowser.settings.BarLayout
import com.voluene.monobrowser.settings.MenuId
import com.voluene.monobrowser.settings.MenuLayout
import com.voluene.monobrowser.ui.browser.MenuIcons
import com.voluene.monobrowser.ui.common.EditorEntry
import com.voluene.monobrowser.ui.common.OrderedListEditor
import com.voluene.monobrowser.ui.common.ScreenScaffold

@Composable
internal fun barItemLabel(item: BarItem): String = stringResource(
    when (item) {
        BarItem.BACK -> R.string.action_back
        BarItem.FORWARD -> R.string.action_forward
        BarItem.ADDRESS -> R.string.bar_item_address
        BarItem.RELOAD -> R.string.action_reload
        BarItem.MENU -> R.string.action_menu
        BarItem.TABS -> R.string.bar_item_tabs
        BarItem.NEW_TAB -> R.string.tab_new
        BarItem.BOOKMARKS -> R.string.menu_bookmarks
        BarItem.BOOKMARK -> R.string.menu_bookmark_add
        BarItem.SHARE -> R.string.menu_share
    },
)

@Composable
internal fun menuItemLabel(id: MenuId): String = stringResource(
    when (id) {
        MenuId.NEW_TAB -> R.string.tab_new
        MenuId.NEW_PRIVATE_TAB -> R.string.menu_new_private_short
        MenuId.BOOKMARKS -> R.string.menu_bookmarks
        MenuId.BOOKMARK_TOGGLE -> R.string.menu_bookmark_add
        MenuId.HISTORY -> R.string.menu_history
        MenuId.PICTURE_IN_PICTURE -> R.string.menu_pip
        MenuId.SHARE -> R.string.menu_share
        MenuId.SETTINGS -> R.string.menu_settings
        MenuId.FIND -> R.string.menu_find
        MenuId.DOWNLOADS -> R.string.menu_downloads
        MenuId.READER -> R.string.menu_reader
        MenuId.REOPEN_CLOSED -> R.string.menu_reopen_short
        MenuId.NOTES -> R.string.menu_notes
        MenuId.NOTE_ADD -> R.string.menu_note_add_short
        MenuId.DESKTOP_SITE -> R.string.menu_desktop_site
        MenuId.PAGE_THEME -> R.string.menu_page_theme
    },
)

/** Settings -> Address bar: which elements the bar shows and in what order (live preview on top). */
@Composable
internal fun BarLayoutPage(manager: TabManager, onBack: () -> Unit) {
    val settings by manager.settings.collectAsState()
    val layout = settings.barLayout
    val tabs by manager.tabs.collectAsState()
    val workspace by manager.currentWorkspaceId.collectAsState()
    val tabCount = tabs.count { it.workspaceId == workspace }.coerceAtLeast(1)
    val entries = layout.map { EditorEntry(it.name, barItemLabel(it), enabled = true, locked = it.mandatory) } +
        BarLayout.available(layout).map { EditorEntry(it.name, barItemLabel(it), enabled = false, movable = false) }
    fun set(next: List<BarItem>) = manager.updateSettings { it.copy(barItems = BarLayout.ids(next)) }
    ScreenScaffold(stringResource(R.string.settings_bar_layout), onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            OrderedListEditor(
                entries = entries,
                onToggle = { id ->
                    val item = BarItem.valueOf(id)
                    if (item in layout) set(BarLayout.remove(layout, item)) else set(BarLayout.add(layout, item))
                },
                onMove = { id, delta -> set(BarLayout.move(layout, layout.indexOf(BarItem.valueOf(id)), delta)) },
                onReset = { set(BarLayout.DEFAULT) },
                header = {
                    BarPreview(layout, tabCount)
                    Text(
                        stringResource(R.string.settings_bar_layout_hint, BarLayout.MAX_BUTTONS), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                        fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
            )
        }
    }
}

/** The bar as it will look (mono boxes with the elements' icons; the address is the wide field). */
@Composable
private fun BarPreview(layout: List<BarItem>, tabCount: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(16.dp).border(1.dp, scheme.outline, RectangleShape).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        layout.forEach { item ->
            when (item) {
                BarItem.ADDRESS -> Box(Modifier.weight(1f).padding(horizontal = 4.dp).height(26.dp).border(1.dp, scheme.onBackground, RectangleShape))
                BarItem.TABS -> Box(Modifier.width(30.dp).padding(horizontal = 3.dp).size(22.dp).border(1.dp, scheme.onBackground, RectangleShape), contentAlignment = Alignment.Center) {
                    Text(tabCount.toString(), color = scheme.onBackground, fontSize = 11.sp)
                }
                else -> Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    Icon(previewIcon(item), contentDescription = null, tint = scheme.onBackground, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun previewIcon(item: BarItem) = when (item) {
    BarItem.BACK -> Icons.AutoMirrored.Filled.ArrowBack
    BarItem.FORWARD -> Icons.AutoMirrored.Filled.ArrowForward
    BarItem.RELOAD -> Icons.Filled.Refresh
    BarItem.MENU -> Icons.Filled.MoreVert
    BarItem.NEW_TAB -> Icons.Filled.Add
    BarItem.BOOKMARKS -> MenuIcons.Bookmark
    BarItem.BOOKMARK -> Icons.Filled.Star
    BarItem.SHARE -> Icons.Filled.Share
    BarItem.ADDRESS, BarItem.TABS -> Icons.Filled.MoreVert
}

/** Settings -> Menu: visibility and order of the main menu's entries (the same editor as the address bar). */
@Composable
internal fun MenuLayoutPage(manager: TabManager, onBack: () -> Unit) {
    val settings by manager.settings.collectAsState()
    val hidden = MenuLayout.hidden(settings.menuHidden)
    val entries = MenuLayout.order(settings.menuOrder).map {
        EditorEntry(it.name, menuItemLabel(it), enabled = it !in hidden, locked = it.mandatory)
    }
    ScreenScaffold(stringResource(R.string.settings_menu_layout), onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            OrderedListEditor(
                entries = entries,
                onToggle = { id -> manager.updateSettings { it.copy(menuHidden = MenuLayout.toggle(it.menuHidden, MenuId.valueOf(id))) } },
                onMove = { id, delta -> manager.updateSettings { it.copy(menuOrder = MenuLayout.move(it.menuOrder, MenuId.valueOf(id), delta)) } },
                onReset = { manager.updateSettings { it.copy(menuOrder = emptyList(), menuHidden = emptyList()) } },
                header = {
                    Text(
                        stringResource(R.string.settings_menu_layout_hint), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                        fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                },
            )
        }
    }
}

/** Settings -> Start page background: choose a photo or an address, remove, veil strength and greyscale. */
@Composable
internal fun StartBackgroundPage(manager: TabManager, onBack: () -> Unit) {
    val settings by manager.settings.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var urlDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val preview by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, settings.startBackground) {
        value = if (settings.startBackground > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.startBackground.load(settings.startBackground) } else null
    }
    fun report(result: com.voluene.monobrowser.browser.BackgroundResult) {
        busy = false
        val text = when (result) {
            com.voluene.monobrowser.browser.BackgroundResult.OK -> R.string.bg_saved
            com.voluene.monobrowser.browser.BackgroundResult.INVALID_URL -> R.string.bg_err_url
            com.voluene.monobrowser.browser.BackgroundResult.DOWNLOAD_FAILED -> R.string.bg_err_download
            com.voluene.monobrowser.browser.BackgroundResult.TOO_LARGE -> R.string.bg_err_large
            com.voluene.monobrowser.browser.BackgroundResult.NOT_AN_IMAGE -> R.string.bg_err_image
        }
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
    ScreenScaffold(stringResource(R.string.settings_start_background), onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
            Box(Modifier.fillMaxWidth().height(160.dp).border(1.dp, scheme.outline, RectangleShape), contentAlignment = Alignment.Center) {
                val image = preview
                if (image != null) {
                    androidx.compose.foundation.Image(
                        image, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        colorFilter = if (settings.startBackgroundGrayscale) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = com.voluene.monobrowser.settings.StartBackground.overlayAlpha(settings.startBackgroundDim))))
                } else {
                    Text(stringResource(if (busy) R.string.bg_working else R.string.settings_start_background_none), color = scheme.onBackground.copy(alpha = 0.6f))
                }
            }
            Text(stringResource(R.string.bg_hint), color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            com.voluene.monobrowser.ui.common.MonoButton(stringResource(R.string.bg_choose_photo), {
                val host = manager.host ?: return@MonoButton
                host.pickImage { uri ->
                    if (uri != null) { busy = true; manager.setStartBackgroundFromUri(uri, ::report) }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            Box(Modifier.height(8.dp))
            com.voluene.monobrowser.ui.common.MonoButton(stringResource(R.string.bg_enter_url), { urlDialog = true }, Modifier.fillMaxWidth(), enabled = !busy)
            if (settings.startBackground > 0) {
                Box(Modifier.height(8.dp))
                com.voluene.monobrowser.ui.common.MonoButton(stringResource(R.string.bg_remove), { manager.removeStartBackground() }, Modifier.fillMaxWidth(), enabled = !busy)
                Text(stringResource(R.string.bg_dim), color = scheme.onBackground, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    val labels = listOf(R.string.bg_dim_0, R.string.bg_dim_1, R.string.bg_dim_2, R.string.bg_dim_3)
                    labels.forEachIndexed { index, label ->
                        com.voluene.monobrowser.ui.common.MonoButton(
                            stringResource(label), { manager.updateSettings { it.copy(startBackgroundDim = index) } }, Modifier.weight(1f),
                            inverted = settings.startBackgroundDim == index, horizontalPadding = 4.dp,
                        )
                    }
                }
                com.voluene.monobrowser.ui.common.MonoCheckbox(
                    settings.startBackgroundGrayscale, stringResource(R.string.bg_gray),
                    { v -> manager.updateSettings { it.copy(startBackgroundGrayscale = v) } }, Modifier.padding(top = 12.dp),
                )
            }
        }
    }
    if (urlDialog) {
        com.voluene.monobrowser.ui.common.TextInputDialog(
            stringResource(R.string.bg_url_title), "", stringResource(R.string.bg_url_field), stringResource(R.string.dialog_ok),
            { text -> busy = true; manager.setStartBackgroundFromUrl(text, ::report) }, { urlDialog = false },
        )
    }
}
