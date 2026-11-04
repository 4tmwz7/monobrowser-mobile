package com.voluene.monobrowser.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How a screen reacts while the back gesture is in progress. */
enum class BackStyle {
    /** Full-screen page: shrinks and moves away from the swiping edge, revealing what is under it (system style). */
    PAGE,
    /** Bottom sheet: shrinks towards the bottom edge (sheets inside a Scrim do this themselves). */
    SHEET,
}

/**
 * Predictive back for an overlay: follows the system back gesture (Android 14+; on older versions back simply
 * runs [onBack]) and animates [content] with the gesture progress; releasing completes [onBack], cancelling
 * springs back. Back handlers inside [content] (sub-pages, dialogs) are registered later and take precedence.
 */
@Composable
fun PredictiveBackContainer(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: BackStyle = BackStyle.PAGE,
    content: @Composable () -> Unit,
) {
    val back by rememberUpdatedState(onBack)
    val progress = remember { Animatable(0f) }
    // Vertical finger travel since the gesture started (px); the page follows it a little, like the system's.
    val dy = remember { Animatable(0f) }
    val edge = remember { intArrayOf(BackEventCompat.EDGE_LEFT) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events ->
        var startY = Float.NaN
        try {
            events.collect { event ->
                edge[0] = event.swipeEdge
                if (startY.isNaN()) startY = event.touchY
                progress.snapTo(BackGesture.ease(event.progress))
                dy.snapTo(event.touchY - startY)
            }
            back()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, Motion.settleSpring) }
            scope.launch { dy.animateTo(0f, Motion.settleSpring) }
            throw e
        }
    }
    val view = LocalView.current
    val density = LocalDensity.current
    // The lifted page gets the screen's corner radius (a card of the same shape as the display), or 28 dp.
    val radius = remember(view) {
        ConcentricCorners.displayCorners(view).maxOfOrNull { it?.radius ?: 0f }?.takeIf { it > 0f } ?: with(density) { 28.dp.toPx() }
    }
    // Swallows touches that the content does not use, so nothing underneath reacts to them.
    Box(modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        // Dim what is revealed underneath (drawn only while the gesture runs; no recomposition per frame).
        if (style == BackStyle.PAGE) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val p = progress.value
                    if (p > 0f) drawRect(Color.Black.copy(alpha = 0.35f * p))
                },
            )
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = progress.value
                if (p == 0f) return@graphicsLayer
                when (style) {
                    BackStyle.PAGE -> {
                        val scale = BackGesture.scale(p)
                        scaleX = scale
                        scaleY = scale
                        val direction = if (edge[0] == BackEventCompat.EDGE_RIGHT) -1f else 1f
                        translationX = direction * BackGesture.shift(p) * size.width
                        translationY = BackGesture.follow(dy.value, size.height) * p
                        // Corners round off over the first part of the swipe (no hard switch from square to round).
                        shape = RoundedCornerShape(radius * (p * 4f).coerceAtMost(1f) / scale)
                        clip = true
                    }
                    BackStyle.SHEET -> {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleX = 1f - 0.1f * p
                        scaleY = 1f - 0.06f * p
                    }
                }
            },
        ) { content() }
    }
}

/** Gesture curves (pure; shared with tests). */
object BackGesture {
    /** Decelerating: the first part of the swipe already shows a clear response. */
    fun ease(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p)
    }

    /** 100 % -> 90 %, like the system's cross-activity animation. */
    fun scale(progress: Float): Float = 1f - 0.1f * progress.coerceIn(0f, 1f)

    /**
     * Vertical offset (px) for a finger that moved [dy] px since the gesture started: follows it with resistance,
     * at most 1/20 of the height (Material's predictive back).
     */
    fun follow(dy: Float, height: Float): Float {
        if (height <= 0f) return 0f
        val max = height / 20f
        val x = (dy / height).coerceIn(-1f, 1f)
        // Decelerating: quick response to small moves, never past [max].
        return max * kotlin.math.sign(x) * (1f - (1f - kotlin.math.abs(x)) * (1f - kotlin.math.abs(x)))
    }

    /** Horizontal shift as a fraction of the width, away from the swiping edge. */
    fun shift(progress: Float): Float = 0.05f * progress.coerceIn(0f, 1f)
}
