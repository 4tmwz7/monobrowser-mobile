package com.voluene.monobrowser.ui.common

import android.os.Build
import androidx.annotation.VisibleForTesting
import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** One rounded corner of the display, in window pixels: the circle's centre and radius. */
data class DisplayCorner(val centerX: Float, val centerY: Float, val radius: Float)

/** Corner radii in pixels (top-left, top-right, bottom-right, bottom-left). */
data class CornerRadii(val topLeft: Float = 0f, val topRight: Float = 0f, val bottomRight: Float = 0f, val bottomLeft: Float = 0f) {
    val isSquare: Boolean get() = topLeft == 0f && topRight == 0f && bottomRight == 0f && bottomLeft == 0f
}

/**
 * Concentric corners: the UI is square, but a corner of an element that sits inside a rounded corner of the screen
 * is rounded with the screen's radius minus its distance from the screen edges, so its outline runs parallel to
 * the glass instead of being cut by it. Pure, unit-tested.
 */
object ConcentricCorners {
    /** Radii below this (px) are not worth rounding. */
    const val MIN_RADIUS = 3f

    /**
     * @param rect the element in window coordinates.
     * @param corners the display's corners (top-left, top-right, bottom-right, bottom-left); null = square corner.
     */
    fun radii(rect: Rect, corners: List<DisplayCorner?>): CornerRadii {
        fun one(corner: DisplayCorner?, x: Float, y: Float, left: Boolean, top: Boolean): Float {
            corner ?: return 0f
            // Distance of the element's corner from the two screen edges that form this display corner.
            val edgeX = if (left) corner.centerX - corner.radius else corner.centerX + corner.radius
            val edgeY = if (top) corner.centerY - corner.radius else corner.centerY + corner.radius
            val inset = maxOf(if (left) x - edgeX else edgeX - x, if (top) y - edgeY else edgeY - y)
            val r = corner.radius - inset.coerceAtLeast(0f)
            return if (r >= MIN_RADIUS) r else 0f
        }
        val r = CornerRadii(
            topLeft = one(corners.getOrNull(0), rect.left, rect.top, left = true, top = true),
            topRight = one(corners.getOrNull(1), rect.right, rect.top, left = false, top = true),
            bottomRight = one(corners.getOrNull(2), rect.right, rect.bottom, left = false, top = false),
            bottomLeft = one(corners.getOrNull(3), rect.left, rect.bottom, left = true, top = false),
        )
        // A corner can never be rounder than half the element.
        val cap = minOf(rect.width, rect.height) / 2f
        return CornerRadii(minOf(r.topLeft, cap), minOf(r.topRight, cap), minOf(r.bottomRight, cap), minOf(r.bottomLeft, cap))
    }

    /**
     * How far content must move in from the left and right of [rect] so that, at height [y] (window px), it is
     * not hidden behind the screen's rounded corners (plus [margin]). Zero where the screen edge is straight.
     */
    fun horizontalInsets(rect: Rect, corners: List<DisplayCorner?>, y: Float, bottom: Boolean, margin: Float = 0f): Pair<Float, Float> {
        fun glass(corner: DisplayCorner?, top: Boolean): Float? {
            corner ?: return null
            val dy = if (top) corner.centerY - y else y - corner.centerY
            if (dy <= 0f) return null // beside the straight part of the edge
            val d = minOf(dy, corner.radius)
            return kotlin.math.sqrt(corner.radius * corner.radius - d * d)
        }
        val top = !bottom
        val leftCorner = corners.getOrNull(if (top) 0 else 3)
        val rightCorner = corners.getOrNull(if (top) 1 else 2)
        val left = glass(leftCorner, top)?.let { half -> (leftCorner!!.centerX - half) - rect.left + margin } ?: 0f
        val right = glass(rightCorner, top)?.let { half -> rect.right - (rightCorner!!.centerX + half) + margin } ?: 0f
        return left.coerceAtLeast(0f) to right.coerceAtLeast(0f)
    }

    /** Replaces the device's corners in screenshot tests (null = ask the window). */
    @VisibleForTesting
    var testCorners: ((width: Int, height: Int) -> List<DisplayCorner?>)? = null

    /** The display's rounded corners as seen by [view]'s window (Android 12+; empty before that). */
    fun displayCorners(view: View): List<DisplayCorner?> {
        testCorners?.let { return it(view.rootView.width, view.rootView.height) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val insets = view.rootWindowInsets ?: return emptyList()
        return listOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT,
        ).map { position ->
            insets.getRoundedCorner(position)?.takeIf { it.radius > 0 }
                ?.let { DisplayCorner(it.center.x.toFloat(), it.center.y.toFloat(), it.radius.toFloat()) }
        }
    }
}

/** A shape that follows the screen's rounded corners wherever the element touches them (see [ConcentricCorners]). */
@Stable
class ScreenAwareShape internal constructor(private val view: View) {
    var radii by mutableStateOf(CornerRadii())
        private set

    /** Square unless a corner reaches into a rounded screen corner. */
    val shape: Shape
        get() = radii.let {
            if (it.isSquare) RectangleShape
            else AbsoluteRoundedCornerShape(CornerSize(it.topLeft), CornerSize(it.topRight), CornerSize(it.bottomRight), CornerSize(it.bottomLeft))
        }

    /** Put it before the background/border/clip that use [shape]. */
    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates ->
        val next = ConcentricCorners.radii(coordinates.boundsInWindow(), ConcentricCorners.displayCorners(view))
        if (next != radii) radii = next
    }
}

/**
 * Horizontal padding that keeps a full-width bar's content (buttons at the far left and right) out of the screen's
 * rounded corners: measured at [sampleFraction] of the bar's height from its screen-edge side. Zero on square
 * screens, older Android and wherever the corners are already clear.
 */
@Composable
fun Modifier.cornerSafeHorizontalPadding(fromBottom: Boolean, sampleFraction: Float = 0.15f, margin: androidx.compose.ui.unit.Dp = 2.dp): Modifier {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var insets by remember { mutableStateOf(0f to 0f) }
    return this
        .onGloballyPositioned { coordinates ->
            val rect = coordinates.boundsInWindow()
            val y = if (fromBottom) rect.bottom - rect.height * sampleFraction else rect.top + rect.height * sampleFraction
            val next = ConcentricCorners.horizontalInsets(rect, ConcentricCorners.displayCorners(view), y, fromBottom, with(density) { margin.toPx() })
            if (next != insets) insets = next
        }
        .padding(start = with(density) { insets.first.toDp() }, end = with(density) { insets.second.toDp() })
}

@Composable
fun rememberScreenAwareShape(): ScreenAwareShape {
    val view = LocalView.current
    return remember(view) { ScreenAwareShape(view) }
}
