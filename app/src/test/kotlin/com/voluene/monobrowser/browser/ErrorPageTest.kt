package com.voluene.monobrowser.browser

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPageTest {
    @Test fun classifiesWebViewErrorCodes() {
        assertEquals(ErrorKind.DNS, ErrorKind.fromWebViewCode(WebViewClient.ERROR_HOST_LOOKUP, online = true))
        assertEquals(ErrorKind.OFFLINE, ErrorKind.fromWebViewCode(WebViewClient.ERROR_HOST_LOOKUP, online = false))
        assertEquals(ErrorKind.REFUSED, ErrorKind.fromWebViewCode(WebViewClient.ERROR_CONNECT))
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.fromWebViewCode(WebViewClient.ERROR_TIMEOUT))
        assertEquals(ErrorKind.TLS, ErrorKind.fromWebViewCode(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
        assertEquals(ErrorKind.REDIRECTS, ErrorKind.fromWebViewCode(WebViewClient.ERROR_REDIRECT_LOOP))
        assertEquals(ErrorKind.INVALID_URL, ErrorKind.fromWebViewCode(WebViewClient.ERROR_BAD_URL))
        assertEquals(ErrorKind.GENERIC, ErrorKind.fromWebViewCode(WebViewClient.ERROR_UNKNOWN))
    }

    @Test fun htmlEscapesEverythingFromTheOutside() {
        val html = ErrorPageHtml.render("T<script>", "hint \"x\"", "Retry", "https://e.example/?a=1&b=<b>", "pl")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;b&gt;"))
        assertTrue(html.contains("href=\"${ErrorPageHtml.RETRY_URL}\""))
        assertTrue(html.contains("lang=\"pl\""))
        assertFalse("error pages run no script", html.contains("<script"))
    }
}
