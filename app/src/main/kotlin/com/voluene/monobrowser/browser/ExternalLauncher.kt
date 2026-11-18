package com.voluene.monobrowser.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

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
        is LinkAction.OpenApp -> view(context, Uri.parse(action.url))
        is LinkAction.OpenIntentUri -> openIntentUri(context, action)
        LinkAction.Load, is LinkAction.Ignore -> LaunchResult.Failed
    }

    private fun view(context: Context, uri: Uri): LaunchResult =
        start(context, Intent(Intent.ACTION_VIEW, uri))

    /** The `intent:` URI as a safe intent (no component, selector or URI grants), or null when it must not be started. */
    private fun safeIntent(uri: String): Intent? {
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        // Never let a web page target a specific component, a selector or grant URI permissions.
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        val dataScheme = intent.data?.scheme?.lowercase()
        if (dataScheme == "file" || dataScheme == "content") return null
        return intent
    }

    /**
     * Labels of the apps that would handle [action] (own app left out), empty when there are none, null when the link
     * must not be started at all. Android 11+ package visibility is declared by `<queries>` in the manifest.
     */
    fun resolveApps(context: Context, action: LinkAction): List<String>? {
        val intent = when (action) {
            is LinkAction.OpenApp -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
            is LinkAction.OpenIntentUri -> safeIntent(action.uri)
            else -> return emptyList()
        } ?: return null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        val infos = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrDefault(emptyList())
        val handlers = infos.filter { it.activityInfo?.packageName != context.packageName }
        fun label(info: android.content.pm.ResolveInfo) = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        // The user picked a default app for such links: only that one opens (no chooser), so only that one is named.
        val default = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull()?.activityInfo?.packageName
        handlers.firstOrNull { it.activityInfo?.packageName == default }?.let(::label)?.let { return listOf(it) }
        return handlers.mapNotNull(::label).distinct()
    }

    private fun openIntentUri(context: Context, action: LinkAction.OpenIntentUri): LaunchResult {
        val fallback = action.fallbackUrl?.let { LaunchResult.LoadFallback(it) }
        val intent = safeIntent(action.uri) ?: return fallback ?: LaunchResult.Failed
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
