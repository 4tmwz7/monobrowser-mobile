package com.voluene.monobrowser.ui.browser

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import com.voluene.monobrowser.browser.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Space between the bar and the neighbour's bar while swiping. */
internal val SWIPE_GAP = 12.dp

/**
 * What a committed swipe calls; [tabId] is the active tab at the time of the release, [previous] / [next] its
 * neighbours (the tab a switch lands on keeps its preview over the page until it has drawn, see [BarSwipeState.coverTab]).
 */
internal class SwipeActions(
    val switchRelative: (Int) -> Unit,
    val newTab: () -> Unit,
    val tabId: Long,
    val previous: Tab? = null,
    val next: Tab? = null,
)

/**
 * Drives the address-bar swipe: the offset follows the finger, a release animates to the neighbour (then the tab is
 * switched) or back. A committed swipe is marked ([busy]) BEFORE its animation, new gestures are ignored until it is
 * over and the switch itself runs even if the animation is cancelled. The offset stays at its end until the active
 * tab really changes, and [shownOffset] reads zero in the very composition that carries the new tab, so nothing
 * jumps or flashes. A change of the active tab from outside during a drag resets the gesture.
 */
@Stable
internal class BarSwipeState(private val scope: CoroutineScope) {
    /** Bar width in px (set by the layout). */
    var width by mutableFloatStateOf(0f)
    private var offset by mutableFloatStateOf(0f)
    private var currentTabId by mutableLongStateOf(0L)
    /** Tab that was active at the commit; set from the release until the active tab changes (or the wait gives up). */
    private var pendingFrom by mutableStateOf<Long?>(null)
    private var job: Job? = null
    /** Tab the running drag started on (null = no drag, or it was reset by an outside change). */
    private var dragTab: Long? = null

    /** Finger travel of the running drag (what [BarSwipe.decide] needs). */
    var total = 0f
        private set

    /** -1 = a bar comes in from the right (next tab / new tab), 1 = from the left (previous), 0 = none. */
    val side: Int by derivedStateOf { if (BarSwipe.neighbourVisible(offset)) (if (offset < 0f) -1 else 1) else 0 }

    /**
     * Tab (the one a committed swipe switches to) whose preview keeps covering the page once it is the active tab,
     * until it has drawn its first frame; null = no cover. Set at the release, cleared by [clearCover] (the page layer
     * does it after the fade) or when the switch does not happen.
     */
    var coverTab by mutableStateOf<Long?>(null)
        private set

    /** The cover's tab was sleeping at the switch: it has to load, so its preview is held longer. */
    var coverSleeping = false
        private set

    fun clearCover() {
        coverTab = null
    }

    /** True from the release of a committed swipe until it is over: new gestures are ignored. */
    val busy: Boolean get() = pendingFrom != null

    fun onTab(id: Long) {
        currentTabId = id
        // The active tab changed under a running drag: the gesture would decide against another tab.
        if (dragTab != null && dragTab != id) {
            dragTab = null
            total = 0f
            offset = 0f
        }
        // A finished swipe's cover belongs to the tab it was left on: once that tab is not the active one any
        // more (switched elsewhere, closed), the cover must go, or it would come back over the live page the
        // next time the tab shows. Not while a swipe is settling ([busy]): the cover of the tab being switched
        // to is still needed, and the settle's own paths ([finishCommit]) drop it if the switch does not happen.
        if (!busy && coverTab != null && coverTab != id) {
            coverTab = null
            coverSleeping = false
        }
    }

    /** Offset to draw when the active tab is [tabId]: zero as soon as it differs from the one the swipe left. */
    fun shownOffset(tabId: Long): Float = if (isStale(tabId)) 0f else offset

    fun shownSide(tabId: Long): Int = if (isStale(tabId)) 0 else side

    private fun isStale(tabId: Long): Boolean = pendingFrom.let { it != null && it != tabId }

    /** A new gesture always starts from a clean state (a return animation still running is dropped). */
    fun beginDrag() {
        job?.cancel()
        job = null
        offset = 0f
        total = 0f
        dragTab = currentTabId
    }

    fun drag(rawDelta: Float, gap: Float, hasPrevious: Boolean) {
        if (dragTab == null) return
        total = rawDelta
        offset = BarSwipe.displayOffset(total, width, gap, hasPrevious)
    }

    /** True while the running drag is still valid (the active tab did not change under it). */
    val dragging: Boolean get() = dragTab != null

    fun release(outcome: BarSwipe.Outcome, velocity: Float, gap: Float, actions: SwipeActions) {
        if (dragTab == null) { // reset from outside: nothing to decide
            offset = 0f
            return
        }
        dragTab = null
        val settleTo = BarSwipe.settleTarget(outcome, width, gap)
        if (outcome == BarSwipe.Outcome.NONE) {
            job = scope.launch { animate(offset, 0f, velocity, SETTLE) { value, _ -> offset = value } }
            return
        }
        pendingFrom = actions.tabId
        val target = when (outcome) {
            BarSwipe.Outcome.PREVIOUS -> actions.previous
            BarSwipe.Outcome.NEXT -> actions.next
            else -> null // a new tab draws its start page at once
        }
        coverTab = target?.id
        coverSleeping = target?.sleeping == true
        job = scope.launch {
            try {
                animate(offset, settleTo, velocity, SETTLE) { value, _ -> offset = value }
            } finally {
                // The switch does not depend on the animation surviving (it is decided and acknowledged by haptics).
                withContext(NonCancellable) { finishCommit(outcome, actions) }
            }
        }
    }

    private suspend fun finishCommit(outcome: BarSwipe.Outcome, actions: SwipeActions) {
        if (currentTabId != actions.tabId) { // the tab changed meanwhile (from outside): do not switch again
            offset = 0f
            pendingFrom = null
            coverTab = null
            return
        }
        try {
            when (outcome) {
                BarSwipe.Outcome.PREVIOUS -> actions.switchRelative(-1)
                BarSwipe.Outcome.NEXT -> actions.switchRelative(1)
                else -> actions.newTab()
            }
        } catch (e: Exception) {
            // Never leave the bar parked off-screen and locked ("busy") when the switch itself fails.
            offset = 0f
            pendingFrom = null
            coverTab = null
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("BarSwipe", "tab switch failed", e) // rethrowing here would crash the app
            return
        }
        // A tab switch is waited for (long safety net); "new tab" may reuse an empty tab and change nothing: short.
        val wait = if (outcome == BarSwipe.Outcome.NEW_TAB) NEW_TAB_WAIT_MS else SWITCH_WAIT_MS
        val changed = withTimeoutOrNull(wait) { snapshotFlow { currentTabId }.first { it != actions.tabId } }
        if (changed == null) {
            // Nothing changed: slide back instead of leaving the bar off-screen.
            pendingFrom = null
            coverTab = null
            animate(offset, 0f, 0f, SETTLE) { value, _ -> offset = value }
        } else {
            offset = 0f
            pendingFrom = null
        }
    }

    private companion object {
        val SETTLE = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)
        const val NEW_TAB_WAIT_MS = 400L
        const val SWITCH_WAIT_MS = 2000L
    }
}

@Composable
internal fun rememberBarSwipeState(tabId: Long): BarSwipeState {
    val scope = rememberCoroutineScope()
    val state = remember { BarSwipeState(scope).also { it.onTab(tabId) } }
    SideEffect { state.onTab(tabId) }
    return state
}
