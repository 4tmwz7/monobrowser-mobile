package com.voluene.monobrowser.ui.browser

import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import com.voluene.monobrowser.ui.common.cornerSafeHorizontalPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
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
 * The address bar: back/forward, the address (a tap opens the command palette), reload/stop, menu and tab count,
 * with a thin load-progress line on the edge facing the page. It sits at the bottom (thumb reach) or the top
 * ([toolbarTop]). Private tabs get an inverted bar. Swiping it sideways switches tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddressBar(
    tab: Tab,
    tabCount: Int,
    toolbarTop: Boolean,
    onOpenPalette: () -> Unit,
    onAddressLongPress: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onMenu: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
    onSwipeTab: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (tab.isPrivate) scheme.onBackground else scheme.background
    val fg = if (tab.isPrivate) scheme.background else scheme.onBackground
    val shown = if (UrlUtils.isAboutBlankUrl(tab.url)) "" else UrlUtils.displayUrl(tab.url)
    val swipe by rememberUpdatedState(onSwipeTab)
    val openTabs by rememberUpdatedState(onTabs)
    val haptics = LocalHapticFeedback.current
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val flingThreshold = with(LocalDensity.current) { 40.dp.toPx() }

    val progress: @Composable () -> Unit = {
        // Thin loading progress bar.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (tab.isLoading) {
                Box(Modifier.fillMaxWidth(tab.progress / 100f).height(2.dp).background(fg))
            }
        }
    }
    // Top: the status-bar strip is drawn by the screen (it must stay when the bar collapses).
    val insets = if (toolbarTop) Modifier else Modifier.navigationBarsPadding().imePadding()
    Column(
        Modifier.fillMaxWidth().background(bg).then(insets)
            .pointerInput(swipeThreshold, toolbarTop) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when (BarGesture.classify(dx, dy, swipeThreshold, flingThreshold, toolbarTop)) {
                            BarGesture.NEXT_TAB -> { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); swipe(1) }
                            BarGesture.PREVIOUS_TAB -> { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); swipe(-1) }
                            BarGesture.OPEN_TABS -> { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); openTabs() }
                            BarGesture.NONE -> Unit
                        }
                    },
                    onDrag = { change, delta -> change.consume(); dx += delta.x; dy += delta.y },
                )
            },
    ) {
        if (!toolbarTop) progress()
        // On phones with rounded screen corners the outermost buttons move in just enough to stay clear of the curve.
        Row(
            Modifier.fillMaxWidth().height(48.dp).cornerSafeHorizontalPadding(fromBottom = !toolbarTop),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.action_back, tab.canGoBack, fg, onBack)
            BarButton(Icons.AutoMirrored.Filled.ArrowForward, R.string.action_forward, tab.canGoForward, fg, onForward)
            val searchLabel = stringResource(R.string.address_hint)
            val fieldCorners = rememberScreenAwareShape()
            Row(
                Modifier.weight(1f).height(36.dp).then(fieldCorners.modifier).clip(fieldCorners.shape).border(1.dp, fg, fieldCorners.shape)
                    .combinedClickable(
                        onClickLabel = searchLabel,
                        onLongClickLabel = stringResource(R.string.address_copy),
                        onLongClick = onAddressLongPress,
                        onClick = onOpenPalette,
                    ).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    shown.ifEmpty { searchLabel },
                    color = fg.copy(alpha = if (shown.isEmpty()) 0.5f else 1f),
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tab.blockedCount > 0) {
                    // Blocked-requests counter of this page.
                    val description = stringResource(R.string.tab_blocked_count, tab.blockedCount)
                    Box(
                        Modifier.padding(start = 6.dp).border(1.dp, fg, RectangleShape)
                            .semantics { contentDescription = description }.padding(horizontal = 4.dp),
                    ) { Text(tab.blockedCount.toString(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            BarButton(
                if (tab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                if (tab.isLoading) R.string.action_stop else R.string.action_reload,
                true, fg, onReloadOrStop,
            )
            BarButton(Icons.Default.MoreVert, R.string.action_menu, true, fg, onMenu)
            val tabsLabel = stringResource(R.string.action_tabs, tabCount)
            // Sits right in the bottom (or top) corner of the screen: follow its curve.
            val counterCorners = rememberScreenAwareShape()
            Box(
                Modifier.padding(horizontal = 6.dp).size(32.dp).then(counterCorners.modifier).clip(counterCorners.shape)
                    .border(1.dp, fg, counterCorners.shape)
                    .combinedClickable(
                        onClickLabel = tabsLabel,
                        onLongClickLabel = stringResource(R.string.tab_new),
                        onLongClick = onNewTab,
                        onClick = onTabs,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(tabCount.toString(), color = fg, fontSize = 14.sp)
            }
        }
        if (toolbarTop) progress()
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: Int, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = stringResource(label), tint = tint.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

/** What a drag on the address bar means. Pure, unit-tested. */
enum class BarGesture {
    NEXT_TAB, PREVIOUS_TAB, OPEN_TABS, NONE;

    companion object {
        /**
         * Mostly sideways: left = next tab, right = previous tab (past [sideways] px). Mostly vertical, away from the
         * screen edge the bar sits on (up for a bottom bar, down for a top bar, past [vertical] px): the tab list.
         */
        fun classify(dx: Float, dy: Float, sideways: Float, vertical: Float, toolbarTop: Boolean): BarGesture = when {
            kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> when {
                dx <= -sideways -> NEXT_TAB
                dx >= sideways -> PREVIOUS_TAB
                else -> NONE
            }
            !toolbarTop && dy <= -vertical -> OPEN_TABS
            toolbarTop && dy >= vertical -> OPEN_TABS
            else -> NONE
        }
    }
}
