package com.voluene.monobrowser.browser

import android.webkit.WebViewClient
import androidx.annotation.StringRes
import com.voluene.monobrowser.R

/** Failure categories, mirroring the desktop `error-page-html.ts` kinds. */
enum class ErrorKind(@param:StringRes val title: Int, @param:StringRes val hint: Int) {
    OFFLINE(R.string.err_offline_title, R.string.err_offline_hint),
    DNS(R.string.err_dns_title, R.string.err_dns_hint),
    REFUSED(R.string.err_refused_title, R.string.err_refused_hint),
    TIMEOUT(R.string.err_timeout_title, R.string.err_timeout_hint),
    RESET(R.string.err_reset_title, R.string.err_reset_hint),
    TLS(R.string.err_tls_title, R.string.err_tls_hint),
    CERT(R.string.err_cert_title, R.string.err_cert_hint),
    REDIRECTS(R.string.err_redirects_title, R.string.err_redirects_hint),
    BLOCKED(R.string.err_blocked_title, R.string.err_blocked_hint),
    INVALID_URL(R.string.err_invalid_url_title, R.string.err_invalid_url_hint),
    FILE(R.string.err_file_title, R.string.err_file_hint),
    GENERIC(R.string.err_generic_title, R.string.err_generic_hint);

    companion object {
        /** Maps [WebViewClient] error codes; [online] tells a lookup failure from "no network at all". */
        fun fromWebViewCode(code: Int, online: Boolean = true): ErrorKind = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP -> if (online) DNS else OFFLINE
            WebViewClient.ERROR_CONNECT -> if (online) REFUSED else OFFLINE
            WebViewClient.ERROR_TIMEOUT -> TIMEOUT
            WebViewClient.ERROR_IO -> if (online) RESET else OFFLINE
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> TLS
            WebViewClient.ERROR_REDIRECT_LOOP -> REDIRECTS
            WebViewClient.ERROR_BAD_URL -> INVALID_URL
            WebViewClient.ERROR_FILE, WebViewClient.ERROR_FILE_NOT_FOUND -> FILE
            WebViewClient.ERROR_UNSAFE_RESOURCE, WebViewClient.ERROR_UNSUPPORTED_SCHEME -> BLOCKED
            else -> GENERIC
        }
    }
}

/** Monochrome, rectangular error page (desktop look: paper background, uppercase title, square button). */
object ErrorPageHtml {
    /** Link target of the retry button; intercepted in `shouldOverrideUrlLoading`. */
    const val RETRY_URL = "monobrowser-retry:"

    fun escape(text: String): String = buildString(text.length) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /** [hint] may contain the `%s` host placeholder, already substituted by the caller. */
    fun render(title: String, hint: String, retryLabel: String, failedUrl: String, language: String): String = """
<!doctype html>
<html lang="${escape(language)}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
  :root { color-scheme: light dark; --bg: #f0efe9; --fg: #0a0a0a; --muted: #5c5b57; }
  @media (prefers-color-scheme: dark) { :root { --bg: #0a0a0a; --fg: #f0efe9; --muted: #9c9b95; } }
  html, body { margin: 0; background: var(--bg); color: var(--fg); }
  body { font-family: sans-serif; padding: 12vh 24px 24px; box-sizing: border-box; min-height: 100vh; }
  main { max-width: 520px; margin: 0 auto; }
  h1 { margin: 0 0 16px; font: 700 34px/1 sans-serif; text-transform: uppercase; letter-spacing: .01em; }
  p { margin: 0 0 12px; line-height: 1.5; }
  .url { color: var(--muted); font-size: 13px; word-break: break-all; }
  a.btn { display: inline-block; margin-top: 20px; padding: 12px 20px; border: 2px solid var(--fg); border-radius: 0;
          background: var(--fg); color: var(--bg); font: 700 12px sans-serif; letter-spacing: .06em; text-transform: uppercase; text-decoration: none; }
</style>
</head>
<body>
<main>
  <h1>${escape(title)}</h1>
  <p>${escape(hint)}</p>
  <p class="url">${escape(failedUrl)}</p>
  <a class="btn" href="$RETRY_URL">${escape(retryLabel)}</a>
</main>
</body>
</html>
""".trimIndent()
}
