package com.voluene.monobrowser.update

/** Semver precedence (port of `compareVersions` from the desktop `portable-update.ts`). */
object Versions {
    private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    class Parsed(val core: List<Long>, val prerelease: List<Any>) // prerelease parts: Long or String

    fun parse(value: String?): Parsed? {
        val m = PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
        val core = (1..3).map { m.groupValues[it].toLongOrNull() ?: return null }
        val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.')
            ?.map { part -> if (part.all(Char::isDigit)) (part.toLongOrNull() ?: return null) else part }
            .orEmpty()
        return Parsed(core, pre)
    }

    /** -1 / 0 / 1, or null when either side is not a valid version. */
    fun compare(a: String?, b: String?): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until 3) {
            if (left.core[i] != right.core[i]) return if (left.core[i] > right.core[i]) 1 else -1
        }
        if (left.prerelease.isEmpty() && right.prerelease.isEmpty()) return 0
        if (left.prerelease.isEmpty()) return 1 // 1.0.0 > 1.0.0-rc.1
        if (right.prerelease.isEmpty()) return -1
        for (i in 0 until maxOf(left.prerelease.size, right.prerelease.size)) {
            val x = left.prerelease.getOrNull(i) ?: return -1
            val y = right.prerelease.getOrNull(i) ?: return 1
            if (x == y) continue
            if (x is Long && y is Long) return if (x > y) 1 else -1
            if (x is Long) return -1 // numeric identifiers sort before alphanumeric ones
            if (y is Long) return 1
            return if ((x as String) > (y as String)) 1 else -1
        }
        return 0
    }

    fun isNewer(candidate: String?, current: String?): Boolean = compare(candidate, current) == 1

    fun stripPrefix(value: String): String = value.trim().removePrefix("v").removePrefix("V")
}
