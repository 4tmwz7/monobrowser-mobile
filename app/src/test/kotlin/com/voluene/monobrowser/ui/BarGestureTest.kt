package com.voluene.monobrowser.ui

import com.voluene.monobrowser.ui.browser.BarGesture
import org.junit.Assert.assertEquals
import org.junit.Test

class BarGestureTest {
    private fun c(dx: Float, dy: Float, top: Boolean = false) = BarGesture.classify(dx, dy, 72f, 40f, top)

    @Test fun sideways() {
        assertEquals(BarGesture.NEXT_TAB, c(-100f, 10f))
        assertEquals(BarGesture.PREVIOUS_TAB, c(100f, -20f))
        assertEquals(BarGesture.NONE, c(50f, 5f))
    }

    @Test fun swipeAwayFromTheEdgeOpensTabs() {
        assertEquals(BarGesture.OPEN_TABS, c(5f, -60f))
        assertEquals(BarGesture.NONE, c(5f, 60f))
        assertEquals(BarGesture.OPEN_TABS, c(5f, 60f, top = true))
        assertEquals(BarGesture.NONE, c(5f, -60f, top = true))
        assertEquals(BarGesture.NONE, c(0f, -20f))
    }
}
