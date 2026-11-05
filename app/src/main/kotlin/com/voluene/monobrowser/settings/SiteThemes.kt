package com.voluene.monobrowser.settings

import com.voluene.monobrowser.adblock.HostUtils
import com.voluene.monobrowser.util.writeTextAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Per-site page theme override: how a site is presented to the page (`prefers-color-scheme`), independent of the browser UI. */
enum class SiteTheme(val key: String) {
    LIGHT("light"), DARK("dark");

    companion object {
        fun fromKey(key: String?): SiteTheme? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** Decides whether a page is shown dark. The browser UI theme ([AppSettings.themeMode]) is never involved. */
object PageThemes {
    fun isDark(globalMode: ThemeMode, systemDark: Boolean, override: SiteTheme?): Boolean = when (override) {
        SiteTheme.DARK -> true
        SiteTheme.LIGHT -> false
        null -> when (globalMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }
}

/**
 * Per-site page theme overrides (host -> light/dark, applies to the host and its subdomains, the most specific entry
 * wins). Persisted as `{"sites":{"example.com":"dark"}}`. Thread-safe. [file] may be null (tests).
 *
 * Private tabs read the persisted entries but never write: their changes live in an in-memory overlay that only
 * private tabs see ([privateState]) and that [clearPrivate] drops once the last private tab is gone.
 */
class SiteThemes(private val file: File?) {
    private val lock = Any()
    private var persisted: Map<String, SiteTheme> = file?.let(::readFile).orEmpty()
    /** Overlay of private tabs: a value replaces the stored entry, null hides it. */
    private var overlay: Map<String, SiteTheme?> = emptyMap()

    private val _state = MutableStateFlow(persisted.toSortedMap())
    private val _privateState = MutableStateFlow<Map<String, SiteTheme>>(persisted.toSortedMap())

    /** Persisted overrides (normal tabs, settings list). */
    val state: StateFlow<Map<String, SiteTheme>> = _state.asStateFlow()
    /** Persisted overrides with the private overlay applied (private tabs only). */
    val privateState: StateFlow<Map<String, SiteTheme>> = _privateState.asStateFlow()

    /** The override that applies to [hostOrUrl], or null when it follows the global setting. */
    fun lookup(hostOrUrl: String?, forPrivate: Boolean = false): SiteTheme? =
        find(hostOrUrl, if (forPrivate) _privateState.value else _state.value)?.second

    /**
     * Sets the override of exactly [hostOrUrl] (normalised). `null` removes the entry that currently applies to the
     * host (it may belong to a parent domain such as `example.com` for `news.example.com`).
     * [forPrivate]: change only the in-memory overlay. Returns false for an invalid host.
     */
    fun set(hostOrUrl: String, theme: SiteTheme?, forPrivate: Boolean = false): Boolean {
        val host = HostUtils.normalizeHost(HostUtils.hostOfUrl(hostOrUrl) ?: hostOrUrl) ?: return false
        synchronized(lock) {
            val visible = if (forPrivate) effectivePrivate() else persisted
            val key = if (theme == null) find(host, visible)?.first ?: return true else host
            if (forPrivate) {
                overlay = overlay + (key to theme)
            } else {
                persisted = if (theme == null) persisted - key else persisted + (key to theme)
                save()
            }
            publish()
        }
        return true
    }

    /** Drops the overlay of private tabs. */
    fun clearPrivate() {
        synchronized(lock) {
            if (overlay.isEmpty()) return
            overlay = emptyMap()
            publish()
        }
    }

    private fun effectivePrivate(): Map<String, SiteTheme> {
        val out = LinkedHashMap(persisted)
        overlay.forEach { (host, theme) -> if (theme == null) out.remove(host) else out[host] = theme }
        return out
    }

    private fun publish() {
        _state.value = persisted.toSortedMap()
        _privateState.value = effectivePrivate().toSortedMap()
    }

    private fun save() {
        val f = file ?: return
        try {
            writeTextAtomically(f, serialize(persisted))
        } catch (_: Exception) {
            // Best effort: the in-memory map stays authoritative for this process.
        }
    }

    companion object {
        fun serialize(sites: Map<String, SiteTheme>): String =
            JsonObject(mapOf("sites" to JsonObject(sites.toSortedMap().mapValues { JsonPrimitive(it.value.key) }))).toString()

        /** Lenient parse: invalid hosts / values are skipped; garbage gives an empty map. */
        fun parse(text: String): Map<String, SiteTheme> = runCatching {
            val sites = Json.parseToJsonElement(text).jsonObject["sites"]?.jsonObject ?: return emptyMap()
            val out = sortedMapOf<String, SiteTheme>()
            for ((rawHost, value) in sites) {
                val host = HostUtils.normalizeHost(rawHost) ?: continue
                val theme = SiteTheme.fromKey((value as? JsonPrimitive)?.contentOrNull) ?: continue
                out[host] = theme
            }
            out
        }.getOrDefault(emptyMap())

        /** The most specific entry for [hostOrUrl] (walks up the parent domains) as (stored host, theme). */
        fun find(hostOrUrl: String?, sites: Map<String, SiteTheme>): Pair<String, SiteTheme>? {
            if (sites.isEmpty() || hostOrUrl == null) return null
            var candidate = HostUtils.normalizeHost(HostUtils.hostOfUrl(hostOrUrl) ?: hostOrUrl) ?: return null
            while (true) {
                sites[candidate]?.let { return candidate to it }
                val dot = candidate.indexOf('.')
                if (dot < 0) return null
                candidate = candidate.substring(dot + 1)
            }
        }

        private fun readFile(file: File): Map<String, SiteTheme> =
            try {
                if (file.isFile) parse(file.readText()) else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
    }
}
