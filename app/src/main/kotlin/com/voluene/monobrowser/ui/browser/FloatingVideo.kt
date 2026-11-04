package com.voluene.monobrowser.ui.browser

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.voluene.monobrowser.R
import com.voluene.monobrowser.ui.common.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The floating video window over the browser: the playing tab's page with its video filling it. Drag it anywhere,
 * it settles into the nearest corner (a fling carries it); a tap shows the buttons (back to the tab, play / pause,
 * close). With [fill] (the app itself is a picture-in-picture window) it covers everything and has no buttons.
 */
@Composable
fun FloatingVideo(
    webView: WebView,
    aspectRatio: Float,
    playing: Boolean,
    fill: Boolean,
    barAtTop: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
) {
    val frame: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(
            modifier = modifier,
            factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
            update = { layout ->
                if (layout.getChildAt(0) !== webView) {
                    layout.removeAllViews()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    layout.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            onRelease = { it.removeAllViews() },
        )
    }
    if (fill) {
        frame(Modifier.fillMaxSize())
        return
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.56f, 2.39f) ?: (16f / 9f)
        val width = minOf(maxWidth * (if (ratio >= 1f) 0.62f else 0.4f), 380.dp)
        val height = width / ratio
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val w = with(density) { width.toPx() }
        val h = with(density) { height.toPx() }
        // Corners: 0 top-start, 1 top-end, 2 bottom-start, 3 bottom-end; clear of the address bar.
        val barGap = with(density) { 64.dp.toPx() }
        fun corner(i: Int) = Offset(
            if (i % 2 == 0) 0f else areaW - w,
            if (i < 2) (if (barAtTop) barGap else 0f) else areaH - h - (if (barAtTop) 0f else barGap),
        )
        var cornerIndex by remember { mutableIntStateOf(3) }
        val position = remember { Animatable(corner(3), Offset.VectorConverter) }
        // Size changes (rotation, another video): back into its corner.
        LaunchedEffect(areaW, areaH, w, h) { position.animateTo(corner(cornerIndex), spring(dampingRatio = 0.85f, stiffness = 380f)) }
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 420f)) }
        var controls by remember { mutableStateOf(false) }
        var touch by remember { mutableIntStateOf(0) }
        LaunchedEffect(controls, touch) { if (controls) { delay(3_000); controls = false } }
        val scope = rememberCoroutineScope()
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .size(width, height)
                .graphicsLayer {
                    val a = appear.value
                    scaleX = 0.6f + 0.4f * a
                    scaleY = 0.6f + 0.4f * a
                    alpha = a.coerceIn(0f, 1f)
                }
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.35f), shape),
        ) {
            frame(Modifier.fillMaxSize())
            // Touches never reach the page: the window is moved and controlled here.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controls = !controls; touch++ }, onDoubleTap = { onExpand() })
                    }
                    .pointerInput(areaW, areaH, w, h) {
                        val tracker = VelocityTracker()
                        detectDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDrag = { change, drag ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                scope.launch {
                                    val p = position.value + drag
                                    position.snapTo(Offset(p.x.coerceIn(-w * 0.3f, areaW - w * 0.7f), p.y.coerceIn(0f, areaH - h)))
                                }
                            },
                            onDragEnd = {
                                val v = tracker.calculateVelocity()
                                // Where a fling would carry it decides the corner.
                                val aim = position.value + Offset(v.x, v.y) * 0.18f
                                val right = aim.x + w / 2 > areaW / 2
                                val bottom = aim.y + h / 2 > areaH / 2
                                cornerIndex = (if (bottom) 2 else 0) + (if (right) 1 else 0)
                                scope.launch {
                                    position.animateTo(corner(cornerIndex), spring(dampingRatio = 0.8f, stiffness = 320f), Offset(v.x, v.y))
                                }
                            },
                        )
                    },
            )
            AnimatedVisibility(controls, enter = fadeIn(Motion.enterTween(150)), exit = fadeOut(Motion.exitTween(200))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                    IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.TopStart)) {
                        Icon(com.voluene.monobrowser.ui.browser.MenuIcons.PictureInPicture, stringResource(R.string.floating_expand), tint = Color.White)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = Color.White)
                    }
                    IconButton(onClick = { onPlayPause(); touch++ }, modifier = Modifier.align(Alignment.Center).size(56.dp)) {
                        Icon(
                            if (playing) com.voluene.monobrowser.ui.browser.MenuIcons.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.media_pause else R.string.media_play), tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}
