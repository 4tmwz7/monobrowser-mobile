package com.voluene.monobrowser.download

import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder

/**
 * `blob:` downloads: a blob URL only exists inside the page that created it, so the page itself reads it (fetch +
 * FileReader) and hands the bytes to the app as a data URL through the [BRIDGE] JavaScript interface. Each request
 * carries a one-time random token; the bridge ignores anything else, so a page cannot write files on its own.
 * Pure helpers, unit-tested.
 */
object BlobDownload {
    const val BRIDGE = "MonoBlob"

    /** Larger blobs would not fit through the bridge as one string. */
    const val MAX_BYTES = 25L * 1024 * 1024

    private fun js(value: String) = JsonPrimitive(value).toString()

    /** Script run in the page that owns [blobUrl]. */
    fun script(blobUrl: String, token: String): String = """
        (function () {
          var t = ${js(token)};
          function fail(why) { try { $BRIDGE.fail(t, why); } catch (e) {} }
          try {
            fetch(${js(blobUrl)}).then(function (r) { return r.blob(); }).then(function (b) {
              if (b.size > $MAX_BYTES) { fail('size'); return; }
              var reader = new FileReader();
              reader.onload = function () { $BRIDGE.deliver(t, reader.result, b.type || ''); };
              reader.onerror = function () { fail('read'); };
              reader.readAsDataURL(b);
            }).catch(function () { fail('fetch'); });
          } catch (e) { fail('error'); }
        })();
    """.trimIndent()

    /**
     * File name from a Content-Disposition header (`filename*=UTF-8''...` wins over `filename="..."`), or null.
     * The result is safe to use as a file name (see [sanitize]).
     */
    fun fileNameFromDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val extended = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)
        if (extended != null) {
            val charset = extended.groupValues[1].ifBlank { "UTF-8" }
            val decoded = runCatching { URLDecoder.decode(extended.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
            sanitize(decoded)?.let { return it }
        }
        val plain = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition) ?: return null
        val value = plain.groupValues[2].ifEmpty { plain.groupValues[1] }.trim()
        return sanitize(value)
    }

    /** Drops directories and characters that are not allowed in file names; null when nothing usable is left. */
    fun sanitize(name: String?): String? {
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val clean = base.replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "_").trim('.', ' ').take(120)
        return clean.takeIf { it.isNotEmpty() }
    }

    /** [name] with an extension for [extension] when it has none. */
    fun withExtension(name: String, extension: String?): String =
        if (extension.isNullOrBlank() || name.substringAfterLast('/').contains('.')) name else "$name.$extension"
}
