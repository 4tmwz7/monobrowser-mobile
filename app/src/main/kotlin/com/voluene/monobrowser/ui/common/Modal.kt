package com.voluene.monobrowser.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a modal's content can use: [BoxScope] for alignment plus motion modifiers bound to the modal's visibility
 * and to the predictive back gesture. All of them only touch the graphics layer, so animating costs no layout
 * or recomposition per frame and runs at the display's full refresh rate.
 */
class ModalScope internal constructor(
    box: BoxScope,
    private val visibility: AnimatedVisibilityScope,
    private val back: Animatable<Float, *>,
) : BoxScope by box {
    /** Bottom sheet: rises from below; during the back gesture it shrinks towards the bottom edge. */
    fun Modifier.sheetMotion(): Modifier = with(visibility) {
        this@sheetMotion.animateEnterExit(
            enter = slideInVertically(Motion.slideSpring) { it } + fadeIn(Motion.enterTween(Motion.FADE_IN_MS)),
            exit = slideOutVertically(Motion.exitTween(240)) { it } + fadeOut(Motion.exitTween(240)),
        )
    }.graphicsLayer {
        val p = back.value
        if (p == 0f) return@graphicsLayer
        transformOrigin = TransformOrigin(0.5f, 1f)
        scaleX = 1f - 0.16f * p
        scaleY = 1f - 0.1f * p
        translationY = p * 40.dp.toPx()
    }

    /** Centered dialog: grows in with a soft spring, shrinks and fades out. */
    fun Modifier.dialogMotion(): Modifier = with(visibility) {
        this@dialogMotion.animateEnterExit(
            enter = scaleIn(Motion.scaleSpring, initialScale = 0.86f) + fadeIn(Motion.enterTween(Motion.FADE_IN_MS)),
            exit = scaleOut(Motion.exitTween(Motion.EXIT_MS), targetScale = 0.92f) + fadeOut(Motion.exitTween(160)),
        )
    }.graphicsLayer {
        val p = back.value
        if (p == 0f) return@graphicsLayer
        scaleX = 1f - 0.16f * p
        scaleY = 1f - 0.16f * p
        alpha = 1f - 0.3f * p
    }

    /** Items of a sheet or list arriving one after another ([index] from the top, small delay each). */
    fun Modifier.stagger(index: Int): Modifier = with(visibility) {
        this@stagger.animateEnterExit(
            enter = fadeIn(Motion.enterTween(260, delay = 50 + 22 * index.coerceAtMost(12))) +
                slideInVertically(Motion.enterTween(380, delay = 50 + 22 * index.coerceAtMost(12))) { it / 3 },
            exit = ExitTransition.None,
        )
    }
}

/**
 * Hosts a modal (dialog, sheet): fades the scrim, runs its content's motion, closes on a tap outside and follows
 * the predictive back gesture (also on dialogs inside full-screen pages, which used to close the page instead).
 *
 * Inside [Presence] (or the overlay host) the modal also animates out when the caller removes it. Used on its own it
 * animates in, and on a tap outside or back it plays the exit before calling [onDismiss].
 */
@Composable
internal fun ModalHost(onDismiss: (() -> Unit)?, modifier: Modifier, content: @Composable ModalScope.() -> Unit) {
    val outer = LocalModalScope.current
    if (outer != null) {
        // The scope is used up here: a dialog opened from inside this one gets its own.
        CompositionLocalProvider(LocalModalScope provides null) { ModalBody(outer, onDismiss, onDismiss, modifier, content) }
        return
    }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    if (!state.targetState && state.isIdle && !state.currentState) {
        LaunchedEffect(Unit) { dismiss?.invoke() }
    }
    AnimatedVisibility(state, enter = EnterTransition.None, exit = ExitTransition.None) {
        ModalBody(this, onDismiss, if (onDismiss == null) null else ({ state.targetState = false }), modifier, content)
    }
}

@Composable
private fun ModalBody(
    visibility: AnimatedVisibilityScope,
    onDismiss: (() -> Unit)?,
    requestDismiss: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable ModalScope.() -> Unit,
) {
    val leaving = visibility.transition.targetState == EnterExitState.PostExit
    val close by rememberUpdatedState(requestDismiss)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = onDismiss != null && !leaving) { events ->
        try {
            events.collect { progress.snapTo(BackGesture.ease(it.progress)) }
            close?.invoke()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, Motion.settleSpring) }
            throw e
        }
    }
    val source = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize().clickable(interactionSource = source, indication = null) { if (!leaving) close?.invoke() }) {
        with(visibility) {
            Box(
                Modifier.matchParentSize()
                    .animateEnterExit(enter = fadeIn(Motion.enterTween(260)), exit = fadeOut(Motion.exitTween(220)))
                    .graphicsLayer { alpha = 1f - 0.5f * progress.value }
                    .background(Color.Black.copy(alpha = 0.55f)),
            )
        }
        ModalScope(this, visibility, progress).content()
        // Nothing reacts to taps while the modal is on its way out.
        if (leaving) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
