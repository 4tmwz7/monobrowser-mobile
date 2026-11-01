package com.voluene.monobrowser.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voluene.monobrowser.ui.common.Scrim
import com.voluene.monobrowser.ui.notes.MarkdownView
import com.voluene.monobrowser.update.ReleaseInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.update.UpdateError
import com.voluene.monobrowser.update.UpdateManager
import com.voluene.monobrowser.update.UpdatePhase
import com.voluene.monobrowser.update.UpdateStatus

@Composable
fun updateErrorText(error: UpdateError?): String = stringResource(
    when (error) {
        UpdateError.NETWORK, null -> R.string.update_error_network
        UpdateError.BAD_RESPONSE -> R.string.update_error_bad_response
        UpdateError.CHECKSUM_UNAVAILABLE -> R.string.update_error_checksum_unavailable
        UpdateError.UNTRUSTED_URL -> R.string.update_error_untrusted_url
        UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum_mismatch
        UpdateError.PACKAGE_REJECTED -> R.string.update_error_package_rejected
        UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature_mismatch
        UpdateError.STORAGE -> R.string.update_error_storage
        UpdateError.INSTALL_CANCELLED -> R.string.update_error_install_cancelled
        UpdateError.INSTALL_FAILED -> R.string.update_error_install_failed
    },
)

/** One line describing what the updater is doing right now (banner, Downloads entry, Settings → About). */
@Composable
fun updateStatusText(status: UpdateStatus): String {
    val version = status.release?.version.orEmpty()
    return when (status.phase) {
        UpdatePhase.IDLE -> stringResource(if (status.lastCheckFailed) R.string.update_status_check_failed else R.string.update_status_idle)
        UpdatePhase.CHECKING -> stringResource(R.string.update_status_checking)
        UpdatePhase.UP_TO_DATE -> stringResource(R.string.update_status_up_to_date)
        UpdatePhase.AVAILABLE -> stringResource(R.string.update_banner_available, version)
        UpdatePhase.NEEDS_PERMISSION -> stringResource(R.string.update_permission_needed)
        UpdatePhase.DOWNLOADING ->
            if (status.percent >= 0) stringResource(R.string.update_status_downloading, version, status.percent)
            else stringResource(R.string.update_status_downloading_unknown, version)
        UpdatePhase.VERIFYING -> stringResource(R.string.update_status_verifying)
        UpdatePhase.INSTALLING -> stringResource(R.string.update_status_installing)
        UpdatePhase.FAILED -> updateErrorText(status.error)
    }
}

/** Thin rectangular progress line (same look as the Downloads screen). */
@Composable
fun UpdateProgress(status: UpdateStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fraction = when (status.phase) {
        UpdatePhase.DOWNLOADING -> if (status.percent >= 0) status.percent / 100f else 0f
        UpdatePhase.VERIFYING, UpdatePhase.INSTALLING -> 1f
        else -> return
    }
    Box(modifier.fillMaxWidth().height(4.dp).border(1.dp, scheme.outline, RectangleShape)) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(scheme.onBackground))
    }
}

private fun openInstallSettings(context: Context, updater: UpdateManager) {
    try {
        context.startActivity(updater.installPermissionIntent())
    } catch (_: ActivityNotFoundException) {
        // No such settings screen on this device: nothing more we can offer.
    }
}

/** Action buttons for the current phase; shared by the banner and the Downloads entry. */
@Composable
private fun UpdateActions(updater: UpdateManager, status: UpdateStatus, onOpenUrl: (String) -> Unit, onNotes: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val w = Modifier.weight(1f).fillMaxHeight()
        when (status.phase) {
            UpdatePhase.AVAILABLE -> {
                MonoButton(stringResource(R.string.update_action_update), updater::startUpdate, w, inverted = true, horizontalPadding = 6.dp)
                if (onNotes != null) MonoButton(stringResource(R.string.update_action_notes), onNotes, w, horizontalPadding = 6.dp)
                MonoButton(stringResource(R.string.update_action_later), updater::dismissBanner, w, horizontalPadding = 6.dp)
            }
            UpdatePhase.NEEDS_PERMISSION -> {
                MonoButton(stringResource(R.string.update_action_open_settings), { openInstallSettings(context, updater) }, w, inverted = true)
                MonoButton(stringResource(R.string.update_action_later), updater::dismissBanner, w)
            }
            UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING ->
                MonoButton(stringResource(R.string.update_action_cancel), updater::cancel, w)
            UpdatePhase.FAILED -> {
                MonoButton(stringResource(R.string.update_action_retry), updater::startUpdate, w, inverted = true)
                MonoButton(stringResource(R.string.update_action_later), updater::dismissBanner, w)
            }
            else -> Unit
        }
    }
}

/** Non-blocking banner at the edge of the page: "MonoBrowser X.Y.Z is available - Update / What's new / Later". */
@Composable
fun UpdateBanner(updater: UpdateManager, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val status by updater.status.collectAsState()
    val dismissed by updater.dismissedVersion.collectAsState()
    val release = status.release ?: return
    val visible = when (status.phase) {
        UpdatePhase.AVAILABLE -> dismissed != release.version
        UpdatePhase.NEEDS_PERMISSION, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING, UpdatePhase.FAILED -> true
        else -> false
    }
    if (!visible) return
    var showNotes by remember { mutableStateOf(false) }
    if (showNotes) UpdateNotesSheet(updater, release, onOpenUrl, onDismiss = { showNotes = false })
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().background(scheme.background).border(1.dp, scheme.onBackground, RectangleShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            updateStatusText(status), color = scheme.onBackground, fontSize = 14.sp,
            fontWeight = if (status.phase == UpdatePhase.AVAILABLE) FontWeight.Bold else FontWeight.Normal,
        )
        UpdateProgress(status)
        UpdateActions(updater, status, onOpenUrl, onNotes = { showNotes = true })
    }
}

/** Downloads-screen entry for an update in progress (desktop records it as `kind: "update"`). */
@Composable
fun UpdateDownloadEntry(updater: UpdateManager, onOpenUrl: (String) -> Unit) {
    val status by updater.status.collectAsState()
    val release = status.release ?: return
    if (status.phase !in setOf(UpdatePhase.NEEDS_PERMISSION, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING, UpdatePhase.FAILED)) return
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.update_downloads_title, release.version), color = scheme.onBackground, fontWeight = FontWeight.Medium)
        Text(updateStatusText(status), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp)
        UpdateProgress(status)
        UpdateActions(updater, status, onOpenUrl, onNotes = null)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
}

/** "YYYY-MM-DD" from a GitHub ISO timestamp, or null when it does not look like one. */
internal fun releaseDate(publishedAt: String?): String? = publishedAt?.take(10)?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }

/** In-app release notes: monochrome rectangular bottom sheet with the rendered Markdown body. */
@Composable
fun UpdateNotesSheet(updater: UpdateManager, release: ReleaseInfo, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val date = releaseDate(release.publishedAt)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scrim(onDismiss) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                    .background(scheme.background).border(2.dp, scheme.outline, RectangleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.update_notes_title, release.version), color = scheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (date != null) Text(stringResource(R.string.update_notes_published, date), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (release.body.isBlank()) Text(stringResource(R.string.update_notes_empty), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 15.sp)
                    else MarkdownView(release.body, onLink = { onDismiss(); onOpenUrl(it) })
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val w = Modifier.weight(1f).fillMaxHeight()
                    MonoButton(stringResource(R.string.update_action_update), { onDismiss(); updater.startUpdate() }, w, inverted = true, horizontalPadding = 6.dp)
                    MonoButton(stringResource(R.string.update_notes_github), { onDismiss(); onOpenUrl(release.pageUrl) }, w, horizontalPadding = 6.dp)
                    MonoButton(stringResource(R.string.update_notes_close), onDismiss, w, horizontalPadding = 6.dp)
                }
            }
        }
    }
}
