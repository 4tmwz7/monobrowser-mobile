package com.voluene.monobrowser.ui.downloads

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.download.DownloadItem
import com.voluene.monobrowser.download.DownloadRepository
import com.voluene.monobrowser.download.DownloadStatus
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen(downloads: DownloadRepository, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var refresh by remember { mutableStateOf(0) }

    // The system DownloadManager has no change callback for progress; poll while the screen is open.
    LaunchedEffect(refresh) {
        while (true) {
            items = withContext(Dispatchers.IO) { runCatching { downloads.items() }.getOrDefault(emptyList()) }
            delay(1000)
        }
    }

    fun act(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        refresh++
    }

    ScreenScaffold(
        title = stringResource(R.string.downloads_title),
        onClose = onClose,
        actions = {
            MonoButton(
                stringResource(R.string.downloads_clear_finished), { act { downloads.forgetFinished() } },
                Modifier.padding(end = 8.dp), enabled = items.orEmpty().any { !it.isActive },
            )
        },
    ) {
        if (items != null && items.orEmpty().isEmpty()) {
            Text(stringResource(R.string.downloads_empty), color = scheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            items(items.orEmpty(), key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, color = scheme.onBackground, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusText(item), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp)
                    if (item.isActive) {
                        Box(Modifier.fillMaxWidth().height(4.dp).border(1.dp, scheme.outline, RectangleShape)) {
                            val fraction = if (item.percent >= 0) item.percent / 100f else 0f
                            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(scheme.onBackground))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            item.isActive -> MonoButton(stringResource(R.string.download_cancel), { act { downloads.cancel(item.id) } })
                            item.status == DownloadStatus.DONE -> {
                                MonoButton(stringResource(R.string.download_open), {
                                    val intent = downloads.openIntent(item.id)
                                    try {
                                        if (intent == null) throw ActivityNotFoundException()
                                        context.startActivity(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(context, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }, inverted = true)
                                MonoButton(stringResource(R.string.download_remove), { act { downloads.forget(item.id) } })
                            }
                            else -> MonoButton(stringResource(R.string.download_remove), { act { downloads.forget(item.id) } })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun statusText(item: DownloadItem): String = when (item.status) {
    DownloadStatus.PENDING -> stringResource(R.string.download_status_pending)
    DownloadStatus.RUNNING -> if (item.percent >= 0) stringResource(R.string.download_status_running, item.percent) else stringResource(R.string.download_status_running_unknown)
    DownloadStatus.PAUSED -> stringResource(R.string.download_status_paused)
    DownloadStatus.DONE -> stringResource(R.string.download_status_done)
    DownloadStatus.FAILED -> stringResource(R.string.download_status_failed)
}
