package com.voluene.monobrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.SitePermission
import com.voluene.monobrowser.browser.SitePermissionStore
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoSwitch
import com.voluene.monobrowser.ui.common.Scrim
import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The lock in the address bar: connection state (and certificate issuer), ad blocking and desktop site for this
 * site, its remembered permissions (each can be reset), copy and share.
 */
@Composable
fun SiteInfoSheet(
    info: TabManager.SiteInfo,
    blockedCount: Int,
    adblockEnabled: Boolean,
    adblockOnForSite: Boolean,
    desktopMode: Boolean,
    onToggleAdblock: () -> Unit,
    onToggleDesktop: () -> Unit,
    onResetPermission: (SitePermission) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = scheme.onBackground
    val corners = rememberScreenAwareShape()
    Scrim(onDismiss) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(corners.modifier).sheetMotion().clip(corners.shape)
                .background(scheme.background, corners.shape).border(1.dp, scheme.outline, corners.shape)
                .clickable(enabled = false) {}.navigationBarsPadding(),
        ) {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(info.host, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Row(verticalAlignment = Alignment.Top) {
                    Icon(if (info.secure) Icons.Filled.Lock else Icons.Filled.Warning, contentDescription = null, tint = fg, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(if (info.secure) R.string.site_secure else R.string.site_not_secure), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(if (info.secure) R.string.site_secure_hint else R.string.site_not_secure_hint),
                            color = fg.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp,
                        )
                        val issuer = info.certificateIssuer
                        val until = info.certificateValidUntil
                        if (info.secure && issuer != null && until != null) {
                            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(until))
                            Text(stringResource(R.string.site_certificate, issuer, date), color = fg.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                ToggleRow(
                    title = stringResource(R.string.site_adblock),
                    subtitle = if (adblockEnabled) stringResource(R.string.site_adblock_count, blockedCount) else stringResource(R.string.site_adblock_global_off),
                    checked = adblockEnabled && adblockOnForSite,
                    enabled = adblockEnabled,
                    onToggle = onToggleAdblock,
                )
                ToggleRow(stringResource(R.string.menu_desktop_site), null, desktopMode, true, onToggleDesktop)

                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                Text(stringResource(R.string.site_permissions).uppercase(), color = fg.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (info.permissions.isEmpty()) {
                    Text(stringResource(R.string.site_permissions_none), color = fg.copy(alpha = 0.7f), fontSize = 13.sp)
                } else {
                    info.permissions.forEach { decision -> PermissionRow(decision, onResetPermission) }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton(stringResource(R.string.address_copy), { onDismiss(); onCopy() }, Modifier.weight(1f))
                    MonoButton(stringResource(R.string.address_share), { onDismiss(); onShare() }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = fg.copy(alpha = if (enabled) 1f else 0.45f), fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = fg.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        MonoSwitch(checked, { if (enabled) onToggle() })
    }
}

@Composable
private fun PermissionRow(decision: SitePermissionStore.Decision, onReset: (SitePermission) -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    val name = when (decision.permission) {
        SitePermission.CAMERA -> R.string.permission_camera
        SitePermission.MICROPHONE -> R.string.permission_microphone
        SitePermission.LOCATION -> R.string.permission_location
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(name), color = fg, fontSize = 15.sp)
            Text(
                stringResource(if (decision.allowed) R.string.site_permission_allowed else R.string.site_permission_blocked),
                color = fg.copy(alpha = 0.6f), fontSize = 13.sp,
            )
        }
        MonoButton(stringResource(R.string.site_permission_reset), { onReset(decision.permission) })
    }
}
