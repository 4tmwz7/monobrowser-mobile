package com.voluene.monobrowser.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
    /** Bottom sheet: slides down. */
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
    val edge = remember { intArrayOf(BackEventCompat.EDGE_LEFT) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                edge[0] = event.swipeEdge
                progress.snapTo(BackGesture.ease(event.progress))
            }
            back()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, spring()) }
            throw e
        }
    }
    val outline = MaterialTheme.colorScheme.outline
    val view = LocalView.current
    val pageShape = remember(view) {
        val radius = ConcentricCorners.displayCorners(view).maxOfOrNull { it?.radius ?: 0f } ?: 0f
        if (radius > 0f) RoundedCornerShape(radius) else RectangleShape
    }
    // Swallows touches that the content does not use, so nothing underneath reacts to them.
    Box(modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        // Dim what is revealed underneath (only while the gesture runs).
        if (style == BackStyle.PAGE && progress.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f * progress.value)))
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
                        shape = pageShape
                        clip = true
                    }
                    BackStyle.SHEET -> {
                        translationY = p * size.height * 0.35f
                        alpha = 1f - p * 0.4f
                    }
                }
            }.then(
                // The shrinking page gets a hard edge, like a card lifted off the stack.
                if (style == BackStyle.PAGE && progress.value > 0f) Modifier.border(2.dp, outline, pageShape) else Modifier,
            ),
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

    /** Horizontal shift as a fraction of the width, away from the swiping edge. */
    fun shift(progress: Float): Float = 0.05f * progress.coerceIn(0f, 1f)
}
