package com.voluene.monobrowser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import com.voluene.monobrowser.MainActivity
import com.voluene.monobrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Running downloads in one ongoing notification with a progress bar and the percentage. On Android 16+ it is a
 * Live Update (promoted ongoing notification, percentage as its status chip), which One UI shows in the Now Bar
 * and on the lock screen. Polls the system DownloadManager once a second while something downloads and stops
 * when nothing does. When the app may not post notifications, the system's download notification is used instead
 * (see [ownNotification]).
 */
class DownloadProgressService : Service() {
    private val scope = MainScope()
    private val repository by lazy { DownloadRepository(this) }
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        scope.launch {
            while (isActive) {
                val active = withContext(Dispatchers.IO) { runCatching { repository.items().filter { it.isActive } }.getOrDefault(emptyList()) }
                if (active.isEmpty()) break
                show(DownloadProgress.summary(active))
                delay(POLL_MS)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: longArrayOf()
            scope.launch(Dispatchers.IO) { ids.forEach { runCatching { repository.cancel(it) } } }
        }
        // Foreground right away (the first poll has not run yet).
        if (!foreground) show(DownloadProgress.Summary("", 0, 0, -1, emptyList()))
        return START_NOT_STICKY
    }

    private fun show(summary: DownloadProgress.Summary) {
        val title = when {
            summary.ids.size > 1 -> getString(R.string.downloads_count, summary.ids.size)
            summary.title.isNotBlank() -> summary.title
            else -> getString(R.string.menu_downloads)
        }
        val text = if (summary.total > 0) {
            getString(R.string.downloads_progress, Formatter.formatShortFileSize(this, summary.done), Formatter.formatShortFileSize(this, summary.total))
        } else {
            Formatter.formatShortFileSize(this, summary.done)
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(summary.percent < 0).setProgress(summary.percent.coerceAtLeast(0)))
            if (summary.percent >= 0) builder.setShortCriticalText("${summary.percent}%")
        } else {
            builder.setProgress(100, summary.percent.coerceAtLeast(0), summary.percent < 0)
            if (summary.percent >= 0) builder.setSubText("${summary.percent}%")
        }
        if (summary.ids.isNotEmpty()) {
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, DownloadProgressService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_IDS, summary.ids.toLongArray()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.downloads_cancel), cancel).build())
        }
        // Ask to be shown as a Live Update (status bar chip, Now Bar); the key NotificationCompat uses as well.
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        // One UI fills its Now Bar and Live Notification card from its own extras, not from the Android ones.
        if (SamsungLive.available) builder.extras.putAll(SamsungLive.progressExtras(this, title, text, summary.percent, R.drawable.ic_notification_download))
        val notification = builder.build()
        if (!foreground) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads_live"
        private const val NOTIFICATION_ID = 43
        private const val POLL_MS = 1_000L
        private const val ACTION_CANCEL = "com.voluene.monobrowser.download.CANCEL"
        private const val EXTRA_IDS = "ids"

        /**
         * Our notification shows the progress (the system's only reports the result) when the app may post
         * notifications; otherwise the system's download notification, which needs no permission, shows it.
         */
        fun ownNotification(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

        fun start(context: Context) {
            if (!ownNotification(context)) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, DownloadProgressService::class.java)) }
        }
    }
}

/** Pure parts (unit-tested). */
object DownloadProgress {
    /** All running downloads in one line: first title, bytes, percent (-1 = unknown size). */
    data class Summary(val title: String, val done: Long, val total: Long, val percent: Int, val ids: List<Long>)

    fun summary(items: List<DownloadItem>): Summary {
        val done = items.sumOf { it.downloadedBytes.coerceAtLeast(0) }
        val known = items.all { it.totalBytes > 0 }
        val total = if (known) items.sumOf { it.totalBytes } else 0L
        val percent = if (known && total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        return Summary(items.firstOrNull()?.title.orEmpty(), done, total, percent, items.map { it.id })
    }
}

/**
 * Samsung One UI Live Notifications (Now Bar, lock screen, status bar chip): One UI shows its own fields, not the
 * Android 16 Live Update ones; without them the Now Bar shows only the app name. Undocumented keys, as used by
 * other apps; ignored elsewhere. The manifest declares `com.samsung.android.support.ongoing_activity`.
 */
object SamsungLive {
    val available: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private const val STYLE_PROGRESS = 2

    /** [percent] -1 = unknown size (no bar, the bytes only). */
    fun progressExtras(context: Context, title: String, text: String, percent: Int, icon: Int): android.os.Bundle =
        android.os.Bundle().apply {
            val chip = if (percent >= 0) "$percent%" else text
            val detail = if (percent >= 0) "$percent%  \u00b7  $text" else text
            putInt("android.ongoingActivityNoti.style", STYLE_PROGRESS)
            putString("android.ongoingActivityNoti.primaryInfo", title)
            putString("android.ongoingActivityNoti.secondaryInfo", detail)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", title)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", detail)
            putString("android.ongoingActivityNoti.chipExpandedText", chip)
            putParcelable("android.ongoingActivityNoti.chipIcon", android.graphics.drawable.Icon.createWithResource(context, icon))
            if (percent >= 0) {
                putInt("android.ongoingActivityNoti.progress", percent)
                putInt("android.ongoingActivityNoti.progressMax", 100)
            }
        }
}
