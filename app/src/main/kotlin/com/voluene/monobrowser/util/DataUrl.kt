package com.voluene.monobrowser.util

import java.net.URLDecoder
import java.util.Base64

class DataUrl(val mimeType: String, val bytes: ByteArray) {
    companion object {
        /** Parses `data:[<mime>][;charset=..][;base64],<data>`; null when malformed. */
        fun parse(url: String): DataUrl? {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma).split(';')
            val isBase64 = meta.any { it.equals("base64", ignoreCase = true) }
            val mime = meta.firstOrNull()?.trim().orEmpty().ifEmpty { "text/plain" }
            val payload = url.substring(comma + 1)
            val bytes = runCatching {
                if (isBase64) {
                    // Base64 payloads may be percent-encoded; '+' is a legitimate base64 digit.
                    Base64.getMimeDecoder().decode(URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8"))
                } else {
                    URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
                }
            }.getOrNull() ?: return null
            return DataUrl(mime.lowercase(), bytes)
        }
    }
}
