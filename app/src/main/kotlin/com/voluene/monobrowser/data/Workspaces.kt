package com.voluene.monobrowser.data

import kotlinx.serialization.Serializable

/**
 * A workspace groups tabs (port of the desktop `workspaces.ts`, without colours: the mobile UI is strictly
 * monochrome). An empty [name] means the localised default name (only the main workspace has one).
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String = "",
    val icon: String = WorkspaceOps.ICONS[0],
)

/** Pure workspace-list operations (all return new lists, nothing is mutated). */
object WorkspaceOps {
    const val MAIN_ID = "main"
    const val MAX_WORKSPACES = 12
    const val MAX_NAME_LENGTH = 40
    private const val MAX_ICON_LENGTH = 16

    /** Small monochrome icon set (symbols); any single character / emoji typed by the user is allowed too. */
    val ICONS: List<String> = listOf("◼", "●", "▲", "◆", "★", "♥", "☰", "⌂", "✦", "☾", "☀", "⚑")

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

    fun main() = Workspace(MAIN_ID)

    /** Display name: the stored name, or [mainLabel] for the unnamed main workspace, or [fallback]. */
    fun displayName(workspace: Workspace, mainLabel: String, fallback: String = mainLabel): String =
        workspace.name.ifBlank { if (workspace.id == MAIN_ID) mainLabel else fallback }

    /** Collapses whitespace, trims and caps the length; null when nothing is left. */
    fun sanitizeName(value: String?): String? {
        val cleaned = value.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_NAME_LENGTH).trim()
        return cleaned.ifEmpty { null }
    }

    /**
     * First user-perceived character of [value] (a base code point plus variation selectors, combining marks,
     * skin-tone modifiers and ZWJ sequences, or a flag = two regional indicators); null for blank input or an
     * absurdly long cluster. Hand-written so that it behaves the same on the JVM (tests) and on Android.
     */
    fun sanitizeIcon(value: String?): String? {
        val trimmed = value.orEmpty().trim()
        if (trimmed.isEmpty()) return null
        val points = trimmed.codePoints().toArray()
        var end = 1
        if (isRegionalIndicator(points[0]) && points.size > 1 && isRegionalIndicator(points[1])) end = 2
        while (end < points.size) {
            val cp = points[end]
            when {
                cp == ZWJ && end + 1 < points.size -> end += 2
                isExtender(cp) -> end += 1
                else -> break
            }
        }
        val first = String(points, 0, end)
        return first.takeIf { it.length <= MAX_ICON_LENGTH }
    }

    private const val ZWJ = 0x200D
    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun isExtender(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun isValidId(id: String) = ID_PATTERN.matches(id)

    /**
     * Validates a stored list: the main workspace always exists (added in front when missing), ids are unique and
     * well-formed, the list is capped.
     */
    fun normalize(list: List<Workspace>): List<Workspace> {
        val seen = HashSet<String>()
        val out = ArrayList<Workspace>()
        for (entry in list) {
            if (!isValidId(entry.id) || !seen.add(entry.id) || out.size >= MAX_WORKSPACES) continue
            out.add(Workspace(entry.id, sanitizeName(entry.name).orEmpty(), sanitizeIcon(entry.icon) ?: ICONS[0]))
        }
        if (MAIN_ID !in seen) out.add(0, main())
        return out.take(MAX_WORKSPACES)
    }

    fun generateId(existing: Collection<String>, random: () -> Double = Math::random): String {
        repeat(50) {
            val id = "ws-" + (random() * 2_176_782_336.0).toLong().toString(36).padStart(6, '0')
            if (id !in existing) return id
        }
        return "ws-${existing.size + 1}-${System.currentTimeMillis().toString(36)}"
    }

    /** "Space 2", "Space 3", ... (first name not used yet). */
    fun nextDefaultName(list: List<Workspace>, base: String, mainLabel: String): String {
        val used = list.map { displayName(it, mainLabel, base).lowercase() }.toSet()
        for (n in list.size + 1 until list.size + 1000) {
            val candidate = "$base $n"
            if (candidate.lowercase() !in used) return candidate
        }
        return "$base ${System.currentTimeMillis()}"
    }

    fun nextDefaultIcon(list: List<Workspace>): String {
        val used = list.map { it.icon }.toSet()
        return ICONS.firstOrNull { it !in used } ?: ICONS[list.size % ICONS.size]
    }

    /** Appends a workspace; null at the limit. Blank [name]/[icon] get defaults. */
    fun add(
        list: List<Workspace>,
        name: String?,
        icon: String?,
        defaultBase: String,
        mainLabel: String,
        random: () -> Double = Math::random,
    ): Pair<List<Workspace>, Workspace>? {
        if (list.size >= MAX_WORKSPACES) return null
        val workspace = Workspace(
            id = generateId(list.map { it.id }, random),
            name = sanitizeName(name) ?: nextDefaultName(list, defaultBase, mainLabel),
            icon = sanitizeIcon(icon) ?: nextDefaultIcon(list),
        )
        return (list + workspace) to workspace
    }

    /** Changes name and/or icon (null = keep). A blank name resets the main workspace to its default name and is ignored elsewhere. */
    fun update(list: List<Workspace>, id: String, name: String? = null, icon: String? = null): List<Workspace>? {
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return null
        val current = list[index]
        var next = current
        if (name != null) {
            val cleaned = sanitizeName(name)
            if (cleaned != null) next = next.copy(name = cleaned) else if (id == MAIN_ID) next = next.copy(name = "")
        }
        if (icon != null) sanitizeIcon(icon)?.let { next = next.copy(icon = it) }
        return list.toMutableList().also { it[index] = next }
    }

    /** Removes a workspace; the main one cannot be removed. */
    fun remove(list: List<Workspace>, id: String): List<Workspace>? =
        if (id == MAIN_ID || list.none { it.id == id }) null else list.filterNot { it.id == id }

    /** Moves a workspace by [delta] places (clamped to the list). */
    fun move(list: List<Workspace>, id: String, delta: Int): List<Workspace>? {
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return null
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (target == index) return list
        val copy = list.toMutableList()
        copy.add(target, copy.removeAt(index))
        return copy
    }

    /** Next/previous workspace, wrapping around; null when there is only one. */
    fun adjacentId(list: List<Workspace>, currentId: String, direction: Int): String? {
        if (list.size < 2) return null
        val index = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        return list[(index + direction + list.size) % list.size].id
    }
}
