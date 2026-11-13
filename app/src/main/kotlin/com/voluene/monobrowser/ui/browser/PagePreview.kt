package com.voluene.monobrowser.ui.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.browser.Tab
import com.voluene.monobrowser.data.TopSites
import com.voluene.monobrowser.ui.start.monogram
import com.voluene.monobrowser.util.UrlUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the page swipe needs to draw other tabs' pages: thumbnails and the page theme of a tab. */
internal class PagePreviews(
    val thumbnail: (Long) -> ImageBitmap?,
    /** Whether the page of this tab is shown dark (its site's theme, else the global page theme). */
    val pageDark: (Tab) -> Boolean,
)

/**
 * A tab's page without its WebView: the page's top drawn at the page's own scale (the preview's width follows the
 * page width, whatever the bitmap's resolution; anything below it stays the page colour), greyed while the tab
 * sleeps. Without a bitmap, the page colour with the site's monogram and host (as a tile of the grid). A blank tab
 * / the "new tab" ([tab] = null) is a calm background. Not interactive, and nothing is decoded here: the bitmaps
 * are already in memory.
 */
@Composable
internal fun PagePreview(tab: Tab?, previews: PagePreviews, modifier: Modifier = Modifier, grey: Boolean = tab?.sleeping == true) {
    val scheme = MaterialTheme.colorScheme
    val blank = tab == null || tab.url.isBlank() || UrlUtils.isAboutBlankUrl(tab.url)
    if (blank) {
        // The start page of a blank tab is interactive and data driven: not redrawn for a preview, just its colour.
        Box(modifier.fillMaxSize().background(if (tab?.isPrivate == true) scheme.onBackground else scheme.background))
        return
    }
    val dark = previews.pageDark(tab!!)
    val bg = if (dark) Color.Black else Color.White
    val fg = if (dark) Color.White else Color.Black
    val thumbnail = previews.thumbnail(tab.id)
    Box(modifier.fillMaxSize().background(bg).clipToBounds()) {
        if (thumbnail != null) {
            // FillWidth: the preview is the page's top at the page's scale (aligned to the top, never zoomed past
            // 1:1 with the page and never stretched to the box's height, unlike a Crop) - the page does not jump
            // to another scale when the real one takes over. Short previews end on the page colour above.
            Image(
                thumbnail, contentDescription = null, contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter,
                colorFilter = if (grey) GreyScale else null, modifier = Modifier.fillMaxSize(),
            )
        } else {
            val key = TopSites.hostKey(tab.url)
            val host = UrlUtils.shortUrlLabel(tab.url).substringBefore('/')
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (key != null) monogram(TopSites.label(key)) else "+", color = fg.copy(alpha = 0.8f), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                if (host.isNotBlank()) {
                    Text(host, color = fg.copy(alpha = 0.6f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** Greyscale like the tab grid's previews of sleeping tabs. */
private val GreyScale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * The neighbour's page sliding in beside the current one while the bar is dragged: placed one travel away on the
 * side the finger comes from (the same maths as the bars). The state is read in the layer / when the side changes
 * only, never per frame in composition.
 */
@Composable
internal fun PageNeighbour(
    swipe: BarSwipeState,
    activeId: Long,
    previousTab: Tab?,
    nextTab: Tab?,
    previews: PagePreviews,
    gapPx: Float,
    modifier: Modifier = Modifier,
) {
    val side = swipe.shownSide(activeId)
    val incoming = BarSwipe.incoming(side, previousTab != null, nextTab != null)
    if (incoming == BarSwipe.Incoming.NONE) return
    PagePreview(
        tab = if (incoming == BarSwipe.Incoming.CARD) (if (side < 0) nextTab else previousTab) else null,
        previews = previews,
        modifier = modifier.graphicsLayer { translationX = BarSwipe.neighbourPageX(swipe.shownOffset(activeId), swipe.width, gapPx) },
    )
}

/**
 * After a swipe switched tabs the preview of the new tab stays over the page until the page has drawn its first
 * frame (a live tab: a short hold for the WebView to be re-attached; a sleeping one: until it reports its first
 * content, at most [PageCover.SLEEPING_MAX_MS]), then fades out. Lives inside the translated page layer.
 */
@Composable
internal fun PageCoverLayer(
    swipe: BarSwipeState,
    active: Tab,
    previews: PagePreviews,
    pageVisible: Flow<Long>,
    modifier: Modifier = Modifier,
) {
    if (swipe.coverTab != active.id) return
    val alpha = remember(active.id) { Animatable(1f) }
    LaunchedEffect(active.id) {
        val sleeping = swipe.coverSleeping
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        try {
            val firstFrame =
                sleeping && withTimeoutOrNull(PageCover.holdLimitMs(sleeping)) { pageVisible.first { it == active.id } } != null
            if (!PageCover.mayFade(elapsed(), sleeping, firstFrame)) {
                kotlinx.coroutines.delay((PageCover.holdLimitMs(sleeping) - elapsed()).coerceAtLeast(0))
            }
            // Let the first frame actually reach the screen before the preview goes.
            androidx.compose.runtime.withFrameNanos { }
            alpha.animateTo(0f, tween(PageCover.FADE_MS))
        } finally {
            // Any way out of the hold (faded, or cancelled because the tab changed / the composition left): the
            // cover must not stay behind, or it would reappear over the live page on the next visit to this tab.
            if (swipe.coverTab == active.id) swipe.clearCover()
        }
    }
    // The preview keeps the look it had while it slid in (grey for a tab that slept), even though the tab is awake now.
    // While it is up it swallows every gesture: the page underneath has not drawn yet and must not receive the tap.
    PagePreview(
        active, previews,
        modifier
            .graphicsLayer { this.alpha = alpha.value }
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) break
                    }
                }
            },
        grey = swipe.coverSleeping,
    )
}
