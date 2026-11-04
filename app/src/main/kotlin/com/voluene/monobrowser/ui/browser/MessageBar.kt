package com.voluene.monobrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.voluene.monobrowser.ui.common.Motion

/** How long a message stays; messages with an action stay a little longer. */
const val MESSAGE_MS = 3_500L
const val MESSAGE_ACTION_MS = 5_000L

/**
 * [MessageBar] with motion: rises from the toolbar side with a soft spring, a newer message replaces the old one
 * by sliding over it, and dismissal fades it back towards the toolbar.
 */
@Composable
fun AnimatedMessageBar(message: TabManager.UiMessage?, onDismiss: (Long) -> Unit, fromTop: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = message,
        contentKey = { it?.id },
        transitionSpec = {
            val sign = if (fromTop) -1 else 1
            (slideInVertically(Motion.slideSpring) { sign * it } + fadeIn(Motion.enterTween(Motion.FADE_IN_MS)) +
                scaleIn(Motion.scaleSpring, initialScale = 0.94f)) togetherWith
                (fadeOut(Motion.exitTween(150)) + scaleOut(Motion.exitTween(), targetScale = 0.94f)) using null
        },
        modifier = modifier,
        label = "message",
    ) { m -> if (m != null) MessageBar(m, onDismiss) }
}

/**
 * A message above the toolbar (inverted bar): text and an optional action ("Open", "Undo", "Switch").
 * Goes away by itself, on the action, or when swiped sideways (it follows the finger and flies off).
 */
@Composable
fun MessageBar(message: TabManager.UiMessage, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(message.id) {
        delay(if (message.action != null) MESSAGE_ACTION_MS else MESSAGE_MS)
        onDismiss(message.id)
    }
    val corners = rememberScreenAwareShape()
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (kotlin.math.abs(offset.value) / size.width).coerceIn(0f, 1f) * 0.8f
            }
            .then(corners.modifier).clip(corners.shape).background(scheme.onBackground, corners.shape)
            .pointerInput(message.id) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onHorizontalDrag = { change, delta ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { offset.snapTo(offset.value + delta) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, Motion.settleSpring) } },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().x
                        val width = size.width.toFloat()
                        val fling = kotlin.math.abs(velocity) > 1200f
                        if (fling || kotlin.math.abs(offset.value) > width * 0.35f) {
                            val direction = if (fling) kotlin.math.sign(velocity) else kotlin.math.sign(offset.value)
                            scope.launch {
                                offset.animateTo(direction * width, tween(180, easing = Motion.EmphasizedAccelerate), initialVelocity = velocity)
                                onDismiss(message.id)
                            }
                        } else {
                            scope.launch { offset.animateTo(0f, Motion.settleSpring, initialVelocity = velocity) }
                        }
                    },
                )
            }
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message.text, color = scheme.background, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        val label = message.actionLabel
        val action = message.action
        if (label != null && action != null) {
            Text(
                label.uppercase(), color = scheme.background, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clickable { onDismiss(message.id); action() }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 16.dp))
        }
    }
}
