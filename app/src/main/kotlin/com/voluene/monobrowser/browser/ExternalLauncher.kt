package com.voluene.monobrowser.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

sealed interface LaunchResult {
    data object Opened : LaunchResult
    data class LoadFallback(val url: String) : LaunchResult
    data object Failed : LaunchResult
}

/** Opens non-web links in other apps, with the safety rules of Chrome's intent handling. */
object ExternalLauncher {
    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun launch(context: Context, action: LinkAction): LaunchResult = when (action) {
        is LinkAction.OpenExternal -> view(context, Uri.parse(action.url))
        is LinkAction.OpenIntentUri -> openIntentUri(context, action)
        LinkAction.Load, is LinkAction.Ignore -> LaunchResult.Failed
    }

    private fun view(context: Context, uri: Uri): LaunchResult =
        start(context, Intent(Intent.ACTION_VIEW, uri))

    private fun openIntentUri(context: Context, action: LinkAction.OpenIntentUri): LaunchResult {
        val fallback = action.fallbackUrl?.let { LaunchResult.LoadFallback(it) }
        val intent = runCatching { Intent.parseUri(action.uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return fallback ?: LaunchResult.Failed
        // Never let a web page target a specific component, a selector or grant URI permissions.
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        val dataScheme = intent.data?.scheme?.lowercase()
        if (dataScheme == "file" || dataScheme == "content") return fallback ?: LaunchResult.Failed
        val result = start(context, intent)
        if (result == LaunchResult.Opened) return result
        fallback?.let { return it }
        // App not installed: offer its store page, as Chrome does.
        val pkg = ExternalLinks.intentPackage(action.uri) ?: return LaunchResult.Failed
        return view(context, Uri.parse("market://details?id=$pkg"))
    }

    private fun start(context: Context, intent: Intent): LaunchResult {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.Opened
        } catch (_: ActivityNotFoundException) {
            LaunchResult.Failed
        } catch (_: SecurityException) {
            LaunchResult.Failed
        }
    }
}
