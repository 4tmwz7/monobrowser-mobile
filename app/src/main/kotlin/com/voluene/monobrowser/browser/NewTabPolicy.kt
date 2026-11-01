package com.voluene.monobrowser.browser

import com.voluene.monobrowser.util.UrlUtils

/**
 * "New tab" de-duplication: asking for a new tab while an empty one already exists switches to it instead of
 * piling up blank tabs. Normal and private tabs are de-duplicated separately. Pure, unit-tested.
 */
object NewTabPolicy {
    /**
     * An empty new tab: no page loaded (blank URL or about:blank, or exactly the configured [homepage] that a
     * new tab opens with), no back history and not showing an error page.
     */
    fun isEmptyNewTab(tab: Tab, homepage: String?): Boolean {
        return !tab.canGoBack && isBlankUrl(tab.url, homepage)
    }

    private fun isBlankUrl(url: String, homepage: String?): Boolean =
        url.isBlank() || UrlUtils.isAboutBlankUrl(url) || (homepage != null && url == homepage)

    /**
     * The tab to switch to instead of creating a new one, or null when a new tab must be created.
     * The active tab wins when it is itself empty; otherwise the first empty tab in tab order.
     */
    fun findReusable(tabs: List<Tab>, activeId: Long, isPrivate: Boolean, homepage: String?): Tab? {
        val candidates = tabs.filter { it.isPrivate == isPrivate && isEmptyNewTab(it, homepage) }
        return candidates.firstOrNull { it.id == activeId } ?: candidates.firstOrNull()
    }
}
