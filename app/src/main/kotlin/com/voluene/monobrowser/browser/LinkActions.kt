package com.voluene.monobrowser.browser

import com.voluene.monobrowser.settings.LinkLongPress
import com.voluene.monobrowser.util.UrlUtils

/** What a long-press on a link does. */
enum class LongPressOutcome { GLANCE, MENU, NONE }

/** Entries of the link context menu. */
enum class LinkMenuAction { OPEN_TAB, OPEN_BACKGROUND, OPEN_PRIVATE, GLANCE, COPY, SHARE }

/** Pure decisions for link long-presses (unit-tested; the [TabManager] and the UI only execute them). */
object LinkActions {
    /** Links the previews / new tabs can handle. Everything else (mailto:, tel:, ...) can only be copied or shared. */
    fun isWebLink(url: String?): Boolean = url != null && UrlUtils.isHttpUrl(url.trim())

    /** Long-press: the configured action for web links; non-web links always get the menu; blank links do nothing. */
    fun onLongPress(url: String?, setting: LinkLongPress): LongPressOutcome = when {
        url.isNullOrBlank() -> LongPressOutcome.NONE
        !isWebLink(url) -> LongPressOutcome.MENU
        setting == LinkLongPress.GLANCE -> LongPressOutcome.GLANCE
        else -> LongPressOutcome.MENU
    }

    /**
     * Menu entries in display order. A private source tab only opens private tabs (links never leave the private
     * profile), so "open in private tab" is redundant there.
     */
    fun menu(url: String, sourceIsPrivate: Boolean): List<LinkMenuAction> =
        if (!isWebLink(url)) {
            listOf(LinkMenuAction.COPY, LinkMenuAction.SHARE)
        } else {
            buildList {
                add(LinkMenuAction.OPEN_TAB)
                add(LinkMenuAction.OPEN_BACKGROUND)
                if (!sourceIsPrivate) add(LinkMenuAction.OPEN_PRIVATE)
                add(LinkMenuAction.GLANCE)
                add(LinkMenuAction.COPY)
                add(LinkMenuAction.SHARE)
            }
        }

    /** Whether a tab opened from [sourceIsPrivate] with an explicit private request is private. */
    fun opensPrivate(sourceIsPrivate: Boolean, explicitPrivate: Boolean) = sourceIsPrivate || explicitPrivate
}

/** The link context menu that is currently shown. */
data class LinkMenuState(val url: String, val sourceTabId: Long, val sourceIsPrivate: Boolean)
