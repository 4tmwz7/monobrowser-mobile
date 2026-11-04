package com.voluene.monobrowser.ui.settings

import com.voluene.monobrowser.ui.common.PredictiveBackContainer
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
import androidx.compose.material3.MaterialTheme
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
import com.voluene.monobrowser.ui.update.UpdateNotesSheet
import com.voluene.monobrowser.adblock.ListsState as State
import com.voluene.monobrowser.browser.SitePermission
import com.voluene.monobrowser.browser.SitePermissionStore
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.settings.AppLanguage
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.settings.NewTabPage
import com.voluene.monobrowser.settings.SearchEngine
import com.voluene.monobrowser.search.SearchSuggestions
import com.voluene.monobrowser.settings.SettingsCodec
import com.voluene.monobrowser.settings.StartupMode
import com.voluene.monobrowser.settings.ThemeMode
import com.voluene.monobrowser.settings.Themes
import com.voluene.monobrowser.settings.LinkLongPress
import com.voluene.monobrowser.settings.ToolbarPosition
import com.voluene.monobrowser.settings.WebDarkMode
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
import com.voluene.monobrowser.ui.update.updateStatusText
import com.voluene.monobrowser.update.UpdatePhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Text typed in the settings search field: rows whose title / description do not match hide themselves. */
private val LocalSettingsQuery = androidx.compose.runtime.compositionLocalOf { "" }

/** Every word of [query] must appear in the title or description (case and accents ignored). Pure. */
internal fun settingMatches(query: String, title: String, subtitle: String?): Boolean {
    if (query.isBlank()) return true
    val haystack = com.voluene.monobrowser.ui.palette.PaletteEngine.fold("$title ${subtitle.orEmpty()}")
    return com.voluene.monobrowser.ui.palette.PaletteEngine.fold(query).split(' ').filter { it.isNotBlank() }.all { haystack.contains(it) }
}

private enum class Page { Main, Theme, Exceptions, Permissions, Licenses, Tips }

private const val WEBSITE = "https://monobrowser.vercel.app"

/** Settings in sections mirroring the desktop app (General, Appearance, Privacy & security, Adblock, About). */
@Composable
fun SettingsScreen(manager: TabManager, onOpenUrl: (String) -> Unit, onClose: () -> Unit) {
    var page by remember { mutableStateOf(Page.Main) }
    val back = { page = Page.Main }
    // The main page stays underneath, so a predictive back gesture on a sub-page reveals it.
    MainPage(manager, onOpenUrl, onClose, open = { page = it })
    // Sub-pages slide in from the side and back out (also after a predictive back gesture).
    com.voluene.monobrowser.ui.common.PushedPage(page.takeIf { it != Page.Main }, onBack = back) { shown ->
        when (shown) {
            Page.Main -> Unit
            Page.Theme -> ThemePage(manager, back)
            Page.Exceptions -> ExceptionsPage(manager, back)
            Page.Permissions -> PermissionsPage(manager, back)
            Page.Licenses -> LicensesPage(back)
            Page.Tips -> SubPage(stringResource(R.string.tips_title), back) {
                com.voluene.monobrowser.ui.onboarding.GestureTips(Modifier.padding(20.dp))
            }
        }
    }
}

// ---- building blocks ----

@Composable
private fun Section(title: String) {
    if (LocalSettingsQuery.current.isNotBlank()) return // results are a flat list
    val scheme = MaterialTheme.colorScheme
    Text(
        title.uppercase(), color = scheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    if (!settingMatches(LocalSettingsQuery.current, title, subtitle)) return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) { MonoSwitch(checked, onChange) }

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    ScreenScaffold(title, onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
    }
}

private fun formatTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

// ---- main page ----

@Composable
private fun MainPage(manager: TabManager, onOpenUrl: (String) -> Unit, onClose: () -> Unit, open: (Page) -> Unit) {
    val context = LocalContext.current
    val settings by manager.settings.collectAsState()
    val lists by manager.adBlocker.listsStatus.collectAsState()
    val exceptions by manager.adBlocker.exceptions.hostsFlow.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    var syncSetup by remember { mutableStateOf(false) }
    var syncStop by remember { mutableStateOf(false) }
    fun update(transform: (AppSettings) -> AppSettings) = manager.updateSettings(transform)

    val engineNames = mapOf(
        *SearchEngine.entries.filter { it != SearchEngine.CUSTOM }.map { it to it.displayName }.toTypedArray(),
        SearchEngine.CUSTOM to stringResource(R.string.settings_engine_custom),
    )
    val startupNames = mapOf(StartupMode.RESTORE to stringResource(R.string.settings_startup_restore), StartupMode.NEWTAB to stringResource(R.string.settings_startup_newtab))
    val newTabNames = mapOf(NewTabPage.BLANK to stringResource(R.string.settings_newtab_blank), NewTabPage.HOMEPAGE to stringResource(R.string.settings_newtab_homepage))
    val languageNames = mapOf(AppLanguage.SYSTEM to stringResource(R.string.settings_language_system), AppLanguage.PL to "Polski", AppLanguage.EN to "English")
    val toolbarNames = mapOf(ToolbarPosition.BOTTOM to stringResource(R.string.settings_toolbar_bottom), ToolbarPosition.TOP to stringResource(R.string.settings_toolbar_top))
    val linkNames = mapOf(LinkLongPress.GLANCE to stringResource(R.string.settings_link_glance), LinkLongPress.MENU to stringResource(R.string.settings_link_menu))
    val modeNames = mapOf(ThemeMode.SYSTEM to stringResource(R.string.settings_mode_system), ThemeMode.LIGHT to stringResource(R.string.settings_mode_light), ThemeMode.DARK to stringResource(R.string.settings_mode_dark))
    val themeName = Themes.find(settings.themeId)?.name ?: stringResource(R.string.settings_theme_default)
    val webDarkNames = mapOf(
        WebDarkMode.AUTO to stringResource(R.string.settings_web_dark_auto),
        WebDarkMode.FORCE to stringResource(R.string.settings_web_dark_force),
        WebDarkMode.OFF to stringResource(R.string.settings_web_dark_off),
    )

    var query by remember { mutableStateOf("") }
    ScreenScaffold(stringResource(R.string.settings_title), onClose) {
        com.voluene.monobrowser.ui.common.MonoTextField(
            query, { query = it }, stringResource(R.string.settings_search),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        androidx.compose.runtime.CompositionLocalProvider(LocalSettingsQuery provides query) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Section(stringResource(R.string.settings_section_general))
            SettingRow(stringResource(R.string.settings_search_engine), engineNames[settings.searchEngine], onClick = { dialog = "engine" })
            if (settings.searchEngine == SearchEngine.CUSTOM) {
                SettingRow(stringResource(R.string.settings_custom_url), settings.customSearchUrl, onClick = { dialog = "customUrl" })
            }
            if (SearchSuggestions.url(settings.searchEngine, "x", "en") != null) {
                SwitchRow(stringResource(R.string.settings_suggestions), stringResource(R.string.settings_suggestions_hint), settings.searchSuggestions) { v -> update { it.copy(searchSuggestions = v) } }
            }
            SettingRow(stringResource(R.string.settings_startup), startupNames[settings.startupMode], onClick = { dialog = "startup" })
            SettingRow(stringResource(R.string.settings_newtab_page), newTabNames[settings.newTabPage], onClick = { dialog = "newtab" })
            if (settings.newTabPage == NewTabPage.HOMEPAGE) {
                SettingRow(stringResource(R.string.settings_homepage_url), settings.homepageUrl.ifEmpty { stringResource(R.string.settings_homepage_unset) }, onClick = { dialog = "homepage" })
            } else {
                SwitchRow(stringResource(R.string.settings_top_sites), stringResource(R.string.settings_top_sites_hint), settings.showTopSites) { v -> update { it.copy(showTopSites = v) } }
                if (settings.hiddenTopSites.isNotEmpty()) {
                    SettingRow(
                        stringResource(R.string.settings_restore_top_sites),
                        stringResource(R.string.settings_restore_top_sites_hint, settings.hiddenTopSites.size),
                        onClick = { update { it.copy(hiddenTopSites = emptyList()) } },
                    )
                }
                SwitchRow(stringResource(R.string.settings_newtab_focus), stringResource(R.string.settings_newtab_focus_hint), settings.newTabFocusAddress) { v -> update { it.copy(newTabFocusAddress = v) } }
            }
            SettingRow(stringResource(R.string.settings_language), languageNames[settings.language], onClick = { dialog = "language" })
            SettingRow(stringResource(R.string.settings_toolbar), toolbarNames[settings.toolbarPosition], onClick = { dialog = "toolbar" })
            SwitchRow(stringResource(R.string.settings_full_address), stringResource(R.string.settings_full_address_hint), settings.showFullAddress) { v -> update { it.copy(showFullAddress = v) } }
            SettingRow(stringResource(R.string.settings_link_longpress), linkNames[settings.linkLongPress], onClick = { dialog = "linkLongPress" })

            SyncRows(manager, settings.syncUri, onSetUp = { syncSetup = true }, onStop = { syncStop = true })

            Section(stringResource(R.string.settings_section_appearance))
            SettingRow(stringResource(R.string.settings_theme), themeName, onClick = { open(Page.Theme) })
            SettingRow(stringResource(R.string.settings_mode), modeNames[settings.themeMode], onClick = { dialog = "mode" })
            SettingRow(stringResource(R.string.settings_text_size), stringResource(R.string.text_size_value, settings.textZoom), onClick = { dialog = "textSize" })
            SettingRow(stringResource(R.string.settings_web_dark), webDarkNames[settings.webDarkMode], onClick = { dialog = "webDark" })

            Section(stringResource(R.string.settings_section_privacy))
            SwitchRow(stringResource(R.string.settings_adblock), stringResource(R.string.settings_adblock_hint), settings.adblockEnabled) { v -> update { it.copy(adblockEnabled = v) } }
            SettingRow(stringResource(R.string.settings_exceptions), stringResource(R.string.settings_exceptions_count, exceptions.size), onClick = { open(Page.Exceptions) })
            SettingRow(stringResource(R.string.settings_permissions), stringResource(R.string.settings_permissions_hint), onClick = { open(Page.Permissions) })
            SwitchRow(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_hint), settings.autoPictureInPicture) { v -> update { it.copy(autoPictureInPicture = v) } }
            SwitchRow(stringResource(R.string.settings_pip_page), stringResource(R.string.settings_pip_page_hint), settings.pipWholePage) { v -> update { it.copy(pipWholePage = v) } }
            SwitchRow(stringResource(R.string.settings_strip_tracking), stringResource(R.string.settings_strip_tracking_hint), settings.stripTracking) { v -> update { it.copy(stripTracking = v) } }
            SettingRow(stringResource(R.string.settings_clear_data), stringResource(R.string.settings_clear_data_hint), onClick = { clearDialog = true })

            Section(stringResource(R.string.settings_section_adblock))
            val stateLabel = when (lists.state) {
                State.IDLE -> R.string.adblock_state_idle
                State.LOADING -> R.string.adblock_state_loading
                State.READY -> R.string.adblock_state_ready
                State.ERROR -> R.string.adblock_state_error
                State.WAITING_NETWORK -> R.string.adblock_state_waiting_network
                State.WAITING_WIFI -> R.string.adblock_state_waiting_wifi
            }
            SettingRow(stringResource(R.string.adblock_status), stringResource(stateLabel))
            SettingRow(
                stringResource(R.string.adblock_lists),
                stringResource(R.string.adblock_lists_count, lists.listsOnDisk, lists.listsTotal),
            )
            SettingRow(
                stringResource(R.string.adblock_last_update),
                if (lists.lastUpdateMs > 0) formatTime(lists.lastUpdateMs) else stringResource(R.string.adblock_never),
            )
            SettingRow(stringResource(R.string.adblock_update_now), stringResource(R.string.adblock_update_now_hint), enabled = !lists.updating, onClick = manager.adBlocker::updateListsNow)
            SwitchRow(stringResource(R.string.adblock_wifi_only), stringResource(R.string.adblock_wifi_only_hint), settings.wifiOnlyListUpdates) { v -> update { it.copy(wifiOnlyListUpdates = v) } }

            Section(stringResource(R.string.settings_section_about))
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            SettingRow(stringResource(R.string.app_name), stringResource(R.string.about_version, version))
            if (manager.updater.enabled) UpdateRows(manager, settings.autoUpdateCheck, onOpenUrl, onAutoChange = { v -> update { it.copy(autoUpdateCheck = v) } })
            SettingRow(stringResource(R.string.tips_title), stringResource(R.string.tips_hint), onClick = { open(Page.Tips) })
            SettingRow(stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_hint), onClick = { open(Page.Licenses) })
            SettingRow(stringResource(R.string.about_website), WEBSITE.removePrefix("https://"), onClick = { onOpenUrl(WEBSITE) })
        }
        }
    }

    when (dialog) {
        "engine" -> ChoiceDialog(stringResource(R.string.settings_search_engine), engineNames.toList(), settings.searchEngine, { choice ->
            update { it.copy(searchEngine = choice) }
            // Custom needs an address first.
            if (choice == SearchEngine.CUSTOM) dialog = "customUrl"
        }, { if (dialog == "engine") dialog = null })
        "startup" -> ChoiceDialog(stringResource(R.string.settings_startup), startupNames.toList(), settings.startupMode, { c -> update { it.copy(startupMode = c) } }, { dialog = null })
        "newtab" -> ChoiceDialog(stringResource(R.string.settings_newtab_page), newTabNames.toList(), settings.newTabPage, { c ->
            update { it.copy(newTabPage = c) }
            if (c == NewTabPage.HOMEPAGE && settings.homepageUrl.isEmpty()) dialog = "homepage"
        }, { if (dialog == "newtab") dialog = null })
        "language" -> ChoiceDialog(stringResource(R.string.settings_language), languageNames.toList(), settings.language, { c -> update { it.copy(language = c) } }, { dialog = null })
        "toolbar" -> ChoiceDialog(stringResource(R.string.settings_toolbar), toolbarNames.toList(), settings.toolbarPosition, { c -> update { it.copy(toolbarPosition = c) } }, { dialog = null })
        "linkLongPress" -> ChoiceDialog(stringResource(R.string.settings_link_longpress), linkNames.toList(), settings.linkLongPress, { c -> update { it.copy(linkLongPress = c) } }, { dialog = null })
        "textSize" -> com.voluene.monobrowser.ui.browser.TextSizeDialog(
            zoom = settings.textZoom,
            onChange = { zoom -> update { it.copy(textZoom = zoom) } },
            onDismiss = { dialog = null },
        )
        "webDark" -> ChoiceDialog(stringResource(R.string.settings_web_dark), webDarkNames.toList(), settings.webDarkMode, { c -> update { it.copy(webDarkMode = c) } }, { dialog = null })
        "mode" -> ChoiceDialog(stringResource(R.string.settings_mode), modeNames.toList(), settings.themeMode, { c -> update { it.copy(themeMode = c) } }, { dialog = null })
        "customUrl" -> TextInputDialog(
            stringResource(R.string.settings_custom_url), settings.customSearchUrl.takeIf { settings.searchEngine == SearchEngine.CUSTOM }.orEmpty(),
            stringResource(R.string.settings_custom_url_hint), stringResource(R.string.dialog_ok),
            { v -> update { it.copy(customSearchUrl = v.trim(), searchEngine = SearchEngine.CUSTOM) } }, { dialog = null },
            validate = SettingsCodec::isValidSearchUrl, keyboardType = KeyboardType.Uri,
        )
        "homepage" -> TextInputDialog(
            stringResource(R.string.settings_homepage_url), settings.homepageUrl, stringResource(R.string.settings_homepage_hint),
            stringResource(R.string.dialog_ok),
            { v -> update { it.copy(homepageUrl = v.trim(), newTabPage = NewTabPage.HOMEPAGE) } }, { dialog = null },
            validate = SettingsCodec::isValidHomepage, keyboardType = KeyboardType.Uri,
        )
    }
    if (clearDialog) ClearDataDialog(manager) { clearDialog = false }
    if (syncSetup) {
        val scheme = MaterialTheme.colorScheme
        MonoDialog({ syncSetup = false }) {
            Text(stringResource(R.string.sync_setup), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.sync_setup_text), color = scheme.onBackground, fontSize = 14.sp, lineHeight = 20.sp)
            MonoButton(stringResource(R.string.sync_create), {
                syncSetup = false
                manager.host?.createDocument("monobrowser-sync.json", "application/json") { uri -> uri?.let(manager::useSyncFile) }
            }, Modifier.fillMaxWidth(), inverted = true)
            MonoButton(stringResource(R.string.sync_choose), {
                syncSetup = false
                manager.host?.openDocument(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) { uri -> uri?.let(manager::useSyncFile) }
            }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.sync_desktop_hint), color = scheme.onBackground.copy(alpha = 0.65f), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    if (syncStop) {
        ConfirmDialog(
            stringResource(R.string.sync_stop), stringResource(R.string.sync_stop_message), stringResource(R.string.sync_stop_confirm),
            onConfirm = manager::stopSync, onDismiss = { syncStop = false },
        )
    }
}

// ---- sync ----

@Composable
private fun SyncRows(manager: TabManager, uri: String, onSetUp: () -> Unit, onStop: () -> Unit) {
    val status by manager.sync.status.collectAsState()
    Section(stringResource(R.string.sync_section))
    if (uri.isBlank()) {
        SettingRow(stringResource(R.string.sync_setup), stringResource(R.string.sync_setup_hint), onClick = onSetUp)
        return
    }
    val (name, provider) = remember(uri) { manager.sync.describe(uri) }
    SettingRow(stringResource(R.string.sync_file), listOf(name, provider).filter { it.isNotBlank() }.joinToString("  \u00b7  ").ifBlank { uri })
    val statusText = when {
        status.running -> stringResource(R.string.sync_running)
        status.error == com.voluene.monobrowser.sync.SyncError.NO_ACCESS -> stringResource(R.string.sync_error_access)
        status.error == com.voluene.monobrowser.sync.SyncError.NOT_A_SYNC_FILE -> stringResource(R.string.sync_error_format)
        status.error == com.voluene.monobrowser.sync.SyncError.IO -> stringResource(R.string.sync_error_io)
        status.lastSyncAt > 0 -> stringResource(R.string.sync_last, formatTime(status.lastSyncAt)) +
            (status.lastWriter.takeIf { it.isNotBlank() }?.let { "  \u00b7  " + stringResource(R.string.sync_written_by, it) } ?: "")
        else -> stringResource(R.string.sync_never)
    }
    SettingRow(stringResource(R.string.sync_now), statusText, enabled = !status.running, onClick = { manager.sync.sync(force = true) })
    SettingRow(stringResource(R.string.sync_what), stringResource(R.string.sync_what_hint))
    SettingRow(stringResource(R.string.sync_stop), stringResource(R.string.sync_stop_hint), onClick = onStop)
}

// ---- updates (Settings -> About) ----

@Composable
private fun UpdateRows(manager: TabManager, auto: Boolean, onOpenUrl: (String) -> Unit, onAutoChange: (Boolean) -> Unit) {
    val updater = manager.updater
    val status by updater.status.collectAsState()
    val release = status.release
    SwitchRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_hint), auto, onAutoChange)
    SettingRow(stringResource(R.string.update_current), updater.currentVersionName)
    SettingRow(
        stringResource(R.string.update_latest),
        release?.version ?: if (status.phase == UpdatePhase.UP_TO_DATE) updater.currentVersionName else stringResource(R.string.update_latest_unknown),
    )
    SettingRow(
        stringResource(R.string.update_last_check),
        if (status.lastCheckMs > 0) formatTime(status.lastCheckMs) else stringResource(R.string.adblock_never),
    )
    val busy = status.phase == UpdatePhase.CHECKING || status.phase.isBusy
    SettingRow(stringResource(R.string.update_check_now), updateStatusText(status), enabled = !busy, onClick = updater::checkNow)
    if (release != null && status.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED, UpdatePhase.NEEDS_PERMISSION)) {
        SettingRow(stringResource(R.string.update_install_version, release.version), onClick = updater::startUpdate) {
            MonoButton(stringResource(R.string.update_action_update), updater::startUpdate, inverted = true)
        }
    }
    if (release != null && status.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED, UpdatePhase.NEEDS_PERMISSION)) {
        var showNotes by remember { mutableStateOf(false) }
        if (showNotes) UpdateNotesSheet(updater, release, onOpenUrl, onDismiss = { showNotes = false })
        SettingRow(stringResource(R.string.update_action_notes), release.version, onClick = { showNotes = true })
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
