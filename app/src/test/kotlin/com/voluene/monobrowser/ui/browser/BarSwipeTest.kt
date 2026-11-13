package com.voluene.monobrowser.ui.browser

import com.voluene.monobrowser.ui.browser.BarSwipe.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarSwipeTest {
    private val width = 300f
    private fun decide(raw: Float, velocity: Float = 0f, prev: Boolean = true, next: Boolean = true) =
        BarSwipe.decide(raw, velocity, width, prev, next, flingVelocity = 1000f, minFlingDistance = 40f)

    @Test fun axisFollowsTheDominantDirection() {
        assertEquals(BarSwipe.Axis.HORIZONTAL, BarSwipe.axis(20f, 5f))
        assertEquals(BarSwipe.Axis.VERTICAL, BarSwipe.axis(5f, -20f))
        assertEquals(BarSwipe.Axis.HORIZONTAL, BarSwipe.axis(10f, 10f))
    }

    @Test fun distanceOfAThirdSwitches() {
        assertEquals(Outcome.NEXT, decide(-100f))
        assertEquals(Outcome.PREVIOUS, decide(100f))
        assertEquals(Outcome.NONE, decide(-99f))
        assertEquals(Outcome.NONE, decide(99f))
    }

    @Test fun aQuickFlickSwitchesOverAShortDistance() {
        assertEquals(Outcome.NEXT, decide(-50f, velocity = -1500f))
        assertEquals(Outcome.PREVIOUS, decide(50f, velocity = 1500f))
        // Too short a move, or a flick against the drag: nothing.
        assertEquals(Outcome.NONE, decide(-20f, velocity = -1500f))
        assertEquals(Outcome.NONE, decide(-50f, velocity = 1500f))
        assertEquals(Outcome.NONE, decide(-50f, velocity = -500f))
    }

    @Test fun pastTheLastTabAFarDragOpensANewOne() {
        assertEquals(Outcome.NEW_TAB, decide(-120f, next = false))
        assertEquals(Outcome.NONE, decide(-50f, next = false))
        // A flick must not create tabs.
        assertEquals(Outcome.NONE, decide(-60f, velocity = -3000f, next = false))
    }

    @Test fun beforeTheFirstTabNothingHappens() {
        assertEquals(Outcome.NONE, decide(250f, prev = false))
        assertEquals(Outcome.NONE, decide(80f, velocity = 3000f, prev = false))
    }

    @Test fun noWidthOrNoMoveIsNothing() {
        assertEquals(Outcome.NONE, BarSwipe.decide(-100f, 0f, 0f, true, true, 1000f, 40f))
        assertEquals(Outcome.NONE, decide(0f))
    }

    @Test fun rubberBandDampsAndStaysBelowTheLimit() {
        val limit = 75f
        val a = BarSwipe.rubberBand(50f, limit)
        val b = BarSwipe.rubberBand(500f, limit)
        assertTrue(a in 1f..50f)
        assertTrue(b > a && b < limit)
        assertEquals(-a, BarSwipe.rubberBand(-50f, limit), 0.001f)
        assertEquals(0f, BarSwipe.rubberBand(0f, limit), 0f)
    }

    @Test fun displayOffsetFollowsTheFingerExceptWhereDamped() {
        assertEquals(-80f, BarSwipe.displayOffset(-80f, width, 12f, hasPrevious = true), 0f)
        assertEquals(80f, BarSwipe.displayOffset(80f, width, 12f, hasPrevious = true), 0f)
        assertTrue(BarSwipe.displayOffset(200f, width, 12f, hasPrevious = false) < 75f)
        // Never past one step (width + gap).
        assertEquals(312f, BarSwipe.displayOffset(900f, width, 12f, hasPrevious = true), 0f)
        assertEquals(-312f, BarSwipe.displayOffset(-900f, width, 12f, hasPrevious = true), 0f)
    }

    @Test fun settleTargets() {
        assertEquals(312f, BarSwipe.settleTarget(Outcome.PREVIOUS, width, 12f), 0f)
        assertEquals(-312f, BarSwipe.settleTarget(Outcome.NEXT, width, 12f), 0f)
        assertEquals(-312f, BarSwipe.settleTarget(Outcome.NEW_TAB, width, 12f), 0f)
        assertEquals(0f, BarSwipe.settleTarget(Outcome.NONE, width, 12f), 0f)
    }

    @Test fun theNeighbourWaitsOneStepAwayAndArrivesAtZero() {
        assertEquals(312f - 100f, BarSwipe.neighbourX(-100f, width, 12f), 0f)
        assertEquals(0f, BarSwipe.neighbourX(-312f, width, 12f), 0f)
        assertEquals(100f - 312f, BarSwipe.neighbourX(100f, width, 12f), 0f)
        assertEquals(0f, BarSwipe.neighbourX(312f, width, 12f), 0f)
        assertFalse(BarSwipe.neighbourVisible(0f))
        assertTrue(BarSwipe.neighbourVisible(-3f))
    }

    @Test fun incomingFollowsTheDragDirectionAndNeighbours() {
        assertEquals(BarSwipe.Incoming.CARD, BarSwipe.incoming(-1, hasPrevious = true, hasNext = true))
        assertEquals(BarSwipe.Incoming.NEW_TAB, BarSwipe.incoming(-1, hasPrevious = true, hasNext = false))
        assertEquals(BarSwipe.Incoming.CARD, BarSwipe.incoming(1, hasPrevious = true, hasNext = false))
        // Before the first tab only the rubber band: no "new tab" bar, nothing slides in.
        assertEquals(BarSwipe.Incoming.NONE, BarSwipe.incoming(1, hasPrevious = false, hasNext = true))
        assertEquals(BarSwipe.Incoming.NONE, BarSwipe.incoming(0, hasPrevious = true, hasNext = true))
    }

    @Test fun pullProgressIsClampedAndNeedsADistance() {
        assertEquals(0f, TabsPullMath.progress(-10f, 300f), 0f)
        assertEquals(0.5f, TabsPullMath.progress(150f, 300f), 0.001f)
        assertEquals(1f, TabsPullMath.progress(900f, 300f), 0f)
        assertEquals(0f, TabsPullMath.progress(50f, 0f), 0f)
    }

    @Test fun tabListOpensPastAFifthOrOnAFlick() {
        val distance = 500f
        assertTrue(TabsPullMath.shouldOpen(100f, 0f, distance, 1000f, 24f))
        assertFalse(TabsPullMath.shouldOpen(99f, 0f, distance, 1000f, 24f))
        assertTrue(TabsPullMath.shouldOpen(40f, 1500f, distance, 1000f, 24f))
        assertFalse(TabsPullMath.shouldOpen(10f, 1500f, distance, 1000f, 24f))
        assertFalse(TabsPullMath.shouldOpen(40f, -1500f, distance, 1000f, 24f))
        // The list follows the finger 1:1: the whole screen is the distance.
        assertEquals(800f, TabsPullMath.distance(800f), 0.001f)
    }

    @Test fun neighbourPageMovesWithTheBarAndKeepsTheGap() {
        val gap = 12f
        listOf(-300f, -150f, -1f, 1f, 150f, 312f).forEach { offset ->
            assertEquals(BarSwipe.neighbourX(offset, width, gap), BarSwipe.neighbourPageX(offset, width, gap), 0f)
            // One page width plus the gap between the current page and its neighbour, whatever the offset.
            assertEquals(gap, BarSwipe.pageGap(offset, width, gap), 0.001f)
        }
        assertEquals(0f, BarSwipe.neighbourPageX(0f, width, gap), 0f)
        // The neighbour starts one travel away on the side the finger comes from and ends at the page's place.
        assertEquals(width + gap - 10f, BarSwipe.neighbourPageX(-10f, width, gap), 0.001f)
        assertEquals(0f, BarSwipe.neighbourPageX(-(width + gap), width, gap), 0.001f)
        assertEquals(-(width + gap) + 10f, BarSwipe.neighbourPageX(10f, width, gap), 0.001f)
        assertEquals(0f, BarSwipe.neighbourPageX(width + gap, width, gap), 0.001f)
    }

    @Test fun coverHoldsALiveTabBrieflyAndASleepingTabUntilItDraws() {
        assertEquals(PageCover.LIVE_HOLD_MS, PageCover.holdLimitMs(false))
        assertEquals(PageCover.SLEEPING_MAX_MS, PageCover.holdLimitMs(true))
        // A live tab does not wait for a first-frame report.
        assertFalse(PageCover.mayFade(50, sleeping = false, firstFrame = false))
        assertTrue(PageCover.mayFade(PageCover.LIVE_HOLD_MS, sleeping = false, firstFrame = false))
        // A sleeping tab fades once it has drawn, or when the limit runs out.
        assertFalse(PageCover.mayFade(300, sleeping = true, firstFrame = false))
        assertTrue(PageCover.mayFade(300, sleeping = true, firstFrame = true))
        assertTrue(PageCover.mayFade(PageCover.SLEEPING_MAX_MS, sleeping = true, firstFrame = false))
    }
}
