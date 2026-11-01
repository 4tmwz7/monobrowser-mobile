package com.voluene.monobrowser.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStepsTest {
    @Test fun navigationIsClamped() {
        assertEquals(0, OnboardingSteps.prev(0))
        assertEquals(OnboardingSteps.COUNT - 1, OnboardingSteps.next(OnboardingSteps.COUNT - 1))
        assertEquals(1, OnboardingSteps.next(0))
        assertEquals(0, OnboardingSteps.clamp(-4))
        assertEquals(OnboardingSteps.COUNT - 1, OnboardingSteps.clamp(99))
    }

    @Test fun lastStep() {
        assertFalse(OnboardingSteps.isLast(0))
        assertTrue(OnboardingSteps.isLast(OnboardingSteps.COUNT - 1))
    }
}
