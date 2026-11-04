package com.voluene.monobrowser

import android.app.Application
import android.content.Context
import com.voluene.monobrowser.update.UpdateManager
import com.voluene.monobrowser.util.LocaleHelper

class MonoApp : Application() {
    /** Self-updater; process-wide so the install result receiver can reach it. */
    val updater: UpdateManager by lazy { UpdateManager(this) }

    /** Read aloud: process-wide, so reading continues after reader view closes (see ReadAloudService). */
    val readAloud: com.voluene.monobrowser.reader.ReadAloud by lazy { com.voluene.monobrowser.reader.ReadAloud(this) }

    /** Media playing in pages, for WebMediaService. */
    val webMedia: com.voluene.monobrowser.media.WebMediaHub by lazy { com.voluene.monobrowser.media.WebMediaHub(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base)) // Android < 13: per-app language
    }
}
