package com.voluene.monobrowser.browser

enum class SitePermission { CAMERA, MICROPHONE, LOCATION }

/**
 * Per-origin permission decisions kept in memory until the app process ends (desktop policy:
 * "Your choice lasts until the app is closed"). Normal and private tabs use separate instances.
 */
class SitePermissionStore {
    private val decisions = HashMap<String, Boolean>()

    private fun key(origin: String, permission: SitePermission) = "$origin|${permission.name}"

    operator fun get(origin: String, permission: SitePermission): Boolean? = decisions[key(origin, permission)]

    fun set(origin: String, permissions: Collection<SitePermission>, allowed: Boolean) {
        permissions.forEach { decisions[key(origin, it)] = allowed }
    }

    fun clear() = decisions.clear()

    /** One remembered decision, for the settings screen. */
    data class Decision(val origin: String, val permission: SitePermission, val allowed: Boolean)

    fun decisionsList(): List<Decision> = decisions.entries.map { (key, allowed) ->
        Decision(key.substringBefore('|'), SitePermission.valueOf(key.substringAfter('|')), allowed)
    }.sortedWith(compareBy({ it.origin }, { it.permission.ordinal }))

    /** Forgets one decision: the site is asked again next time. */
    fun forget(origin: String, permission: SitePermission) {
        decisions.remove(key(origin, permission))
    }

    /** Permissions of [wanted] that still need a user decision. */
    fun undecided(origin: String, wanted: Collection<SitePermission>): List<SitePermission> =
        wanted.filter { get(origin, it) == null }

    companion object {
        /** `scheme://host[:port]` of a URL, or null for non-web pages. */
        fun originOf(url: String): String? {
            val match = Regex("""^(https?)://([^/?#\s]+)""", RegexOption.IGNORE_CASE).find(url) ?: return null
            val authority = match.groupValues[2].substringAfterLast('@')
            return "${match.groupValues[1].lowercase()}://${authority.lowercase()}"
        }

        fun hostOf(origin: String): String = origin.substringAfter("://").substringBefore(':')
    }
}
