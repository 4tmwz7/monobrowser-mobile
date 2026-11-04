package com.voluene.monobrowser.util

/** "Translate page": the page opened through Google Translate's web proxy. Pure, unit-tested. */
object TranslateUrl {
    fun of(url: String, targetLanguage: String): String {
        val lang = targetLanguage.lowercase().takeIf { Regex("^[a-z]{2,3}$").matches(it) } ?: "en"
        return "https://translate.google.com/translate?sl=auto&tl=$lang&u=${UrlUtils.encodeUriComponent(url)}"
    }
}
