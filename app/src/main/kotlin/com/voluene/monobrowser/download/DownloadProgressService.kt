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
import androidx.annotation.RequiresApi
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
 * Android 16+: running downloads as a Live Update (promoted ongoing notification with a progress bar and the
 * percentage as its status chip), which One UI shows in the Now Bar. Polls the system DownloadManager once a
 * second while something downloads and stops when nothing does. Before Android 16 the system's own download
 * notification shows progress (DownloadRepository leaves it on there).
 */
@RequiresApi(36)
class DownloadProgressService : Service() {
    private val scope = MainScope()
    private val repository by lazy { DownloadRepository(this) }
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
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
        val style = Notification.ProgressStyle().setProgressIndeterminate(summary.percent < 0).setProgress(summary.percent.coerceAtLeast(0))
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setStyle(style)
        if (summary.percent >= 0) builder.setShortCriticalText("${summary.percent}%")
        if (summary.ids.isNotEmpty()) {
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, DownloadProgressService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_IDS, summary.ids.toLongArray()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.downloads_cancel), cancel).build())
        }
        // Ask to be shown as a Live Update (status bar chip, Now Bar); the key NotificationCompat uses as well.
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        val notification = builder.build()
        if (!foreground) {
            runCatching { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
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

        /** Live Updates exist from Android 16 on; before that the system download notification shows progress. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

        fun start(context: Context) {
            if (!supported) return
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
