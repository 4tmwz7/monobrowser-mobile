package com.voluene.monobrowser.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A published release that carries an installable APK and its checksum file. */
@Serializable
data class ReleaseInfo(
    val tag: String,
    val version: String,
    /** Human-readable release page ("View on GitHub"). */
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val shaUrl: String,
    /** Release notes (GitHub `body`, Markdown); empty when the release has none. */
    val body: String = "",
    /** GitHub `published_at` (ISO-8601, e.g. 2026-10-30T12:00:00Z) or null. */
    val publishedAt: String? = null,
)

/** Reads the GitHub "latest release" payload; drafts, prereleases and releases without both assets yield null. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, fallbackPageBase: String): ReleaseInfo? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { parse(it, fallbackPageBase) }

    fun parse(root: JsonElement, fallbackPageBase: String): ReleaseInfo? {
        val obj = root as? JsonObject ?: return null
        val tag = obj.string("tag_name") ?: return null
        if ((obj["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        if ((obj["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val version = Versions.stripPrefix(tag)
        if (Versions.parse(version) == null) return null
        val apkName = "MonoBrowser-$version.apk"
        val assets = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it.string("name") == apkName } ?: return null
        val sha = assets.firstOrNull { it.string("name") == "$apkName.sha256" } ?: return null
        val apkUrl = apk.string("browser_download_url") ?: return null
        val shaUrl = sha.string("browser_download_url") ?: return null
        val page = obj.string("html_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: "${fallbackPageBase.trimEnd('/')}/tag/$tag"
        return ReleaseInfo(tag, version, page, apkName, apkUrl, (apk["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: -1L, shaUrl,
            body = obj.string("body")?.trim().orEmpty(), publishedAt = obj.string("published_at")?.takeIf { it.isNotBlank() })
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** Download URLs must come from GitHub (https) or from the explicit test origin of the build's base URL. */
object TrustedUrls {
    private val GITHUB_HOSTS = listOf("github.com", "githubusercontent.com")

    fun isTrusted(url: String, testBaseUrl: String? = null): Boolean {
        val parsed = parts(url) ?: return false
        if (parsed.scheme == "https" && GITHUB_HOSTS.any { parsed.host == it || parsed.host.endsWith(".$it") }) return true
        val base = testBaseUrl?.let(::parts) ?: return false
        return base.host != "api.github.com" && base.scheme == parsed.scheme && base.host == parsed.host && base.port == parsed.port
    }

    private class UrlParts(val scheme: String, val host: String, val port: Int)

    private fun parts(url: String): UrlParts? {
        val m = Regex("""^(https?)://([^/?#:@]+)(?::(\d+))?(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).matchEntire(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val port = m.groupValues[3].toIntOrNull() ?: if (scheme == "https") 443 else 80
        return UrlParts(scheme, m.groupValues[2].lowercase(), port)
    }
}
