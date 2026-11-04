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
        assertEquals(0.82f, BackGesture.scale(1f), 1e-6f)
        assertEquals(0.1f, BackGesture.shift(1f), 1e-6f)
        assertEquals(0f, BackGesture.shift(0f), 0f)
    }

    @Test fun followsTheFingerVerticallyWithResistance() {
        val h = 2000f
        assertEquals(0f, BackGesture.follow(0f, h), 0.001f)
        assertEquals(h / 12f, BackGesture.follow(5000f, h), 0.001f) // capped
        assertEquals(-h / 12f, BackGesture.follow(-5000f, h), 0.001f)
        val small = BackGesture.follow(100f, h)
        assertTrue(small > 0f && small < h / 12f)
        assertTrue(small > 100f / h * h / 12f) // decelerating: small moves respond more than linearly
        assertEquals(0f, BackGesture.follow(100f, 0f), 0.001f)
    }
}
