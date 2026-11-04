package com.voluene.monobrowser.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StateFileTest {
    private fun file(text: String?) = File.createTempFile("state", ".txt").apply { deleteOnExit(); if (text == null) delete() else writeText(text) }

    private fun state(f: File, scope: TestScope, background: Boolean) =
        StateFile(f, "initial", { it.takeIf { t -> t.isNotBlank() } }, { it }, scope, StandardTestDispatcher(scope.testScheduler), background)

    @Test fun eagerLoadReadsAtConstruction() {
        val scope = TestScope()
        val s = state(file("saved"), scope, background = false)
        assertEquals("saved", s.state.value)
        assertTrue(s.existed)
    }

    @Test fun backgroundLoadPublishesLater() {
        val scope = TestScope()
        val s = state(file("saved"), scope, background = true)
        assertEquals("initial", s.state.value)
        scope.advanceUntilIdle()
        assertEquals("saved", s.state.value)
        assertFalse(s.existed)
    }

    @Test fun valueAndUpdateNeverSeeTheUnloadedDefault() {
        val scope = TestScope()
        val s = state(file("saved"), scope, background = true)
        assertEquals("saved", s.value) // read on demand
        val t = state(file("saved"), scope, background = true)
        t.update { "$it+1" } // applied to the file's value, not to "initial"
        scope.advanceUntilIdle()
        assertEquals("saved+1", t.state.value)
    }

    @Test fun missingFileKeepsInitial() {
        val scope = TestScope()
        val s = state(file(null), scope, background = true)
        scope.advanceUntilIdle()
        assertEquals("initial", s.value)
    }
}
