package com.voluene.monobrowser.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.adblock.ListsState as State
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.search.SearchSuggestions
import com.voluene.monobrowser.settings.AppLanguage
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.settings.LinkLongPress
import com.voluene.monobrowser.settings.NewTabPage
import com.voluene.monobrowser.settings.SearchEngine
import com.voluene.monobrowser.settings.SettingsItem
import com.voluene.monobrowser.settings.StartupMode
import com.voluene.monobrowser.settings.ThemeMode
import com.voluene.monobrowser.settings.Themes
import com.voluene.monobrowser.settings.ToolbarPosition
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.update.UpdateNotesSheet
import com.voluene.monobrowser.ui.update.updateStatusText
import com.voluene.monobrowser.update.UpdatePhase

/** What an item needs from the screen around it: the manager, the live settings, navigation and the shared dialogs. */
internal class SettingsEnv(
    val manager: TabManager,
    val settings: AppSettings,
    val onOpenUrl: (String) -> Unit,
    /** Opens a sub-page. */
    val open: (Page) -> Unit,
    /** Opens one of the choice / text dialogs of [SettingsDialogs] by key. */
    val showDialog: (String) -> Unit,
    val showClearData: () -> Unit,
    val showSyncSetup: () -> Unit,
    val showSyncStop: () -> Unit,
    val names: SettingsNames,
) {
    fun update(transform: (AppSettings) -> AppSettings) = manager.updateSettings(transform)
}

/** Display names of the enum choices (also used by the dialogs). */
internal class SettingsNames(
    val engines: Map<SearchEngine, String>,
    val startup: Map<StartupMode, String>,
    val newTab: Map<NewTabPage, String>,
    val languages: Map<AppLanguage, String>,
    val toolbar: Map<ToolbarPosition, String>,
    val links: Map<LinkLongPress, String>,
    val modes: Map<ThemeMode, String>,
)

@Composable
internal fun rememberSettingsNames(): SettingsNames {
    // The names follow the app's language: rebuilt when the configuration (locale, scale) changes instead of on
    // every recomposition.
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return remember(context, configuration) {
        fun text(id: Int): String = context.getString(id)
        SettingsNames(
            engines = mapOf(
                *SearchEngine.entries.filter { it != SearchEngine.CUSTOM }.map { it to it.displayName }.toTypedArray(),
                SearchEngine.CUSTOM to text(R.string.settings_engine_custom),
            ),
            startup = mapOf(StartupMode.RESTORE to text(R.string.settings_startup_restore), StartupMode.NEWTAB to text(R.string.settings_startup_newtab)),
            newTab = mapOf(NewTabPage.BLANK to text(R.string.settings_newtab_blank), NewTabPage.HOMEPAGE to text(R.string.settings_newtab_homepage)),
            languages = mapOf(AppLanguage.SYSTEM to text(R.string.settings_language_system), AppLanguage.PL to "Polski", AppLanguage.EN to "English"),
            toolbar = mapOf(ToolbarPosition.BOTTOM to text(R.string.settings_toolbar_bottom), ToolbarPosition.TOP to text(R.string.settings_toolbar_top)),
            links = mapOf(LinkLongPress.GLANCE to text(R.string.settings_link_glance), LinkLongPress.MENU to text(R.string.settings_link_menu)),
            modes = mapOf(ThemeMode.SYSTEM to text(R.string.settings_mode_system), ThemeMode.LIGHT to text(R.string.settings_mode_light), ThemeMode.DARK to text(R.string.settings_mode_dark)),
        )
    }
}

/**
 * Draws one settings item (a row, or a few inseparable rows). Items that do not apply right now (a custom search address
 * with another engine, sync details before sync is set up, update rows in a build without updates) draw nothing.
 * Every row filters itself by the search text, so the same function serves a category page and the search results.
 */
@Composable
internal fun SettingItem(item: SettingsItem, env: SettingsEnv) {
    val settings = env.settings
    val names = env.names
    val manager = env.manager
    when (item) {
        // ---- General ----
        SettingsItem.SEARCH_ENGINE -> {
            SettingRow(stringResource(R.string.settings_search_engine), names.engines[settings.searchEngine], onClick = { env.showDialog("engine") })
            if (settings.searchEngine == SearchEngine.CUSTOM) {
                SettingRow(stringResource(R.string.settings_custom_url), settings.customSearchUrl, onClick = { env.showDialog("customUrl") })
            }
        }
        SettingsItem.SEARCH_SUGGESTIONS -> if (SearchSuggestions.url(settings.searchEngine, "x", "en") != null) {
            SwitchRow(stringResource(R.string.settings_suggestions), stringResource(R.string.settings_suggestions_hint), settings.searchSuggestions) { v -> env.update { it.copy(searchSuggestions = v) } }
        }
        SettingsItem.STARTUP -> SettingRow(stringResource(R.string.settings_startup), names.startup[settings.startupMode], onClick = { env.showDialog("startup") })
        SettingsItem.LANGUAGE -> SettingRow(stringResource(R.string.settings_language), names.languages[settings.language], onClick = { env.showDialog("language") })

        // ---- Start page (the page rows only apply while a new tab shows the start page, not a home page) ----
        SettingsItem.NEWTAB_PAGE -> {
            SettingRow(stringResource(R.string.settings_newtab_page), names.newTab[settings.newTabPage], onClick = { env.showDialog("newtab") })
            if (settings.newTabPage == NewTabPage.HOMEPAGE) {
                SettingRow(stringResource(R.string.settings_homepage_url), settings.homepageUrl.ifEmpty { stringResource(R.string.settings_homepage_unset) }, onClick = { env.showDialog("homepage") })
            }
        }
        SettingsItem.TOP_SITES -> if (settings.newTabPage != NewTabPage.HOMEPAGE) {
            SwitchRow(stringResource(R.string.settings_top_sites), stringResource(R.string.settings_top_sites_hint), settings.showTopSites) { v -> env.update { it.copy(showTopSites = v) } }
            if (settings.hiddenTopSites.isNotEmpty()) {
                SettingRow(
                    stringResource(R.string.settings_restore_top_sites),
                    stringResource(R.string.settings_restore_top_sites_hint, settings.hiddenTopSites.size),
                    onClick = { env.update { it.copy(hiddenTopSites = emptyList()) } },
                )
            }
        }
        SettingsItem.START_BOOKMARKS -> if (settings.newTabPage != NewTabPage.HOMEPAGE) {
            val bookmarkData by manager.bookmarks.collectAsState()
            SwitchRow(stringResource(R.string.settings_start_bookmarks), stringResource(R.string.settings_start_bookmarks_hint), settings.showStartBookmarks) { v -> env.update { it.copy(showStartBookmarks = v) } }
            if (settings.showStartBookmarks) {
                SettingRow(
                    stringResource(R.string.settings_start_bookmarks_choose),
                    stringResource(R.string.settings_start_bookmarks_count, bookmarkData.startPage.size),
                    onClick = { env.open(Page.StartPicker) },
                )
            }
        }
        SettingsItem.FAVICONS -> if (settings.newTabPage != NewTabPage.HOMEPAGE) {
            SwitchRow(stringResource(R.string.settings_favicons), stringResource(R.string.settings_favicons_hint), settings.showFavicons) { v -> env.update { it.copy(showFavicons = v) } }
            if (settings.showFavicons) {
                SwitchRow(stringResource(R.string.settings_favicons_gray), null, settings.faviconsGrayscale) { v -> env.update { it.copy(faviconsGrayscale = v) } }
            }
        }
        SettingsItem.START_BACKGROUND -> if (settings.newTabPage != NewTabPage.HOMEPAGE) {
            SettingRow(
                stringResource(R.string.settings_start_background),
                stringResource(if (settings.startBackground > 0) R.string.settings_start_background_set else R.string.settings_start_background_none),
                onClick = { env.open(Page.StartBackground) },
            )
        }
        SettingsItem.NEWTAB_FOCUS -> if (settings.newTabPage != NewTabPage.HOMEPAGE) {
            SwitchRow(stringResource(R.string.settings_newtab_focus), stringResource(R.string.settings_newtab_focus_hint), settings.newTabFocusAddress) { v -> env.update { it.copy(newTabFocusAddress = v) } }
        }

        // ---- Appearance ----
        SettingsItem.THEME -> SettingRow(
            stringResource(R.string.settings_theme), Themes.find(settings.themeId)?.name ?: stringResource(R.string.settings_theme_default),
            onClick = { env.open(Page.Theme) },
        )
        SettingsItem.MODE -> SettingRow(stringResource(R.string.settings_mode), names.modes[settings.themeMode], onClick = { env.showDialog("mode") })
        SettingsItem.TOOLBAR_POSITION -> SettingRow(stringResource(R.string.settings_toolbar), names.toolbar[settings.toolbarPosition], onClick = { env.showDialog("toolbar") })
        SettingsItem.BAR_LAYOUT -> SettingRow(stringResource(R.string.settings_bar_layout), null, onClick = { env.open(Page.BarLayout) })
        SettingsItem.MENU_LAYOUT -> SettingRow(stringResource(R.string.settings_menu_layout), null, onClick = { env.open(Page.MenuLayout) })
        SettingsItem.FULL_ADDRESS -> SwitchRow(stringResource(R.string.settings_full_address), stringResource(R.string.settings_full_address_hint), settings.showFullAddress) { v -> env.update { it.copy(showFullAddress = v) } }

        // ---- Tabs and gestures ----
        SettingsItem.LINK_LONG_PRESS -> SettingRow(stringResource(R.string.settings_link_longpress), names.links[settings.linkLongPress], onClick = { env.showDialog("linkLongPress") })
        SettingsItem.GESTURE_TIPS -> SettingRow(stringResource(R.string.tips_title), stringResource(R.string.tips_hint), onClick = { env.open(Page.Tips) })

        // ---- Pages ----
        SettingsItem.PAGE_THEME -> SettingRow(stringResource(R.string.settings_page_theme), names.modes[settings.pageThemeMode], onClick = { env.showDialog("pageMode") })
        SettingsItem.DARKEN_PAGES -> SwitchRow(stringResource(R.string.settings_darken_pages), stringResource(R.string.settings_darken_pages_hint), settings.darkenPages) { v -> env.update { it.copy(darkenPages = v) } }
        SettingsItem.SITE_THEMES -> {
            val siteThemes by manager.siteThemes.state.collectAsState()
            SettingRow(stringResource(R.string.settings_site_themes), stringResource(R.string.settings_site_themes_count, siteThemes.size), onClick = { env.open(Page.SiteThemes) })
        }
        SettingsItem.TEXT_SIZE -> SettingRow(stringResource(R.string.settings_text_size), stringResource(R.string.text_size_value, settings.textZoom), onClick = { env.showDialog("textSize") })
        SettingsItem.PIP -> SwitchRow(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_hint), settings.autoPictureInPicture) { v -> env.update { it.copy(autoPictureInPicture = v) } }
        SettingsItem.PIP_PAGE -> SwitchRow(stringResource(R.string.settings_pip_page), stringResource(R.string.settings_pip_page_hint), settings.pipWholePage) { v -> env.update { it.copy(pipWholePage = v) } }

        // ---- Privacy and blocking ----
        SettingsItem.ADBLOCK -> SwitchRow(stringResource(R.string.settings_adblock), stringResource(R.string.settings_adblock_hint), settings.adblockEnabled) { v -> env.update { it.copy(adblockEnabled = v) } }
        SettingsItem.EXCEPTIONS -> {
            val exceptions by manager.adBlocker.exceptions.hostsFlow.collectAsState()
            SettingRow(stringResource(R.string.settings_exceptions), stringResource(R.string.settings_exceptions_count, exceptions.size), onClick = { env.open(Page.Exceptions) })
        }
        SettingsItem.PERMISSIONS -> SettingRow(stringResource(R.string.settings_permissions), stringResource(R.string.settings_permissions_hint), onClick = { env.open(Page.Permissions) })
        SettingsItem.STRIP_TRACKING -> SwitchRow(stringResource(R.string.settings_strip_tracking), stringResource(R.string.settings_strip_tracking_hint), settings.stripTracking) { v -> env.update { it.copy(stripTracking = v) } }
        SettingsItem.CLEAR_DATA -> SettingRow(stringResource(R.string.settings_clear_data), stringResource(R.string.settings_clear_data_hint), onClick = env.showClearData)
        SettingsItem.ADBLOCK_STATUS -> {
            val lists by manager.adBlocker.listsStatus.collectAsState()
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
        }
        SettingsItem.ADBLOCK_LISTS -> {
            val lists by manager.adBlocker.listsStatus.collectAsState()
            SettingRow(stringResource(R.string.adblock_lists), stringResource(R.string.adblock_lists_count, lists.listsOnDisk, lists.listsTotal))
        }
        SettingsItem.ADBLOCK_LAST_UPDATE -> {
            val lists by manager.adBlocker.listsStatus.collectAsState()
            SettingRow(
                stringResource(R.string.adblock_last_update),
                if (lists.lastUpdateMs > 0) formatTime(lists.lastUpdateMs) else stringResource(R.string.adblock_never),
            )
        }
        SettingsItem.ADBLOCK_UPDATE_NOW -> {
            val lists by manager.adBlocker.listsStatus.collectAsState()
            SettingRow(stringResource(R.string.adblock_update_now), stringResource(R.string.adblock_update_now_hint), enabled = !lists.updating, onClick = manager.adBlocker::updateListsNow)
        }
        SettingsItem.ADBLOCK_WIFI_ONLY -> SwitchRow(stringResource(R.string.adblock_wifi_only), stringResource(R.string.adblock_wifi_only_hint), settings.wifiOnlyListUpdates) { v -> env.update { it.copy(wifiOnlyListUpdates = v) } }

        // ---- Sync ----
        SettingsItem.SYNC_SETUP -> if (settings.syncUri.isBlank()) {
            SettingRow(stringResource(R.string.sync_setup), stringResource(R.string.sync_setup_hint), onClick = env.showSyncSetup)
        }
        SettingsItem.SYNC_FILE -> if (settings.syncUri.isNotBlank()) {
            val uri = settings.syncUri
            val (name, provider) = remember(uri) { manager.sync.describe(uri) }
            SettingRow(stringResource(R.string.sync_file), listOf(name, provider).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { uri })
        }
        SettingsItem.SYNC_NOW -> if (settings.syncUri.isNotBlank()) {
            val status by manager.sync.status.collectAsState()
            val statusText = when {
                status.running -> stringResource(R.string.sync_running)
                status.error == com.voluene.monobrowser.sync.SyncError.NO_ACCESS -> stringResource(R.string.sync_error_access)
                status.error == com.voluene.monobrowser.sync.SyncError.NOT_A_SYNC_FILE -> stringResource(R.string.sync_error_format)
                status.error == com.voluene.monobrowser.sync.SyncError.IO -> stringResource(R.string.sync_error_io)
                status.lastSyncAt > 0 -> stringResource(R.string.sync_last, formatTime(status.lastSyncAt)) +
                    (status.lastWriter.takeIf { it.isNotBlank() }?.let { "  ·  " + stringResource(R.string.sync_written_by, it) } ?: "")
                else -> stringResource(R.string.sync_never)
            }
            SettingRow(stringResource(R.string.sync_now), statusText, enabled = !status.running, onClick = { manager.sync.sync(force = true) })
        }
        SettingsItem.SYNC_WHAT -> if (settings.syncUri.isNotBlank()) {
            SettingRow(stringResource(R.string.sync_what), stringResource(R.string.sync_what_hint))
        }
        SettingsItem.SYNC_STOP -> if (settings.syncUri.isNotBlank()) {
            SettingRow(stringResource(R.string.sync_stop), stringResource(R.string.sync_stop_hint), onClick = env.showSyncStop)
        }

        // ---- About and updates ----
        SettingsItem.ABOUT_APP -> {
            val context = LocalContext.current
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            SettingRow(stringResource(R.string.app_name), stringResource(R.string.about_version, version)) {
                com.voluene.monobrowser.ui.common.AppLogo(size = 40.dp)
            }
        }
        SettingsItem.UPDATE_AUTO -> if (manager.updater.enabled) {
            SwitchRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_hint), settings.autoUpdateCheck) { v -> env.update { it.copy(autoUpdateCheck = v) } }
        }
        SettingsItem.UPDATE_CURRENT -> if (manager.updater.enabled) {
            SettingRow(stringResource(R.string.update_current), manager.updater.currentVersionName)
        }
        SettingsItem.UPDATE_LATEST -> if (manager.updater.enabled) {
            val status by manager.updater.status.collectAsState()
            SettingRow(
                stringResource(R.string.update_latest),
                status.release?.version ?: if (status.phase == UpdatePhase.UP_TO_DATE) manager.updater.currentVersionName else stringResource(R.string.update_latest_unknown),
            )
        }
        SettingsItem.UPDATE_LAST_CHECK -> if (manager.updater.enabled) {
            val status by manager.updater.status.collectAsState()
            SettingRow(
                stringResource(R.string.update_last_check),
                if (status.lastCheckMs > 0) formatTime(status.lastCheckMs) else stringResource(R.string.adblock_never),
            )
        }
        SettingsItem.UPDATE_CHECK_NOW -> if (manager.updater.enabled) {
            val status by manager.updater.status.collectAsState()
            val busy = status.phase == UpdatePhase.CHECKING || status.phase.isBusy
            SettingRow(stringResource(R.string.update_check_now), updateStatusText(status), enabled = !busy, onClick = manager.updater::checkNow)
        }
        SettingsItem.UPDATE_INSTALL -> if (manager.updater.enabled) {
            val updater = manager.updater
            val status by updater.status.collectAsState()
            val release = status.release
            if (release != null && status.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED, UpdatePhase.NEEDS_PERMISSION)) {
                SettingRow(stringResource(R.string.update_install_version, release.version), onClick = updater::startUpdate) {
                    MonoButton(stringResource(R.string.update_action_update), updater::startUpdate, inverted = true)
                }
            }
        }
        SettingsItem.UPDATE_NOTES -> if (manager.updater.enabled) {
            val updater = manager.updater
            val status by updater.status.collectAsState()
            val release = status.release
            if (release != null && status.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED, UpdatePhase.NEEDS_PERMISSION)) {
                var showNotes by remember { mutableStateOf(false) }
                if (showNotes) UpdateNotesSheet(updater, release, env.onOpenUrl, onDismiss = { showNotes = false })
                SettingRow(stringResource(R.string.update_action_notes), release.version, onClick = { showNotes = true })
            }
        }
        SettingsItem.LICENSES -> SettingRow(stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_hint), onClick = { env.open(Page.Licenses) })
        SettingsItem.WEBSITE -> SettingRow(stringResource(R.string.about_website), WEBSITE.removePrefix("https://"), onClick = { env.onOpenUrl(WEBSITE) })
    }
}
