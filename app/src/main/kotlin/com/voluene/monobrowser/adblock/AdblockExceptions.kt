package com.voluene.monobrowser.adblock

import java.io.File

/** Host helpers shared by the exception list and the blocker. Port of desktop `adblock-exceptions.ts`. */
object HostUtils {
    private val HOST_LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://")

    /** Lowercase host of an absolute URL without port / userinfo; null when there is none. */
    fun hostOfUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val lower = url.trim().lowercase()
        val m = SCHEME.find(lower) ?: return null
        val rest = lower.substring(m.range.last + 1)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.ifEmpty { null }
    }

    /** Normalizes a hostname or URL to a lowercase host without a leading "www."; null when invalid. */
    fun normalizeHost(input: String?): String? {
        var value = input?.trim()?.lowercase() ?: return null
        if (value.isEmpty() || value.length > 300) return null
        if (SCHEME.containsMatchIn(value)) {
            value = hostOfUrl(value) ?: return null
        } else if (value.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) {
            return null
        }
        value = value.removeSuffix(".").removePrefix("www.")
        if (value.isEmpty() || value.length > 253 || value.contains(':')) return null
        if (!value.split('.').all { HOST_LABEL.matches(it) }) return null
        return value
    }

    /** True when [host] equals an allowlisted site or is one of its subdomains. */
    fun isHostExcepted(host: String?, exceptions: Set<String>): Boolean {
        if (exceptions.isEmpty()) return false
        var candidate = normalizeHost(host) ?: return false
        while (true) {
            if (candidate in exceptions) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }
}

/**
 * Per-site adblock allowlist (host -> adblock disabled on that site and its subdomains).
 * Persisted as `{"hosts":["a.com","b.org"]}` (same shape as desktop). Thread-safe.
 * [file] may be null for a purely in-memory store (tests).
 */
class AdblockExceptions(private val file: File?) {
    private val initial: Set<String> = file?.let(::readFile).orEmpty()
    @Volatile private var hosts: Set<String> = initial
    private val lock = Any()
    private val _hostsFlow = kotlinx.coroutines.flow.MutableStateFlow(initial.toList().sorted())

    /** Observable sorted list (settings screen, menu toggle label). */
    val hostsFlow: kotlinx.coroutines.flow.StateFlow<List<String>> = _hostsFlow

    fun list(): List<String> = hosts.sorted()

    /** True when adblock is disabled for [hostOrUrl] (exact host or subdomain of an excepted site). */
    fun contains(hostOrUrl: String?): Boolean {
        val set = hosts
        if (set.isEmpty() || hostOrUrl == null) return false
        return HostUtils.isHostExcepted(HostUtils.hostOfUrl(hostOrUrl) ?: hostOrUrl, set)
    }

    /** Returns false when [hostOrUrl] is not a valid host. */
    fun add(hostOrUrl: String): Boolean {
        val host = HostUtils.normalizeHost(hostOrUrl) ?: return false
        synchronized(lock) {
            hosts = hosts + host
            save()
        }
        return true
    }

    /** Returns false when [hostOrUrl] is not a valid host. */
    fun remove(hostOrUrl: String): Boolean {
        val host = HostUtils.normalizeHost(hostOrUrl) ?: return false
        synchronized(lock) {
            hosts = hosts - host
            save()
        }
        return true
    }

    /** Flips the exception; returns true when the host is excepted afterwards, null when invalid. */
    fun toggle(hostOrUrl: String): Boolean? {
        val host = HostUtils.normalizeHost(hostOrUrl) ?: return null
        synchronized(lock) {
            val nowExcepted = host !in hosts
            hosts = if (nowExcepted) hosts + host else hosts - host
            save()
            return nowExcepted
        }
    }

    /**
     * Menu toggle for the page being viewed: when the site is already excepted (directly or through a parent
     * domain such as `example.com` for `news.example.com`) the matching entry is removed, otherwise the host is
     * added. Returns true when adblock is disabled for the site afterwards, null for an invalid host.
     */
    fun toggleSite(hostOrUrl: String): Boolean? {
        val host = HostUtils.normalizeHost(HostUtils.hostOfUrl(hostOrUrl) ?: hostOrUrl) ?: return null
        synchronized(lock) {
            var candidate: String? = host
            var matching: String? = null
            while (candidate != null) {
                if (candidate in hosts) { matching = candidate; break }
                val dot = candidate.indexOf('.')
                candidate = if (dot < 0) null else candidate.substring(dot + 1)
            }
            hosts = if (matching != null) hosts - matching else hosts + host
            save()
            return matching == null
        }
    }

    private fun save() {
        _hostsFlow.value = hosts.sorted()
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(serialize(hosts))
            if (!tmp.renameTo(f)) {
                f.writeText(serialize(hosts))
                tmp.delete()
            }
        } catch (_: Exception) {
            // Best effort: the in-memory list stays authoritative for this process.
        }
    }

    companion object {
        fun serialize(hosts: Collection<String>): String =
            hosts.sorted().joinToString(",", prefix = "{\"hosts\":[", postfix = "]}") { "\"$it\"" }

        /** Lenient parse of `{"hosts":[...]}` or a bare `[...]`: sorted, de-duplicated, validated. */
        fun parse(json: String): Set<String> {
            val body = json.substringAfter('[', "").substringBeforeLast(']', "")
            return Regex("\"([^\"\\\\]*)\"").findAll(body)
                .mapNotNull { HostUtils.normalizeHost(it.groupValues[1]) }
                .toSortedSet()
        }

        private fun readFile(file: File): Set<String> =
            try {
                if (file.exists()) parse(file.readText()) else emptySet()
            } catch (_: Exception) {
                emptySet()
            }
    }
}
