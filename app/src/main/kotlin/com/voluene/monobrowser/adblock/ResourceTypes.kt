package com.voluene.monobrowser.adblock

/** Maps a WebView request onto the resource types the filter engine understands. Pure, unit-tested. */
object ResourceTypes {
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
    private val MEDIA_EXT = setOf("mp4", "webm", "mp3", "m4a", "ogg", "wav", "m3u8", "mpd")
    private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val SCRIPT_EXT = setOf("js", "mjs")

    /** Engine (adblock-rust) request type name. */
    fun engineName(type: ResourceType): String = when (type) {
        ResourceType.DOCUMENT -> "document"
        ResourceType.SUBDOCUMENT -> "sub_frame"
        ResourceType.SCRIPT -> "script"
        ResourceType.STYLESHEET -> "stylesheet"
        ResourceType.IMAGE -> "image"
        ResourceType.MEDIA -> "media"
        ResourceType.FONT -> "font"
        ResourceType.XHR -> "xhr"
        ResourceType.OTHER -> "other"
    }

    /**
     * @param path URL path (a query/fragment is ignored)
     * @param headers request headers; looked up case-insensitively
     */
    fun guess(isMainFrame: Boolean, path: String?, headers: Map<String, String>): ResourceType {
        if (isMainFrame) return ResourceType.DOCUMENT
        fun header(name: String): String =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty().lowercase()

        // Sec-Fetch-Dest is the most reliable signal when the WebView provides it.
        when (header("Sec-Fetch-Dest")) {
            "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.SCRIPT
            "style" -> return ResourceType.STYLESHEET
            "image" -> return ResourceType.IMAGE
            "font" -> return ResourceType.FONT
            "audio", "video", "track" -> return ResourceType.MEDIA
            "iframe", "frame", "embed", "object" -> return ResourceType.SUBDOCUMENT
            "document" -> return ResourceType.DOCUMENT
        }

        val ext = path.orEmpty().substringBefore('?').substringBefore('#').substringAfterLast('/', "")
            .substringAfterLast('.', "").lowercase()
        val accept = header("Accept")
        return when {
            ext in SCRIPT_EXT -> ResourceType.SCRIPT
            ext == "css" -> ResourceType.STYLESHEET
            ext in FONT_EXT -> ResourceType.FONT
            ext in MEDIA_EXT -> ResourceType.MEDIA
            ext in IMAGE_EXT || accept.startsWith("image/") -> ResourceType.IMAGE
            accept.startsWith("text/css") -> ResourceType.STYLESHEET
            accept.contains("text/html") -> ResourceType.SUBDOCUMENT
            header("X-Requested-With") == "xmlhttprequest" -> ResourceType.XHR
            // fetch()/XHR typically send "*/*" with no file extension.
            accept == "*/*" && ext.isEmpty() -> ResourceType.XHR
            else -> ResourceType.OTHER
        }
    }
}
