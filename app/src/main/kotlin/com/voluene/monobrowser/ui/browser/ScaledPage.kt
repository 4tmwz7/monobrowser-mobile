package com.voluene.monobrowser.ui.browser

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize

/**
 * Lays the content out at [page] (the phone-sized page) and draws it scaled down to fit the space it gets,
 * centred: the picture-in-picture window shows the whole site made small, as it looked on the screen.
 * Only placement and a layer scale: the page keeps its layout (no reflow to the tiny window).
 */
fun Modifier.scaledPage(page: DpSize): Modifier = layout { measurable, constraints ->
    val pw = page.width.roundToPx().coerceAtLeast(1)
    val ph = page.height.roundToPx().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(pw, ph))
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else pw
    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else ph
    val scale = minOf(w / pw.toFloat(), h / ph.toFloat())
    layout(w, h) {
        placeable.placeWithLayer((w - pw) / 2, (h - ph) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}
