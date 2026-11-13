package com.voluene.monobrowser.ui.browser

import androidx.compose.runtime.MonotonicFrameClock
import com.voluene.monobrowser.browser.Tab
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cover state of a committed swipe ([BarSwipeState.coverTab]): it must survive the settle and the switch,
 * and it must go when the tab it covers is no longer the active one - or it would come back over the live page
 * on the next visit to that tab (a "ghost" preview).
 */
class BarSwipeStateTest {
    /** Frame clock for the settle animations: one 16 ms frame per step, so the springs converge in virtual time. */
    private val frames = object : MonotonicFrameClock {
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            now += 16_000_000L
            return onFrame(now)
        }
    }


    /** A committed swipe to the previous tab (tab 1 -> tab 2); the switch happens in the settle, like in the app. */
    private fun TestScope.committedSwipe(tabId: Long = 1L, targetId: Long = 2L): BarSwipeState {
        val state = BarSwipeState(this)
        state.onTab(tabId) // like rememberBarSwipeState: the state starts on the active tab
        state.width = 300f
        state.beginDrag()
        state.drag(-150f, 12f, hasPrevious = true)
        state.release(
            BarSwipe.Outcome.PREVIOUS, velocity = 0f, gap = 12f,
            SwipeActions(switchRelative = { state.onTab(targetId) }, newTab = {}, tabId = tabId, previous = Tab(id = targetId), next = null),
        )
        return state
    }

    @Test
    fun coverSurvivesTheSwitchAndGoesWhenAnotherTabBecomesActive() = runTest(frames) {
        val state = committedSwipe()
        // The cover belongs to the tab being switched to from the release on (the settle has not run yet).
        assertEquals(2L, state.coverTab)
        advanceUntilIdle() // the settle animation, then the switch
        assertEquals(2L, state.coverTab)
        assertFalse(state.busy)
        // The active tab changed from outside (palette, tab switcher, closing): the finished cover must go.
        state.onTab(3L)
        assertNull(state.coverTab)
    }

    @Test
    fun recompositionsWithTheOldTabDoNotDropTheCoverWhileBusy() = runTest(frames) {
        val state = committedSwipe()
        // During the settle the composition still reports the tab the swipe left: it must not drop the cover
        // of the tab being switched to.
        state.onTab(1L)
        assertEquals(2L, state.coverTab)
        // A real outside change (a third tab) while the swipe is settling: the settle itself drops the cover,
        // because it does not switch to it.
        state.onTab(3L)
        advanceUntilIdle()
        assertNull(state.coverTab)
        assertFalse(state.busy)
    }

    @Test
    fun outsideChangeDuringTheSettleKeepsTheStateClean() = runTest(frames) {
        val state = committedSwipe()
        state.onTab(3L)
        advanceUntilIdle()
        // Nothing changed to the swiped-to tab: no parked offset, no pending switch, no cover.
        assertEquals(0f, state.shownOffset(3L), 0f)
        assertNull(state.coverTab)
        assertFalse(state.busy)
    }
}
