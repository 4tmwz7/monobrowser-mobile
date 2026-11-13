package com.voluene.monobrowser.ui.browser

import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import com.voluene.monobrowser.ui.common.ScreenAwareShape
import com.voluene.monobrowser.ui.common.CornerInsets
import com.voluene.monobrowser.ui.common.cornerInsetsPadding
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.voluene.monobrowser.ui.common.cornerSafeHorizontalPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import com.voluene.monobrowser.settings.BarItem
import com.voluene.monobrowser.settings.BarLayout
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.Tab
import com.voluene.monobrowser.data.HistoryEntry
import com.voluene.monobrowser.util.UrlUtils
import com.voluene.monobrowser.util.AddressDisplay
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import kotlin.math.roundToInt

/**
 * Collapses to zero height when [visible] is false (the page grows into the freed space, animated).
 * [fromTop]: the bar slides up out of view (top toolbar) instead of being cut off at the bottom.
 */
@Composable
fun CollapsingBar(visible: Boolean, modifier: Modifier = Modifier, fromTop: Boolean = false, content: @Composable () -> Unit) {
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "bar",
    )
    Box(
        modifier.clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = (placeable.height * fraction).roundToInt()
            layout(placeable.width, height) { placeable.placeRelative(0, if (fromTop) height - placeable.height else 0) }
        },
    ) { content() }
}

/**
 * The address bar: a row of the elements chosen in the settings ([items], see `BarLayout`; by default back, forward,
 * the address, reload/stop, menu and the tab counter) with a thin load-progress line on the edge facing the page. It
 * sits at the bottom (thumb reach) or the top ([toolbarTop]). Private tabs get an inverted bar. Dragging it follows
 * the finger: sideways it slides to the neighbouring tab (whose bar slides in; past the last tab a "new tab" bar),
 * away from the screen edge it pulls up the tab list ([onTabsDrag] / [onTabsRelease]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddressBar(
    tab: Tab,
    tabCount: Int,
    toolbarTop: Boolean,
    onOpenPalette: () -> Unit,
    onAddressLongPress: () -> Unit,
    onSiteInfo: () -> Unit,
    /** Long press on back (false) / forward (true): the tab's history in that direction. */
    onHistory: (forward: Boolean) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onMenu: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
    onSwipeTab: (Int) -> Unit,
    /** Drives the sideways swipe; owned by the screen too, because the page slides with the bar (see [BarSwipeState]). */
    swipe: BarSwipeState = rememberBarSwipeState(tab.id),
    /**
     * The gesture's axis was horizontal (after the touch slop): a swipe may follow. The screen refreshes the current
     * page's thumbnail now, while the page is still in place (it slides away with the swipe, and becomes the
     * neighbour's preview if the user swipes back). Once per gesture, not on every touch.
     */
    onBarTouch: () -> Unit = {},
    /** Swiping past the last tab: open a new tab. */
    onSwipeNewTab: () -> Unit = onNewTab,
    /** Vertical drag towards the page: [onTabsDrag] gets the progress (0..1), [onTabsRelease] the decision. */
    tabsPull: TabsPull? = null,
    /** Neighbours of [tab] in the current workspace (null at the ends): their bars slide in while the bar is dragged. */
    previousTab: Tab? = null,
    nextTab: Tab? = null,
    /** Elements of the row in order. */
    items: List<BarItem> = BarLayout.DEFAULT,
    bookmarked: Boolean = false,
    onBookmarks: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    onShare: () -> Unit = {},
    /** Show the whole URL instead of the site's host. */
    fullAddress: Boolean = false,
    /** Live load percentage (0..100) of a tab, read while drawing. */
    loadProgress: (Tab) -> Int = { it.progress },
    /** Whether a tab's page is bookmarked (the neighbour's bar shows its star as the real bar will). */
    neighbourBookmarked: (Tab) -> Boolean = { false },
    /** Shown when the page is an article: opens reader view. */
    onReader: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val gapPx = with(density) { SWIPE_GAP.toPx() }
    val flingVelocity = with(density) { 600.dp.toPx() }
    val minFlingDistance = with(density) { 24.dp.toPx() }
    val actions = rememberUpdatedState(SwipeActions(onSwipeTab, onSwipeNewTab, tab.id, previousTab, nextTab))
    val neighbours = rememberUpdatedState((previousTab != null) to (nextTab != null))
    val pull = rememberUpdatedState(tabsPull)
    val barTouched = rememberUpdatedState(onBarTouch)
    val currentTabId = rememberUpdatedState(tab.id)
    val pullDistance = TabsPullMath.distance(with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() })
    val haptics = LocalHapticFeedback.current
    // Shapes and corner insets are measured by the real bar and shared with the neighbour's copy, which sits where
    // the real bar will: nothing jumps when the swiped-to bar takes over. While the bar moves (a drag, the settle
    // animation after a release) the measurement is frozen: boundsInWindow rides with the translated bar, so a
    // measurement taken mid-gesture would change the radii and insets every frame and the content would "swim".
    val chrome = rememberBarChrome(frozen = { swipe.busy || swipe.dragging || swipe.shownOffset(currentTabId.value) != 0f })
    val calls = BarCalls(
        onOpenPalette, onAddressLongPress, onSiteInfo, onHistory, onBack, onForward, onReloadOrStop, onMenu, onTabs, onNewTab,
        onBookmarks, onToggleBookmark, onShare, onReader,
    )
    // Top: the status-bar strip is drawn by the screen (it must stay when the bar collapses).
    val insets = if (toolbarTop) Modifier else Modifier.navigationBarsPadding().imePadding()
    // The whole bar follows the finger; the neighbour's bar waits beside it (one gap away) and slides in.
    Box(
        Modifier.fillMaxWidth().clipToBounds().onSizeChanged { swipe.width = it.width.toFloat() }
            .pointerInput(toolbarTop, gapPx, flingVelocity, minFlingDistance, pullDistance, swipe) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A committed swipe is still settling, or the tab list is still sliding in from a pull: this
                    // gesture must not start while either animation moves the page (see BarSwipeState.busy).
                    if (swipe.busy || pull.value?.busy() == true) return@awaitEachGesture
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    val dx0 = slop.position.x - down.position.x
                    val dy0 = slop.position.y - down.position.y
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    tracker.addPointerInputChange(slop)
                    if (BarSwipe.axis(dx0, dy0) == BarSwipe.Axis.VERTICAL) {
                        // Towards the page (up for a bottom bar, down for a top one) pulls the tab list in; the other way does nothing.
                        val sign = if (toolbarTop) 1f else -1f
                        val target = pull.value
                        var dy = 0f
                        var released = false
                        try {
                            val finished = drag(slop.id) { change ->
                                change.consume()
                                tracker.addPointerInputChange(change)
                                dy = (change.position.y - slop.position.y) * sign
                                target?.onDrag(TabsPullMath.progress(dy, pullDistance))
                            }
                            val velocity = if (finished) tracker.calculateVelocity().y * sign else 0f
                            val open = finished && TabsPullMath.shouldOpen(dy, velocity, pullDistance, flingVelocity, minFlingDistance)
                            if (open) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            released = true
                            target?.onRelease(open, velocity)
                        } finally {
                            // The handler was restarted mid-pull (rotation, split screen): let the list go back,
                            // or the pull would stay "active" and block both bar gestures for good.
                            if (!released) target?.onRelease(false, 0f)
                        }
                        return@awaitEachGesture
                    }
                    // The axis is decided (a horizontal swipe): copy the page once, while it still sits in place -
                    // it slides away with the swipe and becomes the neighbour's preview if the user swipes back.
                    barTouched.value()
                    swipe.beginDrag()
                    var released = false
                    try {
                        val finished = drag(slop.id) { change ->
                            change.consume()
                            tracker.addPointerInputChange(change)
                            swipe.drag(change.position.x - slop.position.x, gapPx, neighbours.value.first)
                        }
                        val velocity = if (finished) tracker.calculateVelocity().x else 0f
                        val outcome = if (finished) {
                            BarSwipe.decide(swipe.total, velocity, swipe.width, neighbours.value.first, neighbours.value.second, flingVelocity, minFlingDistance)
                        } else BarSwipe.Outcome.NONE
                        if (outcome != BarSwipe.Outcome.NONE) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        released = true
                        swipe.release(outcome, velocity, gapPx, actions.value)
                    } finally {
                        // The handler was restarted mid-swipe (rotation, split screen): slide everything back
                        // instead of leaving the bar and page parked off-centre with the corner measuring frozen.
                        if (!released) swipe.release(BarSwipe.Outcome.NONE, 0f, gapPx, actions.value)
                    }
                }
            },
    ) {
        BarBody(
            tab = tab, tabCount = tabCount, items = items, toolbarTop = toolbarTop,
            bg = if (tab.isPrivate) scheme.onBackground else scheme.background,
            fg = if (tab.isPrivate) scheme.background else scheme.onBackground,
            fullAddress = fullAddress, bookmarked = bookmarked, interactive = true, chrome = chrome, calls = calls,
            progress = { loadProgress(tab) }, newTabHint = false, insets = insets,
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = swipe.shownOffset(tab.id) },
        )
        val side = swipe.shownSide(tab.id)
        // Dragged right before the first tab there is only the rubber band: nothing slides in.
        val incoming = BarSwipe.incoming(side, previousTab != null, nextTab != null)
        if (incoming != BarSwipe.Incoming.NONE) {
            // The bar the swiped-to tab will have: the same row of elements, in that tab's colours, with its state.
            // Past the last tab: the bar of the empty tab a new one would be (a normal tab, labelled "+ New tab").
            val shown = if (incoming == BarSwipe.Incoming.CARD) (if (side < 0) nextTab else previousTab) else null
            val blank = remember { Tab(id = NEW_TAB_PREVIEW_ID) }
            val neighbour = shown ?: blank
            BarBody(
                tab = neighbour, tabCount = if (shown == null) tabCount + 1 else tabCount, items = items, toolbarTop = toolbarTop,
                bg = if (neighbour.isPrivate) scheme.onBackground else scheme.background,
                fg = if (neighbour.isPrivate) scheme.background else scheme.onBackground,
                fullAddress = fullAddress, bookmarked = shown != null && neighbourBookmarked(shown), interactive = false,
                chrome = chrome, calls = BarCalls(onReader = {}),
                progress = { loadProgress(neighbour) }, newTabHint = shown == null, insets = insets,
                modifier = Modifier.matchParentSize()
                    .graphicsLayer { translationX = BarSwipe.neighbourX(swipe.shownOffset(tab.id), swipe.width, gapPx) }
                    // A picture of a bar, not a control: invisible to screen readers.
                    .clearAndSetSemantics {},
            )
        }
    }
}

private const val NEW_TAB_PREVIEW_ID = -1L

/** What the elements of the bar do when tapped (the neighbour's copy has none and is not interactive). */
internal class BarCalls(
    val onOpenPalette: () -> Unit = {},
    val onAddressLongPress: () -> Unit = {},
    val onSiteInfo: () -> Unit = {},
    /** Long press on back (false) / forward (true): the tab's history in that direction. */
    val onHistory: (forward: Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onForward: () -> Unit = {},
    val onReloadOrStop: () -> Unit = {},
    val onMenu: () -> Unit = {},
    val onTabs: () -> Unit = {},
    val onNewTab: () -> Unit = {},
    val onBookmarks: () -> Unit = {},
    val onToggleBookmark: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onReader: (() -> Unit)? = null,
)

/** Shapes and corner insets that depend on where the bar sits on the screen; measured by the real bar, shared with copies. */
@androidx.compose.runtime.Stable
internal class BarChrome(val field: ScreenAwareShape, val counter: ScreenAwareShape, val insets: CornerInsets, val frozen: () -> Boolean)

@Composable
internal fun rememberBarChrome(frozen: () -> Boolean): BarChrome {
    val field = rememberScreenAwareShape(frozen)
    val counter = rememberScreenAwareShape(frozen)
    return remember(field, counter) { BarChrome(field, counter, CornerInsets(), frozen) }
}

/**
 * The bar of one tab: the load-progress line on the page side and the row of [items]. Used for the real bar
 * ([interactive]) and for the picture of the neighbour's bar that slides in while swiping (not interactive: no taps,
 * no measuring; it takes the shapes and insets from [chrome], so it is identical to the bar that replaces it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarBody(
    tab: Tab,
    tabCount: Int,
    items: List<BarItem>,
    toolbarTop: Boolean,
    bg: Color,
    fg: Color,
    fullAddress: Boolean,
    bookmarked: Boolean,
    interactive: Boolean,
    chrome: BarChrome,
    calls: BarCalls,
    progress: () -> Int,
    newTabHint: Boolean,
    insets: Modifier,
    modifier: Modifier,
) {
    val isWeb = UrlUtils.isHttpUrl(tab.url)
    val line: @Composable () -> Unit = {
        // Thin loading progress bar, drawn in the draw phase: progress steps repaint this line only.
        Box(
            Modifier.fillMaxWidth().height(2.dp).drawBehind {
                val p = progress()
                if (tab.isLoading && p in 0..99) drawRect(fg, size = androidx.compose.ui.geometry.Size(size.width * p / 100f, size.height))
            },
        )
    }
    Column(modifier.background(bg).then(insets)) {
        if (!toolbarTop) line()
        // On phones with rounded screen corners the outermost buttons move in just enough to stay clear of the curve.
        Row(
            Modifier.fillMaxWidth().height(48.dp).then(
                if (interactive) Modifier.cornerSafeHorizontalPadding(fromBottom = !toolbarTop, shared = chrome.insets, frozen = chrome.frozen)
                else Modifier.cornerInsetsPadding(chrome.insets),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                when (item) {
                    BarItem.BACK -> BarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.action_back, tab.canGoBack, fg, interactive, onLongClick = { calls.onHistory(false) }, onClick = calls.onBack)
                    BarItem.FORWARD -> BarButton(Icons.AutoMirrored.Filled.ArrowForward, R.string.action_forward, tab.canGoForward, fg, interactive, onLongClick = { calls.onHistory(true) }, onClick = calls.onForward)
                    BarItem.ADDRESS -> AddressField(
                        tab, fg, fullAddress, interactive, chrome.field, newTabHint, calls,
                        Modifier.weight(1f).padding(start = if (index == 0) 8.dp else 0.dp, end = if (index == items.lastIndex) 8.dp else 0.dp),
                    )
                    BarItem.RELOAD -> BarButton(
                        if (tab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                        if (tab.isLoading) R.string.action_stop else R.string.action_reload,
                        true, fg, interactive, onClick = calls.onReloadOrStop,
                    )
                    BarItem.MENU -> BarButton(Icons.Default.MoreVert, R.string.action_menu, true, fg, interactive, onClick = calls.onMenu)
                    BarItem.TABS -> {
                        val tabsLabel = stringResource(R.string.action_tabs, tabCount)
                        // Follows the curve of the screen when it sits right in a corner.
                        val counterCorners = chrome.counter
                        Box(
                            Modifier.padding(horizontal = 6.dp).size(32.dp)
                                .then(if (interactive) counterCorners.modifier else Modifier).clip(counterCorners.shape)
                                .border(1.dp, fg, counterCorners.shape)
                                .then(
                                    if (interactive) Modifier.combinedClickable(
                                        onClickLabel = tabsLabel,
                                        onLongClickLabel = stringResource(R.string.tab_new),
                                        onLongClick = calls.onNewTab,
                                        onClick = calls.onTabs,
                                    ) else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(tabCount.toString(), color = fg, fontSize = 14.sp)
                        }
                    }
                    BarItem.NEW_TAB -> BarButton(Icons.Default.Add, R.string.tab_new, true, fg, interactive, onClick = calls.onNewTab)
                    BarItem.BOOKMARKS -> BarButton(MenuIcons.Bookmark, R.string.menu_bookmarks, true, fg, interactive, onClick = calls.onBookmarks)
                    BarItem.BOOKMARK -> BarButton(
                        if (bookmarked) Icons.Filled.Star else MenuIcons.StarOutline,
                        if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add,
                        isWeb && !tab.isPrivate, fg, interactive, onClick = calls.onToggleBookmark,
                    )
                    BarItem.SHARE -> BarButton(Icons.Default.Share, R.string.menu_share, isWeb, fg, interactive, onClick = calls.onShare)
                }
            }
        }
        if (toolbarTop) line()
    }
}

/**
 * The address field: lock, host (or the whole address), reader button and blocked counter; tap = palette. As a
 * non-[interactive] copy it only draws. [newTabHint]: the field of a tab that does not exist yet reads "+ New tab".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddressField(
    tab: Tab,
    fg: Color,
    fullAddress: Boolean,
    interactive: Boolean,
    fieldCorners: ScreenAwareShape,
    newTabHint: Boolean,
    calls: BarCalls,
    modifier: Modifier,
) {
    val shown = if (UrlUtils.isAboutBlankUrl(tab.url)) "" else UrlUtils.displayUrl(tab.url)
    val searchLabel = stringResource(R.string.address_hint)
    Row(
        modifier.height(36.dp).then(if (interactive) fieldCorners.modifier else Modifier).clip(fieldCorners.shape).border(1.dp, fg, fieldCorners.shape)
            .then(
                if (interactive) Modifier.combinedClickable(
                    onClickLabel = searchLabel,
                    onLongClickLabel = stringResource(R.string.address_copy),
                    onLongClick = calls.onAddressLongPress,
                    onClick = calls.onOpenPalette,
                ) else Modifier,
            ).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val display = AddressDisplay.of(tab.url)
        if (display.secure != null) {
            // Lock (https) or warning (http): opens the site information sheet.
            val siteLabel = stringResource(if (display.secure) R.string.site_secure else R.string.site_not_secure)
            Box(
                Modifier.padding(end = 4.dp).size(28.dp)
                    .then(if (interactive) Modifier.clickable(onClickLabel = stringResource(R.string.site_info), onClick = calls.onSiteInfo).semantics { contentDescription = siteLabel } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (display.secure) Icons.Filled.Lock else Icons.Filled.Warning, contentDescription = null,
                    tint = fg.copy(alpha = if (display.secure) 0.8f else 1f), modifier = Modifier.size(15.dp),
                )
            }
        }
        if (display.secure != null && !fullAddress) {
            // Modern browsers show the site; the full address is one tap away (the palette).
            Text(
                display.host, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        } else if (newTabHint) {
            // Clear about what the swipe will do.
            Text(
                "+  " + stringResource(R.string.tab_new), color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                shown.ifEmpty { searchLabel },
                color = fg.copy(alpha = if (shown.isEmpty()) 0.5f else 1f),
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val onReader = calls.onReader
        if (onReader != null && tab.readerAvailable && !tab.isLoading) {
            val readerLabel = stringResource(R.string.menu_reader)
            Box(
                Modifier.padding(start = 4.dp).size(28.dp)
                    .then(if (interactive) Modifier.clickable(onClickLabel = readerLabel, onClick = onReader).semantics { contentDescription = readerLabel } else Modifier),
                contentAlignment = Alignment.Center,
            ) { Icon(MenuIcons.Article, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp)) }
        }
        if (tab.blockedCount > 0) {
            // Blocked-requests counter of this page.
            val description = stringResource(R.string.tab_blocked_count, tab.blockedCount)
            Box(
                Modifier.padding(start = 6.dp).border(1.dp, fg, RectangleShape)
                    .semantics { contentDescription = description }.padding(horizontal = 4.dp),
            ) { Text(tab.blockedCount.toString(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: Int, enabled: Boolean, tint: Color, interactive: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val iconTint = tint.copy(alpha = if (enabled) 1f else 0.35f)
    if (!interactive) {
        // Same size and place as the real button, but only a picture.
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = iconTint) }
        return
    }
    if (onLongClick == null) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(icon, contentDescription = stringResource(label), tint = iconTint)
        }
        return
    }
    // Back / forward: a long press lists the tab's history in that direction.
    Box(
        Modifier.size(38.dp).combinedClickable(
            enabled = enabled,
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = stringResource(label),
            onLongClickLabel = stringResource(R.string.tab_history),
            onLongClick = onLongClick,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(label), tint = iconTint)
    }
}
