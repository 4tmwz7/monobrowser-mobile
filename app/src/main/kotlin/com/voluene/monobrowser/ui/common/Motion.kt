package com.voluene.monobrowser.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset

/**
 * Motion tokens shared by every screen: Material 3 emphasized easing for things that enter or leave, springs for
 * things that follow a gesture or settle (they keep their velocity when interrupted, so nothing ever jumps).
 */
object Motion {
    /** Fast start, long gentle landing: things arriving on screen. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Slow start, quick exit: things leaving the screen. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val ENTER_MS = 320
    const val EXIT_MS = 200
    const val FADE_IN_MS = 180
    const val FADE_OUT_MS = 140

    /** Sheets and pages sliding in: no visible bounce, just a soft landing. */
    val slideSpring: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 420f)
    /** Dialogs and zooming pages: a hint of overshoot. */
    val scaleSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.78f, stiffness = 520f)
    /** Settling back after a cancelled gesture. */
    val settleSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

    fun <T> enterTween(duration: Int = ENTER_MS, delay: Int = 0) = tween<T>(duration, delay, EmphasizedDecelerate)
    fun <T> exitTween(duration: Int = EXIT_MS) = tween<T>(duration, easing = EmphasizedAccelerate)

    val fadeInQuick: EnterTransition get() = fadeIn(tween(FADE_IN_MS, easing = Standard))
    val fadeOutQuick: ExitTransition get() = fadeOut(tween(FADE_OUT_MS, easing = Standard))
}

/**
 * The visibility of the modal (dialog, sheet) that is being composed, so it can animate out as well as in.
 * Provided by [Presence] and by the overlay host; a [Scrim] without one animates in by itself.
 */
val LocalModalScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Keeps [content] on screen while it animates out after [value] became null: the last non-null value is shown
 * during the exit. Children animate with [ModalScope]'s motion modifiers ([Scrim], [MonoDialog], sheets).
 */
@Composable
fun <T : Any> Presence(value: T?, content: @Composable (T) -> Unit) {
    val held = remember { arrayOfNulls<Any>(1) }
    if (value != null) held[0] = value
    AnimatedVisibility(visible = value != null, enter = EnterTransition.None, exit = ExitTransition.None) {
        @Suppress("UNCHECKED_CAST")
        val shown = (value ?: held[0]) as T? ?: return@AnimatedVisibility
        CompositionLocalProvider(LocalModalScope provides this) { content(shown) }
    }
}

/** [Presence] for a flag. */
@Composable
fun Presence(visible: Boolean, content: @Composable () -> Unit) = Presence(if (visible) Unit else null) { content() }

/**
 * A page pushed over another one (settings sub-page, note editor): slides in from the end edge and back out,
 * and follows the predictive back gesture in between. [key] identifies the page; null shows nothing. With
 * [backEnabled] false the page does not take the back gesture (a page pushed over it does).
 */
@Composable
fun <T : Any> PushedPage(key: T?, onBack: () -> Unit, backEnabled: Boolean = true, content: @Composable (T) -> Unit) {
    val held = remember { arrayOfNulls<Any>(1) }
    if (key != null) held[0] = key
    AnimatedVisibility(
        visible = key != null,
        enter = slideInHorizontally(Motion.slideSpring) { it } + fadeIn(Motion.enterTween(Motion.FADE_IN_MS)),
        exit = slideOutHorizontally(Motion.exitTween(260)) { it / 3 } + fadeOut(Motion.exitTween(220)),
    ) {
        @Suppress("UNCHECKED_CAST")
        val shown = (key ?: held[0]) as T? ?: return@AnimatedVisibility
        androidx.compose.runtime.key(shown) {
            PredictiveBackContainer(onBack = onBack, enabled = key != null && backEnabled) { content(shown) }
        }
    }
}
