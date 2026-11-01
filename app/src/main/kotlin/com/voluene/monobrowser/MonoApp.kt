package com.voluene.monobrowser

import android.app.Application
import android.content.Context
import com.voluene.monobrowser.util.LocaleHelper

class MonoApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base)) // Android < 13: per-app language
    }
}
