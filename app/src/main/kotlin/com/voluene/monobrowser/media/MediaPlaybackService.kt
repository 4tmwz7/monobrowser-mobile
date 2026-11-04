package com.voluene.monobrowser.media

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
import com.voluene.monobrowser.MainActivity
import com.voluene.monobrowser.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What a media notification shows; null = nothing to show (the service stops). */
data class MediaInfo(
    val title: String,
    val subtitle: String,
    val playing: Boolean,
    val canSkip: Boolean = true,
    /** Private tab: no title on the lock screen. */
    val private: Boolean = false,
    val rate: Float = 1f,
    /** Cover (shown in the media player, Now Bar, lock screen, watch). */
    val artwork: android.graphics.Bitmap? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** elapsedRealtime of [positionMs]; 0 = now. */
    val updatedAt: Long = 0,
    val canSeek: Boolean = false,
) {
    /** Where playback is now: [positionMs] moved on by the time since [updatedAt] while playing. */
    fun currentPositionMs(now: Long = android.os.SystemClock.elapsedRealtime()): Long =
        if (!playing || updatedAt <= 0) positionMs else positionMs + ((now - updatedAt).coerceAtLeast(0) * rate).toLong()
}

/**
 * Something that plays: read aloud, or media in a web page. Commands come from the notification, the lock
 * screen, Samsung's Now Bar and watch media controls (all of them talk to the media session).
 */
interface MediaSource {
    /** Changes to show (the current value is read with [current]). */
    val info: Flow<MediaInfo?>
    fun current(): MediaInfo?
    fun play()
    fun pause()
    fun skip(forward: Boolean)
    fun stop()
    fun seekTo(positionMs: Long) {}
}

/**
 * Publishes a [MediaSource] as media playback: a media session plus a MediaStyle notification (previous,
 * play / pause, next, stop) in a mediaPlayback foreground service, which also keeps the process alive while
 * something plays in the background. Android and One UI show such sessions on the lock screen, in quick
 * settings, in the Now Bar and on a paired Galaxy Watch. Stops itself when the source has nothing to show.
 */
abstract class MediaPlaybackService : Service() {
    protected abstract val source: MediaSource
    protected abstract val channelId: String
    protected abstract val channelName: Int
    protected abstract val notificationId: Int
    protected abstract val smallIcon: Int

    private val scope = MainScope()
    private lateinit var session: MediaSession
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, getString(channelName), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        session = MediaSession(this, javaClass.simpleName).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = source.play()
                override fun onPause() = source.pause()
                override fun onStop() = source.stop()
                override fun onSkipToNext() = source.skip(forward = true)
                override fun onSkipToPrevious() = source.skip(forward = false)
                override fun onSeekTo(pos: Long) = source.seekTo(pos)
            })
            setSessionActivity(openApp())
            isActive = true
        }
        scope.launch { source.info.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> source.play()
            ACTION_PAUSE -> source.pause()
            ACTION_NEXT -> source.skip(forward = true)
            ACTION_PREVIOUS -> source.skip(forward = false)
            ACTION_STOP -> source.stop()
            ACTION_REWIND, ACTION_FORWARD -> source.current()?.takeIf { it.canSeek }?.let { info ->
                val step = if (intent.action == ACTION_FORWARD) SEEK_STEP_MS else -SEEK_STEP_MS
                val target = (info.currentPositionMs() + step).coerceIn(0, info.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                source.seekTo(target)
            }
        }
        // A foreground service must show its notification right away.
        render(source.current(), force = true)
        return START_NOT_STICKY
    }

    private fun render(info: MediaInfo?, force: Boolean = false) {
        if (info == null) {
            if (force && !foreground) startForegroundCompat(notification(MediaInfo("", "", false))) // required once
            session.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        session.isActive = true
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, if (info.private) getString(R.string.tab_private_marker) else info.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, if (info.private) "" else info.subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, getString(R.string.app_name))
        if (info.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, info.durationMs)
        info.artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(metadata.build())
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (info.canSkip) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (info.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        val position = if (info.durationMs > 0) info.positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val state = if (info.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (info.playing) info.rate else 0f
        // The system moves the progress bar on from (position, time it was measured, speed).
        val playback = PlaybackState.Builder().setActions(actions)
        if (info.updatedAt > 0) playback.setState(state, position, speed, info.updatedAt) else playback.setState(state, position, speed)
        session.setPlaybackState(playback.build())
        val notification = notification(info)
        if (!foreground) startForegroundCompat(notification)
        else getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(notificationId, notification)
            }
            foreground = true
        }
    }

    private fun notification(info: MediaInfo): Notification {
        fun action(icon: Int, label: Int, action: String) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), command(action)).build()
        val builder = Notification.Builder(this, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(info.title.ifBlank { getString(channelName) })
            .setContentText(info.subtitle)
            .setContentIntent(openApp())
            .setDeleteIntent(command(ACTION_STOP))
            .setVisibility(if (info.private) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(info.playing)
        info.artwork?.takeIf { !info.private }?.let { builder.setLargeIcon(it) }
        builder
        if (info.private) {
            // What the lock screen shows instead of the page title.
            builder.setPublicVersion(
                Notification.Builder(this, channelId).setSmallIcon(smallIcon).setContentTitle(getString(R.string.tab_private_marker)).build(),
            )
        }
        val compact = ArrayList<Int>()
        if (info.canSkip) { builder.addAction(action(android.R.drawable.ic_media_previous, R.string.media_previous, ACTION_PREVIOUS)); compact.add(0) }
        builder.addAction(
            if (info.playing) action(android.R.drawable.ic_media_pause, R.string.media_pause, ACTION_PAUSE)
            else action(android.R.drawable.ic_media_play, R.string.media_play, ACTION_PLAY),
        )
        compact.add(compact.size)
        if (info.canSkip) { builder.addAction(action(android.R.drawable.ic_media_next, R.string.media_next, ACTION_NEXT)); compact.add(compact.size) }
        builder.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, R.string.media_stop, ACTION_STOP))
        builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact.toIntArray()))
        return builder.build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this, (javaClass.name + action).hashCode(), Intent(this, javaClass).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.voluene.monobrowser.media.PLAY"
        const val ACTION_PAUSE = "com.voluene.monobrowser.media.PAUSE"
        const val ACTION_NEXT = "com.voluene.monobrowser.media.NEXT"
        const val ACTION_PREVIOUS = "com.voluene.monobrowser.media.PREVIOUS"
        const val ACTION_STOP = "com.voluene.monobrowser.media.STOP"
        const val ACTION_REWIND = "com.voluene.monobrowser.media.REWIND"
        const val ACTION_FORWARD = "com.voluene.monobrowser.media.FORWARD"
        const val SEEK_STEP_MS = 10_000L
    }
}
