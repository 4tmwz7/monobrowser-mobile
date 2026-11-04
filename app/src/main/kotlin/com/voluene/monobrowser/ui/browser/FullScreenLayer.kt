package com.voluene.monobrowser.ui.browser

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.voluene.monobrowser.browser.TabManager

/**
 * A page element in full screen (usually a video): drawn over everything on black, system bars hidden (they come
 * back with a swipe from the edge), the screen stays on. Back leaves full screen.
 */
@Composable
fun FullScreenLayer(fullScreen: TabManager.FullScreen, onExit: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onExit)
    DisposableEffect(fullScreen) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        fullScreen.view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            fullScreen.view.keepScreenOn = false
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
        update = { frame ->
            if (frame.getChildAt(0) !== fullScreen.view) {
                frame.removeAllViews()
                (fullScreen.view.parent as? ViewGroup)?.removeView(fullScreen.view)
                frame.addView(fullScreen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { frame -> frame.removeAllViews() },
    )
}
