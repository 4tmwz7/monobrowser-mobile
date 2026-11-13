package com.voluene.monobrowser.ui.settings

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import com.voluene.monobrowser.settings.SettingsCategory
import com.voluene.monobrowser.settings.SettingsStructure
import com.voluene.monobrowser.ui.browser.MenuIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.SitePermission
import com.voluene.monobrowser.browser.SitePermissionStore
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.settings.NewTabPage
import com.voluene.monobrowser.settings.SearchEngine
import com.voluene.monobrowser.settings.SettingsCodec
import com.voluene.monobrowser.settings.Themes
import com.voluene.monobrowser.ui.common.ChoiceDialog
import com.voluene.monobrowser.ui.common.ConfirmDialog
import com.voluene.monobrowser.ui.common.HairLine
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoCheckbox
import com.voluene.monobrowser.ui.common.MonoDialog
import com.voluene.monobrowser.ui.common.MonoSwitch
import com.voluene.monobrowser.ui.common.ScreenScaffold
import com.voluene.monobrowser.ui.common.TextInputDialog
import com.voluene.monobrowser.ui.theme.hexColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Text typed in the settings search field: rows whose title / description do not match hide themselves. */
internal val LocalSettingsQuery = androidx.compose.runtime.compositionLocalOf { "" }

/** Every word of [query] must appear in the title or description (case and accents ignored). Pure. */
internal fun settingMatches(query: String, title: String, subtitle: String?): Boolean {
    if (query.isBlank()) return true
    val haystack = com.voluene.monobrowser.ui.palette.PaletteEngine.fold("$title ${subtitle.orEmpty()}")
    return com.voluene.monobrowser.ui.palette.PaletteEngine.fold(query).split(' ').filter { it.isNotBlank() }.all { haystack.contains(it) }
}

internal enum class Page { Theme, SiteThemes, Exceptions, Permissions, Licenses, Tips, BarLayout, MenuLayout, StartBackground, StartPicker }

internal const val WEBSITE = "https://monobrowser.vercel.app"

/** Name of the category the row being drawn is listed under; shown on search hits and matched by the search. */
private val LocalSettingsCategoryLabel = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * Settings in two levels: a list of categories ("folders", [SettingsStructure]) with a search over every setting, and the
 * rows of one category on a page pushed over it (sub-pages such as the bar layout are pushed over that). Back goes up one level.
 */
@Composable
fun SettingsScreen(manager: TabManager, onOpenUrl: (String) -> Unit, onClose: () -> Unit) {
    var category by remember { mutableStateOf<SettingsCategory?>(null) }
    var page by remember { mutableStateOf<Page?>(null) }
    var query by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    var syncSetup by remember { mutableStateOf(false) }
    var syncStop by remember { mutableStateOf(false) }
    val settings by manager.settings.collectAsState()
    val env = SettingsEnv(
        manager, settings, onOpenUrl,
        open = { page = it },
        showDialog = { dialog = it },
        showClearData = { clearDialog = true },
        showSyncSetup = { syncSetup = true },
        showSyncStop = { syncStop = true },
        names = rememberSettingsNames(),
    )
    val back = { page = null }
    // The category list stays underneath, so a predictive back gesture on a category reveals it.
    CategoriesPage(env, query, { query = it }, onClose, open = { category = it })
    // Pages slide in from the side and back out (also after a predictive back gesture); a sub-page takes the back
    // gesture from the category page below it.
    com.voluene.monobrowser.ui.common.PushedPage(category, onBack = { category = null }, backEnabled = page == null) { shown ->
        CategoryPage(shown, env) { category = null }
    }
    com.voluene.monobrowser.ui.common.PushedPage(page, onBack = back) { shown ->
        when (shown) {
            Page.Theme -> ThemePage(manager, back)
            Page.SiteThemes -> SiteThemesPage(manager, back)
            Page.Exceptions -> ExceptionsPage(manager, back)
            Page.Permissions -> PermissionsPage(manager, back)
            Page.Licenses -> LicensesPage(back)
            Page.BarLayout -> BarLayoutPage(manager, back)
            Page.MenuLayout -> MenuLayoutPage(manager, back)
            Page.StartBackground -> StartBackgroundPage(manager, back)
            Page.StartPicker -> {
                val data by manager.bookmarks.collectAsState()
                com.voluene.monobrowser.ui.start.StartBookmarksPicker(data, manager::editBookmarks, back)
            }
            Page.Tips -> SubPage(stringResource(R.string.tips_title), back) {
                com.voluene.monobrowser.ui.onboarding.GestureTips(Modifier.padding(20.dp))
            }
        }
    }
    // Dialogs are drawn last: over every page. `currentDialog` reads the LIVE dialog key: ChoiceDialog runs its
    // selection and its dismissal in one handler, so a chain (engine -> custom address, new tab -> homepage)
    // would otherwise see the key captured at composition time and close the dialog it just opened.
    SettingsDialogs(env, dialog, { dialog }, { dialog = null })
    if (clearDialog) ClearDataDialog(manager) { clearDialog = false }
    if (syncSetup) SyncSetupDialog(manager) { syncSetup = false }
    if (syncStop) {
        ConfirmDialog(
            stringResource(R.string.sync_stop), stringResource(R.string.sync_stop_message), stringResource(R.string.sync_stop_confirm),
            onConfirm = manager::stopSync, onDismiss = { syncStop = false },
        )
    }
}

// ---- building blocks ----

@Composable
internal fun Section(title: String) {
    if (LocalSettingsQuery.current.isNotBlank()) return // results are a flat list
    val scheme = MaterialTheme.colorScheme
    Text(
        title.uppercase(), color = scheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val query = LocalSettingsQuery.current
    val category = LocalSettingsCategoryLabel.current
    // The category name is searchable too ("sync", "privacy" list that category's rows).
    if (!settingMatches(query, title, listOfNotNull(subtitle, category).joinToString(" "))) return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // A search hit says where it lives.
            if (query.isNotBlank() && category != null) {
                Text(category.uppercase(), color = scheme.onBackground.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(title, color = scheme.onBackground.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 16.sp)
            if (subtitle != null) {
                Text(subtitle, color = scheme.onBackground.copy(alpha = if (enabled) 0.65f else 0.35f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
    HairLine()
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) { MonoSwitch(checked, onChange) }

@Composable
internal fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    ScreenScaffold(title, onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
    }
}

internal fun formatTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

// ---- categories ----

@Composable
private fun categoryTitle(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.GENERAL -> R.string.settings_cat_general
        SettingsCategory.START_PAGE -> R.string.settings_cat_start
        SettingsCategory.APPEARANCE -> R.string.settings_cat_appearance
        SettingsCategory.TABS_GESTURES -> R.string.settings_cat_tabs
        SettingsCategory.PAGES -> R.string.settings_cat_pages
        SettingsCategory.PRIVACY -> R.string.settings_cat_privacy
        SettingsCategory.SYNC -> R.string.settings_cat_sync
        SettingsCategory.ABOUT -> R.string.settings_cat_about
    },
)

@Composable
private fun categorySummary(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.GENERAL -> R.string.settings_cat_general_hint
        SettingsCategory.START_PAGE -> R.string.settings_cat_start_hint
        SettingsCategory.APPEARANCE -> R.string.settings_cat_appearance_hint
        SettingsCategory.TABS_GESTURES -> R.string.settings_cat_tabs_hint
        SettingsCategory.PAGES -> R.string.settings_cat_pages_hint
        SettingsCategory.PRIVACY -> R.string.settings_cat_privacy_hint
        SettingsCategory.SYNC -> R.string.settings_cat_sync_hint
        SettingsCategory.ABOUT -> R.string.settings_cat_about_hint
    },
)

private fun categoryIcon(category: SettingsCategory): ImageVector = when (category) {
    SettingsCategory.GENERAL -> Icons.Filled.Settings
    SettingsCategory.START_PAGE -> Icons.Filled.Home
    SettingsCategory.APPEARANCE -> MenuIcons.Contrast
    SettingsCategory.TABS_GESTURES -> MenuIcons.Tabs
    SettingsCategory.PAGES -> MenuIcons.Article
    SettingsCategory.PRIVACY -> Icons.Filled.Lock
    SettingsCategory.SYNC -> Icons.Filled.Refresh
    SettingsCategory.ABOUT -> Icons.Filled.Info
}

/**
 * First level: the search field and the category list. While something is typed, the list gives way to the matching
 * rows of EVERY category (each hit names its category; categories whose name matches are offered first).
 */
@Composable
private fun CategoriesPage(env: SettingsEnv, query: String, onQuery: (String) -> Unit, onClose: () -> Unit, open: (SettingsCategory) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ScreenScaffold(stringResource(R.string.settings_title), onClose) {
        com.voluene.monobrowser.ui.common.MonoTextField(
            query, onQuery, stringResource(R.string.settings_search),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (query.isBlank()) {
                SettingsStructure.categories.forEach { category ->
                    CategoryRow(categoryTitle(category), categorySummary(category), categoryIcon(category)) { open(category) }
                }
            } else {
                androidx.compose.runtime.CompositionLocalProvider(LocalSettingsQuery provides query) {
                    SettingsStructure.categories.forEach { category ->
                        val title = categoryTitle(category)
                        val summary = categorySummary(category)
                        if (settingMatches(query, title, null)) CategoryRow(title, summary, categoryIcon(category)) { open(category) }
                    }
                    SettingsStructure.categories.forEach { category ->
                        androidx.compose.runtime.CompositionLocalProvider(LocalSettingsCategoryLabel provides categoryTitle(category)) {
                            SettingsStructure.items(category).forEach { SettingItem(it, env) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onBackground, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = scheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            // One line: the row stays the same height whatever the language; the summaries are kept short enough.
            Text(summary, color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(start = 8.dp).size(22.dp))
    }
    HairLine()
}

/** Second level: the rows of one category. */
@Composable
private fun CategoryPage(category: SettingsCategory, env: SettingsEnv, onBack: () -> Unit) {
    SubPage(categoryTitle(category), onBack) {
        SettingsStructure.items(category).forEach { SettingItem(it, env) }
    }
}

// ---- dialogs of the rows ----

@Composable
private fun SettingsDialogs(env: SettingsEnv, dialog: String?, currentDialog: () -> String?, dismiss: () -> Unit) {
    val settings = env.settings
    val names = env.names
    fun update(transform: (AppSettings) -> AppSettings) = env.update(transform)
    when (dialog) {
        "engine" -> ChoiceDialog(stringResource(R.string.settings_search_engine), names.engines.toList(), settings.searchEngine, { choice ->
            update { it.copy(searchEngine = choice) }
            // Custom needs an address first.
            if (choice == SearchEngine.CUSTOM) env.showDialog("customUrl")
        }, { if (currentDialog() == "engine") dismiss() })
        "startup" -> ChoiceDialog(stringResource(R.string.settings_startup), names.startup.toList(), settings.startupMode, { c -> update { it.copy(startupMode = c) } }, dismiss)
        "newtab" -> ChoiceDialog(stringResource(R.string.settings_newtab_page), names.newTab.toList(), settings.newTabPage, { c ->
            update { it.copy(newTabPage = c) }
            if (c == NewTabPage.HOMEPAGE && settings.homepageUrl.isEmpty()) env.showDialog("homepage")
        }, { if (currentDialog() == "newtab") dismiss() })
        "language" -> ChoiceDialog(stringResource(R.string.settings_language), names.languages.toList(), settings.language, { c -> update { it.copy(language = c) } }, dismiss)
        "toolbar" -> ChoiceDialog(stringResource(R.string.settings_toolbar), names.toolbar.toList(), settings.toolbarPosition, { c -> update { it.copy(toolbarPosition = c) } }, dismiss)
        "linkLongPress" -> ChoiceDialog(stringResource(R.string.settings_link_longpress), names.links.toList(), settings.linkLongPress, { c -> update { it.copy(linkLongPress = c) } }, dismiss)
        "textSize" -> com.voluene.monobrowser.ui.browser.TextSizeDialog(
            zoom = settings.textZoom,
            onChange = { zoom -> update { it.copy(textZoom = zoom) } },
            onDismiss = dismiss,
        )
        "mode" -> ChoiceDialog(stringResource(R.string.settings_mode), names.modes.toList(), settings.themeMode, { c -> update { it.copy(themeMode = c) } }, dismiss)
        "pageMode" -> ChoiceDialog(stringResource(R.string.settings_page_theme), names.modes.toList(), settings.pageThemeMode, { c -> update { it.copy(pageThemeMode = c) } }, dismiss)
        "customUrl" -> TextInputDialog(
            stringResource(R.string.settings_custom_url), settings.customSearchUrl.takeIf { settings.searchEngine == SearchEngine.CUSTOM }.orEmpty(),
            stringResource(R.string.settings_custom_url_hint), stringResource(R.string.dialog_ok),
            { v -> update { it.copy(customSearchUrl = v.trim(), searchEngine = SearchEngine.CUSTOM) } }, dismiss,
            validate = SettingsCodec::isValidSearchUrl, keyboardType = KeyboardType.Uri,
        )
        "homepage" -> TextInputDialog(
            stringResource(R.string.settings_homepage_url), settings.homepageUrl, stringResource(R.string.settings_homepage_hint),
            stringResource(R.string.dialog_ok),
            { v -> update { it.copy(homepageUrl = v.trim(), newTabPage = NewTabPage.HOMEPAGE) } }, dismiss,
            validate = SettingsCodec::isValidHomepage, keyboardType = KeyboardType.Uri,
        )
    }
}

@Composable
private fun SyncSetupDialog(manager: TabManager, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MonoDialog(onDismiss) {
        Text(stringResource(R.string.sync_setup), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.sync_setup_text), color = scheme.onBackground, fontSize = 14.sp, lineHeight = 20.sp)
        MonoButton(stringResource(R.string.sync_create), {
            onDismiss()
            manager.host?.createDocument("monobrowser-sync.json", "application/json") { uri -> uri?.let(manager::useSyncFile) }
        }, Modifier.fillMaxWidth(), inverted = true)
        MonoButton(stringResource(R.string.sync_choose), {
            onDismiss()
            manager.host?.openDocument(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) { uri -> uri?.let(manager::useSyncFile) }
        }, Modifier.fillMaxWidth())
        Text(stringResource(R.string.sync_desktop_hint), color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

// ---- clear browsing data ----

@Composable
private fun ClearDataDialog(manager: TabManager, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    var storage by remember { mutableStateOf(true) }
    MonoDialog(onDismiss) {
        Text(stringResource(R.string.settings_clear_data), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        MonoCheckbox(history, stringResource(R.string.clear_history), { history = it })
        MonoCheckbox(cookies, stringResource(R.string.clear_cookies), { cookies = it })
        MonoCheckbox(cache, stringResource(R.string.clear_cache), { cache = it })
        MonoCheckbox(storage, stringResource(R.string.clear_storage), { storage = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.weight(1f))
            MonoButton(
                stringResource(R.string.clear_confirm),
                { manager.clearBrowsingData(history, cookies, cache, storage); onDismiss() },
                Modifier.weight(1f), inverted = true, enabled = history || cookies || cache || storage,
            )
        }
    }
}

// ---- theme page ----

@Composable
private fun ThemePage(manager: TabManager, onBack: () -> Unit) {
    val settings by manager.settings.collectAsState()
    val scheme = MaterialTheme.colorScheme
    SubPage(stringResource(R.string.settings_theme), onBack) {
        Text(stringResource(R.string.settings_theme_hint), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        ThemeRow(stringResource(R.string.settings_theme_default), null, settings.themeId == Themes.DEFAULT_ID) {
            manager.updateSettings { it.copy(themeId = Themes.DEFAULT_ID) }
        }
        Themes.PRESETS.forEach { preset ->
            ThemeRow(preset.name, preset, settings.themeId == preset.id) { manager.updateSettings { it.copy(themeId = preset.id) } }
        }
    }
}

@Composable
private fun ThemeRow(name: String, preset: com.voluene.monobrowser.settings.ThemePreset?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).border(2.dp, scheme.outline, RectangleShape).padding(3.dp)) {
            if (selected) Box(Modifier.fillMaxSize().background(scheme.onBackground))
        }
        Text(name, color = scheme.onBackground, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(start = 12.dp))
        // Swatch: background / text / accent / danger as flat squares.
        val colors = preset?.tokens?.let { listOf(it.bg, it.fg, it.accent, it.danger) } ?: listOf("#ffffff", "#000000", "#999999", "#333333")
        Row(Modifier.border(1.dp, scheme.outline, RectangleShape)) {
            colors.forEach { Box(Modifier.size(20.dp).background(hexColor(it))) }
        }
    }
    HairLine()
}

// ---- per-site page themes ----

@Composable
private fun SiteThemesPage(manager: TabManager, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sites by manager.siteThemes.state.collectAsState()
    SubPage(stringResource(R.string.settings_site_themes), onBack) {
        Text(stringResource(R.string.settings_site_themes_hint), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        if (sites.isEmpty()) {
            Text(stringResource(R.string.settings_site_themes_empty), color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
        }
        sites.forEach { (host, theme) ->
            SettingRow(host, stringResource(if (theme == com.voluene.monobrowser.settings.SiteTheme.DARK) R.string.page_theme_dark else R.string.page_theme_light), onClick = null) {
                MonoButton(stringResource(R.string.settings_remove), { manager.siteThemes.set(host, null) })
            }
        }
    }
}

// ---- adblock exceptions ----

@Composable
private fun ExceptionsPage(manager: TabManager, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hosts by manager.adBlocker.exceptions.hostsFlow.collectAsState()
    var adding by remember { mutableStateOf(false) }
    SubPage(stringResource(R.string.settings_exceptions), onBack) {
        Text(stringResource(R.string.settings_exceptions_hint), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        MonoButton(stringResource(R.string.settings_exceptions_add), { adding = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        Box(Modifier.height(12.dp))
        if (hosts.isEmpty()) {
            Text(stringResource(R.string.settings_exceptions_empty), color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
        }
        hosts.forEach { host ->
            SettingRow(host, onClick = null) {
                MonoButton(stringResource(R.string.settings_remove), { manager.adBlocker.exceptions.remove(host) })
            }
        }
    }
    if (adding) {
        TextInputDialog(
            stringResource(R.string.settings_exceptions_add), "", stringResource(R.string.settings_exceptions_placeholder), stringResource(R.string.dialog_ok),
            { manager.adBlocker.exceptions.add(it) }, { adding = false },
            validate = { com.voluene.monobrowser.adblock.HostUtils.normalizeHost(it) != null }, keyboardType = KeyboardType.Uri,
        )
    }
}

// ---- site permissions ----

@Composable
private fun PermissionsPage(manager: TabManager, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var version by remember { mutableStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val decisions = remember(version) { manager.sitePermissionList }
    SubPage(stringResource(R.string.settings_permissions), onBack) {
        Text(stringResource(R.string.settings_permissions_page_hint), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        MonoButton(stringResource(R.string.settings_permissions_reset_all), { confirmReset = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = decisions.isNotEmpty())
        Box(Modifier.height(12.dp))
        if (decisions.isEmpty()) {
            Text(stringResource(R.string.settings_permissions_empty), color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
        }
        decisions.forEach { d: SitePermissionStore.Decision ->
            val permission = stringResource(
                when (d.permission) {
                    SitePermission.CAMERA -> R.string.permission_camera
                    SitePermission.MICROPHONE -> R.string.permission_microphone
                    SitePermission.LOCATION -> R.string.permission_location
                },
            )
            val verdict = stringResource(if (d.allowed) R.string.perm_allow else R.string.perm_deny)
            SettingRow(SitePermissionStore.hostOf(d.origin), "$permission  ·  $verdict", onClick = null) {
                MonoButton(stringResource(R.string.settings_permissions_reset), { manager.forgetSitePermission(d.origin, d.permission); version++ })
            }
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            stringResource(R.string.settings_permissions_reset_all), stringResource(R.string.settings_permissions_reset_message),
            stringResource(R.string.settings_permissions_reset), { manager.resetSitePermissions(); version++ }, { confirmReset = false },
        )
    }
}

// ---- licences ----

@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val text = remember { runCatching { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }.getOrDefault("") }
    SubPage(stringResource(R.string.about_licenses), onBack) {
        Text(text, color = scheme.onBackground, fontSize = 13.sp, modifier = Modifier.padding(16.dp), overflow = TextOverflow.Clip)
    }
}
