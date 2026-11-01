package com.voluene.monobrowser.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.voluene.monobrowser.MonoApp

/** Receives the `PackageInstaller` session result and hands it to [UpdateManager]. Not exported. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        (context.applicationContext as MonoApp).updater.onInstallStatus(status, message, confirm)
    }

    companion object {
        const val ACTION = "com.voluene.monobrowser.update.INSTALL_RESULT"
    }
}
