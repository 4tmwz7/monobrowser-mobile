package com.voluene.monobrowser.browser

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources

/**
 * Context a page WebView is created with. Chromium WebView derives `prefers-color-scheme` (and whether algorithmic
 * darkening may apply) from `android:isLightTheme` of this context's theme, so the page theme is decoupled from the
 * browser UI by switching [dark]; the WebView re-reads it whenever its preferences are recomputed.
 */
class PageThemeContext(base: Context) : ContextWrapper(base) {
    @Volatile var dark: Boolean = false

    private val lightTheme: Resources.Theme by lazy {
        resources.newTheme().apply { applyStyle(android.R.style.Theme_Material_Light_NoActionBar, true) }
    }
    private val darkTheme: Resources.Theme by lazy {
        resources.newTheme().apply { applyStyle(android.R.style.Theme_Material_NoActionBar, true) }
    }

    override fun getTheme(): Resources.Theme = if (dark) darkTheme else lightTheme
}
