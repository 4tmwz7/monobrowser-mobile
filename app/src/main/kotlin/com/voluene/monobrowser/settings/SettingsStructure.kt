package com.voluene.monobrowser.settings

/** The categories ("folders") of the Settings screen, in the order they are listed. */
enum class SettingsCategory { GENERAL, START_PAGE, APPEARANCE, TABS_GESTURES, PAGES, PRIVACY, SYNC, ABOUT }

/**
 * Every row (or small, inseparable group of rows) of the Settings screen. The screen draws a category's rows by
 * listing its items here, and the search draws all of them: a setting lives in exactly one category.
 */
enum class SettingsItem {
    // General
    SEARCH_ENGINE, SEARCH_SUGGESTIONS, STARTUP, LANGUAGE,

    // Start page
    NEWTAB_PAGE, TOP_SITES, START_BOOKMARKS, FAVICONS, START_BACKGROUND, NEWTAB_FOCUS,

    // Appearance
    THEME, MODE, TOOLBAR_POSITION, BAR_LAYOUT, MENU_LAYOUT, FULL_ADDRESS,

    // Tabs and gestures
    LINK_LONG_PRESS, GESTURE_TIPS,

    // Pages
    PAGE_THEME, DARKEN_PAGES, SITE_THEMES, TEXT_SIZE, PIP, PIP_PAGE,

    // Privacy and blocking
    ADBLOCK, EXCEPTIONS, ADBLOCK_STATUS, ADBLOCK_LISTS, ADBLOCK_LAST_UPDATE, ADBLOCK_UPDATE_NOW, ADBLOCK_WIFI_ONLY,
    PERMISSIONS, STRIP_TRACKING, CLEAR_DATA,

    // Sync
    SYNC_SETUP, SYNC_FILE, SYNC_NOW, SYNC_WHAT, SYNC_STOP,

    // About and updates
    ABOUT_APP, UPDATE_AUTO, UPDATE_CURRENT, UPDATE_LATEST, UPDATE_LAST_CHECK, UPDATE_CHECK_NOW, UPDATE_INSTALL, UPDATE_NOTES,
    LICENSES, WEBSITE,
}

/** Which item belongs to which category, and in which order a category lists them. Pure data. */
object SettingsStructure {
    private val layout: List<Pair<SettingsCategory, List<SettingsItem>>> = listOf(
        SettingsCategory.GENERAL to listOf(
            SettingsItem.SEARCH_ENGINE, SettingsItem.SEARCH_SUGGESTIONS, SettingsItem.STARTUP, SettingsItem.LANGUAGE,
        ),
        SettingsCategory.START_PAGE to listOf(
            SettingsItem.NEWTAB_PAGE, SettingsItem.TOP_SITES, SettingsItem.START_BOOKMARKS, SettingsItem.FAVICONS,
            SettingsItem.START_BACKGROUND, SettingsItem.NEWTAB_FOCUS,
        ),
        SettingsCategory.APPEARANCE to listOf(
            SettingsItem.THEME, SettingsItem.MODE, SettingsItem.TOOLBAR_POSITION, SettingsItem.BAR_LAYOUT,
            SettingsItem.MENU_LAYOUT, SettingsItem.FULL_ADDRESS,
        ),
        SettingsCategory.TABS_GESTURES to listOf(SettingsItem.LINK_LONG_PRESS, SettingsItem.GESTURE_TIPS),
        SettingsCategory.PAGES to listOf(
            SettingsItem.PAGE_THEME, SettingsItem.DARKEN_PAGES, SettingsItem.SITE_THEMES, SettingsItem.TEXT_SIZE,
            SettingsItem.PIP, SettingsItem.PIP_PAGE,
        ),
        SettingsCategory.PRIVACY to listOf(
            SettingsItem.ADBLOCK, SettingsItem.EXCEPTIONS, SettingsItem.PERMISSIONS, SettingsItem.STRIP_TRACKING,
            SettingsItem.CLEAR_DATA, SettingsItem.ADBLOCK_STATUS, SettingsItem.ADBLOCK_LISTS, SettingsItem.ADBLOCK_LAST_UPDATE,
            SettingsItem.ADBLOCK_UPDATE_NOW, SettingsItem.ADBLOCK_WIFI_ONLY,
        ),
        SettingsCategory.SYNC to listOf(
            SettingsItem.SYNC_SETUP, SettingsItem.SYNC_FILE, SettingsItem.SYNC_NOW, SettingsItem.SYNC_WHAT, SettingsItem.SYNC_STOP,
        ),
        SettingsCategory.ABOUT to listOf(
            SettingsItem.ABOUT_APP, SettingsItem.UPDATE_AUTO, SettingsItem.UPDATE_CURRENT, SettingsItem.UPDATE_LATEST,
            SettingsItem.UPDATE_LAST_CHECK, SettingsItem.UPDATE_CHECK_NOW, SettingsItem.UPDATE_INSTALL, SettingsItem.UPDATE_NOTES,
            SettingsItem.LICENSES, SettingsItem.WEBSITE,
        ),
    )

    val categories: List<SettingsCategory> get() = layout.map { it.first }

    /** The items of [category] in display order. */
    fun items(category: SettingsCategory): List<SettingsItem> = layout.first { it.first == category }.second

    /** The one category an [item] is in. */
    fun categoryOf(item: SettingsItem): SettingsCategory = layout.first { item in it.second }.first
}
