package com.voluene.monobrowser.settings

/** Things that can sit on the address bar's row. */
enum class BarItem {
    BACK, FORWARD, ADDRESS, RELOAD, MENU, TABS, NEW_TAB, BOOKMARKS, BOOKMARK, SHARE;

    /** The address field and the menu cannot be taken off the bar (without the menu there is no way back to the settings). */
    val mandatory: Boolean get() = this == ADDRESS || this == MENU
}

/**
 * Which elements the address bar shows and in what order. Stored in the settings as a list of names (unknown names
 * from an older/newer file are ignored); [sanitize] always returns a valid layout. Pure, unit-tested.
 */
object BarLayout {
    val DEFAULT: List<BarItem> = listOf(BarItem.BACK, BarItem.FORWARD, BarItem.ADDRESS, BarItem.RELOAD, BarItem.MENU, BarItem.TABS)

    /** Most buttons next to the address field: keeps the field usable on a narrow phone. */
    const val MAX_BUTTONS = 6

    val DEFAULT_IDS: List<String> = DEFAULT.map { it.name }

    fun parse(ids: List<String>): List<BarItem> = sanitize(ids.mapNotNull { id -> BarItem.entries.firstOrNull { it.name == id } })

    fun ids(items: List<BarItem>): List<String> = items.map { it.name }

    /**
     * Valid layout from anything: no duplicates, exactly one ADDRESS, MENU present, at most [MAX_BUTTONS] buttons
     * besides the address (surplus optional buttons are dropped from the end). A missing ADDRESS goes after the
     * first two items, a missing MENU to the end.
     */
    fun sanitize(items: List<BarItem>): List<BarItem> {
        val unique = items.distinct().toMutableList()
        if (BarItem.ADDRESS !in unique) unique.add(minOf(2, unique.size), BarItem.ADDRESS)
        if (BarItem.MENU !in unique) unique.add(BarItem.MENU)
        var buttons = unique.count { it != BarItem.ADDRESS }
        var index = unique.lastIndex
        while (buttons > MAX_BUTTONS && index >= 0) {
            if (!unique[index].mandatory) { unique.removeAt(index); buttons-- }
            index--
        }
        return unique
    }

    /** Optional elements not on the bar, in declaration order. */
    fun available(items: List<BarItem>): List<BarItem> = BarItem.entries.filter { it !in items }

    /** Whether one more button fits. */
    fun canAdd(items: List<BarItem>): Boolean = items.count { it != BarItem.ADDRESS } < MAX_BUTTONS

    fun remove(items: List<BarItem>, item: BarItem): List<BarItem> = if (item.mandatory) items else sanitize(items - item)

    /** Appends [item] at the end of the row; ignored when it is already there or the row is full ([MAX_BUTTONS]). */
    fun add(items: List<BarItem>, item: BarItem): List<BarItem> =
        if (item in items || !canAdd(items)) items else sanitize(items + item)

    /** Moves the item at [index] by [delta] places (clamped to the row). */
    fun move(items: List<BarItem>, index: Int, delta: Int): List<BarItem> {
        if (index !in items.indices) return items
        val target = (index + delta).coerceIn(0, items.lastIndex)
        if (target == index) return items
        val list = items.toMutableList()
        list.add(target, list.removeAt(index))
        return list
    }
}
