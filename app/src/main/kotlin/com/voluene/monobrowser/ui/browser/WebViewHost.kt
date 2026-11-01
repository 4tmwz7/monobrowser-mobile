package com.voluene.monobrowser.ui.browser

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts a tab's WebView inside a [PullRefreshLayout] (pull-to-refresh only when the page is scrolled to the top;
 * the indicator is a thin line on the edge next to the toolbar). The WebView is re-parented when the active tab changes.
 */
@Composable
fun WebViewHost(webView: WebView?, onRefresh: () -> Unit, lineAtBottom: Boolean, modifier: Modifier = Modifier) {
    val refresh by rememberUpdatedState(onRefresh)
    val line = MaterialTheme.colorScheme.onBackground.toArgb()
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> PullRefreshLayout(context).also { it.onRefresh = { refresh() } } },
        update = { layout ->
            layout.lineColor = line
            layout.lineAtBottom = lineAtBottom
            if (webView != null && layout.getChildAt(0) !== webView) {
                layout.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                layout.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
    )
}
