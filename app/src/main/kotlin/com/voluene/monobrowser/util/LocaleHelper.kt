package com.voluene.monobrowser.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.voluene.monobrowser.settings.AppLanguage
import com.voluene.monobrowser.settings.SettingsCodec
import java.io.File
import java.util.Locale

/**
 * Per-app language. Android 13+ uses the platform `LocaleManager` (the system also lists the app in its
 * per-app language settings, see `locales_config.xml`); older versions wrap the Context with the saved
 * language (`attachBaseContext`) and recreate the Activity on change.
 */
object LocaleHelper {
    /** Language saved in `settings.json`, read synchronously (tiny file) because it is needed before any UI. */
    fun savedLanguage(context: Context): AppLanguage =
        runCatching { SettingsCodec.decode(File(context.filesDir, "settings.json").readText())?.language }.getOrNull()
            ?: AppLanguage.SYSTEM

    /** Pre-33 fallback; on 33+ the platform already applies the app locale, so [base] is returned unchanged. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = savedLanguage(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Applies [language] after the user changed it. Returns true when the caller must recreate its Activity
     * (pre-33); on 33+ the platform recreates it by itself.
     */
    fun apply(context: Context, language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val wanted = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            if (manager.applicationLocales != wanted) manager.applicationLocales = wanted
            return false
        }
        return true
    }

    /** Language the current process runs with (for pages generated in native code, e.g. error pages). */
    fun currentLanguage(): String = Locale.getDefault().language
}
