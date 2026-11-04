package com.voluene.monobrowser

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.voluene.monobrowser.browser.IncomingIntent

/** Quick Settings tile: one tap opens a new private tab (after unlocking the phone when it is locked). */
class PrivateTabTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::openPrivateTab) else openPrivateTab()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openPrivateTab() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(IncomingIntent.ACTION_NEW_PRIVATE_TAB)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
