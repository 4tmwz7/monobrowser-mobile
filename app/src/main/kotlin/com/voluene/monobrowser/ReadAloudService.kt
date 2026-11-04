package com.voluene.monobrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.voluene.monobrowser.reader.ReadAloudState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Read aloud as media playback: a media session (title = article, artist = site) with previous / play-pause /
 * next paragraph and stop, shown as a media notification. Android and One UI pick that up everywhere media
 * goes: the lock screen, quick settings, Samsung's Now Bar (One UI 7+) and the media controls of a paired
 * Galaxy Watch. Runs while an article is loaded; stops when reading is stopped or reaches the end.
 */
class ReadAloudService : Service() {
    private val scope = MainScope()
    private val player get() = (application as MonoApp).readAloud
    private lateinit var session: MediaSession
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.reader_listen), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        session = MediaSession(this, "MonoBrowserReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.resume()
                override fun onPause() = player.pause()
                override fun onStop() = player.stop()
                override fun onSkipToNext() = player.skip(forward = true)
                override fun onSkipToPrevious() = player.skip(forward = false)
            })
            setSessionActivity(openApp())
            isActive = true
        }
        scope.launch { player.state.collect(::render) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> player.resume()
            ACTION_PAUSE -> player.pause()
            ACTION_NEXT -> player.skip(forward = true)
            ACTION_PREVIOUS -> player.skip(forward = false)
            ACTION_STOP -> player.stop()
        }
        // A foreground service must show its notification right away, also before the speech engine is ready.
        render(player.state.value, force = true)
        return START_NOT_STICKY
    }

    private fun render(state: ReadAloudState, force: Boolean = false) {
        if (state.articleUrl == null || state.finished) {
            if (force && !foreground) startForegroundCompat(notification(state)) // the system requires it once
            session.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.site)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, getString(R.string.app_name))
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(if (state.speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.rate)
                .build(),
        )
        val notification = notification(state)
        if (!foreground) startForegroundCompat(notification)
        else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun notification(state: ReadAloudState): Notification {
        fun action(icon: Int, label: Int, action: String) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), command(action)).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_read_aloud)
            .setContentTitle(state.title.ifBlank { getString(R.string.reader_listen) })
            .setContentText(state.site)
            .setContentIntent(openApp())
            .setDeleteIntent(command(ACTION_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(state.speaking)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.read_aloud_previous, ACTION_PREVIOUS))
            .addAction(
                if (state.speaking) action(android.R.drawable.ic_media_pause, R.string.reader_pause, ACTION_PAUSE)
                else action(android.R.drawable.ic_media_play, R.string.reader_listen, ACTION_PLAY),
            )
            .addAction(action(android.R.drawable.ic_media_next, R.string.read_aloud_next, ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, R.string.read_aloud_stop, ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, ReadAloudService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps reading (like other players); stop is in the notification.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 41
        const val ACTION_PLAY = "com.voluene.monobrowser.readaloud.PLAY"
        const val ACTION_PAUSE = "com.voluene.monobrowser.readaloud.PAUSE"
        const val ACTION_NEXT = "com.voluene.monobrowser.readaloud.NEXT"
        const val ACTION_PREVIOUS = "com.voluene.monobrowser.readaloud.PREVIOUS"
        const val ACTION_STOP = "com.voluene.monobrowser.readaloud.STOP"
    }
}
