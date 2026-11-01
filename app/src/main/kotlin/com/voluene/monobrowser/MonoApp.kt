package com.voluene.monobrowser

import android.app.Application
import android.content.Context
import com.voluene.monobrowser.update.UpdateManager
import com.voluene.monobrowser.util.LocaleHelper

class MonoApp : Application() {
    /** Self-updater; process-wide so the install result receiver can reach it. */
    val updater: UpdateManager by lazy { UpdateManager(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base)) // Android < 13: per-app language
    }
}
