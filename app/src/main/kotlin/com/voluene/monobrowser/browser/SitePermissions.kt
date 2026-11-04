package com.voluene.monobrowser.browser

enum class SitePermission { CAMERA, MICROPHONE, LOCATION }

/**
 * Per-origin permission decisions. Normal tabs persist them through [onChange] (the store is created from the
 * saved [initial] map); private tabs use a separate in-memory instance that is cleared with the private session.
 */
class SitePermissionStore(initial: Map<String, Boolean> = emptyMap(), private val onChange: (Map<String, Boolean>) -> Unit = {}) {
    private val decisions = HashMap<String, Boolean>(initial.filterKeys(::isValidKey))

    private fun changed() = onChange(HashMap(decisions))

    private fun key(origin: String, permission: SitePermission) = "$origin|${permission.name}"

    operator fun get(origin: String, permission: SitePermission): Boolean? = decisions[key(origin, permission)]

    fun set(origin: String, permissions: Collection<SitePermission>, allowed: Boolean) {
        permissions.forEach { decisions[key(origin, it)] = allowed }
        changed()
    }

    fun clear() {
        decisions.clear()
        changed()
    }

    /** One remembered decision, for the settings screen. */
    data class Decision(val origin: String, val permission: SitePermission, val allowed: Boolean)

    fun decisionsList(): List<Decision> = decisions.entries.map { (key, allowed) ->
        Decision(key.substringBefore('|'), SitePermission.valueOf(key.substringAfter('|')), allowed)
    }.sortedWith(compareBy({ it.origin }, { it.permission.ordinal }))

    /** Forgets one decision: the site is asked again next time. */
    fun forget(origin: String, permission: SitePermission) {
        decisions.remove(key(origin, permission))
        changed()
    }

    /** Decisions of one site (the site information sheet). */
    fun decisionsFor(origin: String): List<Decision> = decisionsList().filter { it.origin == origin }

    /** Permissions of [wanted] that still need a user decision. */
    fun undecided(origin: String, wanted: Collection<SitePermission>): List<SitePermission> =
        wanted.filter { get(origin, it) == null }

    companion object {
        /** A stored key must be `origin|PERMISSION` with a known permission (anything else is dropped on load). */
        fun isValidKey(key: String): Boolean {
            val origin = key.substringBefore('|', "")
            val name = key.substringAfter('|', "")
            return originOf(origin) == origin && SitePermission.entries.any { it.name == name }
        }

        /** `scheme://host[:port]` of a URL, or null for non-web pages. */
        fun originOf(url: String): String? {
            val match = Regex("""^(https?)://([^/?#\s]+)""", RegexOption.IGNORE_CASE).find(url) ?: return null
            val authority = match.groupValues[2].substringAfterLast('@')
            return "${match.groupValues[1].lowercase()}://${authority.lowercase()}"
        }

        fun hostOf(origin: String): String = origin.substringAfter("://").substringBefore(':')
    }
}
