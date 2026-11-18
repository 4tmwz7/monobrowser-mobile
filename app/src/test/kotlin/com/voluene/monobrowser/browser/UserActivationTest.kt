package com.voluene.monobrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserActivationTest {
    @Test fun inactiveUntilTouched() {
        assertFalse(UserActivation().isActive(1_000))
    }

    @Test fun activeForWindowAfterTouch() {
        val a = UserActivation()
        a.touch(10_000)
        assertTrue(a.isActive(10_000))
        assertTrue(a.isActive(10_000 + UserActivation.WINDOW_MS - 1))
        assertFalse(a.isActive(10_000 + UserActivation.WINDOW_MS))
    }

    @Test fun consumeSpendsIt() {
        val a = UserActivation()
        a.touch(0)
        a.consume()
        assertFalse(a.isActive(100))
    }

    @Test fun newTouchRenewsAfterConsumeOrExpiry() {
        val a = UserActivation()
        a.touch(0)
        a.consume()
        a.touch(50_000)
        assertTrue(a.isActive(51_000))
        assertFalse(a.isActive(56_000))
        a.touch(60_000)
        assertTrue(a.isActive(61_000))
    }

    @Test fun clockGoingBackwardsIsNotActive() {
        val a = UserActivation()
        a.touch(5_000)
        assertFalse(a.isActive(4_000))
    }
}
