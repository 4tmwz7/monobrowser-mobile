package com.voluene.monobrowser.settings

/**
 * Entries of the main menu (the grid behind the address bar's menu button). The enum name is the stable id stored
 * in the settings; the declaration order is the default order. Add new entries at the end of a release's block:
 * an id that is missing from a stored order is appended automatically.
 */
enum class MenuId {
    // The first seven visible entries are shown before "More": Settings must stay among them, also while the video
    // tile (PICTURE_IN_PICTURE, only while a video plays) takes a slot - hence Settings before Share.
    NEW_TAB, NEW_PRIVATE_TAB, BOOKMARKS, BOOKMARK_TOGGLE, HISTORY, PICTURE_IN_PICTURE, SETTINGS, SHARE,
    FIND, DOWNLOADS, READER, REOPEN_CLOSED, NOTES, NOTE_ADD, DESKTOP_SITE, PAGE_THEME;

    /** Settings cannot be hidden (it is the way back to this configuration). */
    val mandatory: Boolean get() = this == SETTINGS
}

/**
 * Order and visibility of the main menu: a stored order of ids plus a set of hidden ids (not "the visible list"),
 * so entries added by a later version appear at the end instead of vanishing. Pure, unit-tested.
 */
object MenuLayout {
    val DEFAULT_ORDER: List<MenuId> = MenuId.entries.toList()

    private fun parseIds(ids: List<String>): List<MenuId> =
        ids.mapNotNull { id -> MenuId.entries.firstOrNull { it.name == id } }

    /** Every known entry exactly once: the stored [order] (unknown ids and duplicates dropped), then the rest in default order. */
    fun order(stored: List<String>): List<MenuId> {
        val known = parseIds(stored).distinct()
        return known + DEFAULT_ORDER.filter { it !in known }
    }

    fun hidden(stored: List<String>): Set<MenuId> = parseIds(stored).filterNot { it.mandatory }.toSet()

    /** Entries to show, in order (context-dependent ones are filtered by the caller, which knows the page / tab). */
    fun visible(storedOrder: List<String>, storedHidden: List<String>): List<MenuId> {
        val hidden = hidden(storedHidden)
        return order(storedOrder).filter { it !in hidden }
    }

    /** Settings-ready values: [order] as ids, hidden entries as ids (never the mandatory one). */
    fun ids(order: List<MenuId>): List<String> = order.map { it.name }

    /** Canonical form of the stored fields; empty (= default) stays empty. */
    fun sanitizeOrder(stored: List<String>): List<String> = if (stored.isEmpty()) emptyList() else ids(order(stored))

    fun sanitizeHidden(stored: List<String>): List<String> = MenuId.entries.filter { it in hidden(stored) }.map { it.name }

    fun toggle(hidden: List<String>, id: MenuId): List<String> {
        if (id.mandatory) return sanitizeHidden(hidden)
        val set = hidden(hidden)
        return MenuId.entries.filter { if (it == id) it !in set else it in set }.map { it.name }
    }

    /** Moves [id] by [delta] places within the full order. */
    fun move(storedOrder: List<String>, id: MenuId, delta: Int): List<String> {
        val list = order(storedOrder).toMutableList()
        val index = list.indexOf(id)
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (index < 0 || target == index) return ids(list)
        list.add(target, list.removeAt(index))
        return ids(list)
    }
}
