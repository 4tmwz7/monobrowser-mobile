package com.voluene.monobrowser.ui

import com.voluene.monobrowser.ui.common.BackGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackGestureTest {
    @Test fun easeIsMonotonicAndBounded() {
        assertEquals(0f, BackGesture.ease(0f), 0f)
        assertEquals(1f, BackGesture.ease(1f), 0f)
        assertEquals(1f, BackGesture.ease(5f), 0f)
        assertEquals(0f, BackGesture.ease(-1f), 0f)
        // Decelerating: half the swipe already shows most of the effect.
        assertTrue(BackGesture.ease(0.5f) > 0.7f)
        var last = 0f
        for (i in 1..100) { val v = BackGesture.ease(i / 100f); assertTrue(v >= last); last = v }
    }

    @Test fun scaleAndShift() {
        assertEquals(1f, BackGesture.scale(0f), 0f)
        assertEquals(0.9f, BackGesture.scale(1f), 1e-6f)
        assertEquals(0.05f, BackGesture.shift(1f), 1e-6f)
        assertEquals(0f, BackGesture.shift(0f), 0f)
    }
}
