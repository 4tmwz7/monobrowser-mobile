package com.voluene.monobrowser.ui.browser

import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import androidx.compose.ui.draw.clip
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.Tab
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.Scrim
import com.voluene.monobrowser.util.UrlUtils

/**
 * Glance: a quick preview of a link in a bottom sheet (~85% of the screen). The page runs in its own WebView
 * ([webView], same profile as the source tab); "Open in tab" hands that very WebView over to a new tab, so
 * nothing is reloaded. System back closes the sheet.
 */
@Composable
fun GlanceSheet(
    glance: Tab,
    webView: WebView?,
    onOpenInTab: () -> Unit,
    onOpenInBackground: () -> Unit,
    onClose: () -> Unit,
    loadProgress: () -> Int = { glance.progress },
) {
    val scheme = MaterialTheme.colorScheme
    // System back (with its predictive animation) is handled by the caller's PredictiveBackContainer.
    val corners = rememberScreenAwareShape()
    Scrim(onClose) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(HEIGHT_FRACTION)
                .then(corners.modifier).clip(corners.shape).background(scheme.background, corners.shape).border(1.dp, scheme.outline, corners.shape)
                .clickable(enabled = false) {},
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    if (glance.isPrivate) {
                        Text(stringResource(R.string.tab_private_badge), color = scheme.onBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        glance.title.ifBlank { UrlUtils.shortUrlLabel(glance.url) },
                        color = scheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        UrlUtils.shortUrlLabel(glance.url),
                        color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton(stringResource(R.string.glance_open_tab), onOpenInTab, Modifier.weight(1.2f), inverted = true)
                    MonoButton(stringResource(R.string.glance_open_background), onOpenInBackground, Modifier.weight(1f))
                    MonoButton(stringResource(R.string.action_close), onClose, Modifier.weight(0.8f))
                }
            }
            // Load progress: a hard-edged line instead of a spinner.
            val line = scheme.onBackground
            Box(
                Modifier.fillMaxWidth().height(2.dp).background(scheme.outlineVariant).drawBehind {
                    val p = loadProgress()
                    if (glance.isLoading && p in 0..99) drawRect(line, size = androidx.compose.ui.geometry.Size(size.width * p / 100f, size.height))
                },
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { FrameLayout(it) },
                update = { frame ->
                    if (webView != null && frame.getChildAt(0) !== webView) {
                        frame.removeAllViews()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        frame.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
            )
            Box(Modifier.navigationBarsPadding())
        }
    }
}

private const val HEIGHT_FRACTION = 0.85f
