package com.voluene.monobrowser.data

import com.voluene.monobrowser.util.writeTextAtomically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionCodecTest {
    @get:Rule val temp = TemporaryFolder()

    private val sample = SessionData(
        tabs = listOf(
            PersistedTab(1, "https://example.com/", "Example", neverSleep = true),
            PersistedTab(5, "", ""),
            PersistedTab(7, "https://pl.wikipedia.org/wiki/Zażółć", "Zażółć \"gęślą\" jaźń"),
        ),
        activeTabId = 5,
        nextTabId = 8,
        recentlyClosed = listOf(ClosedTab("https://closed.example/", "Closed")),
    )

    @Test fun roundTripKeepsEverything() {
        val decoded = SessionCodec.decode(SessionCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test fun privateTabsAreNeverWritten() {
        val data = sample.copy(tabs = sample.tabs + PersistedTab(9, "https://secret.example/", "Secret", isPrivate = true))
        val text = SessionCodec.encode(data)
        assertFalse(text.contains("secret.example"))
        assertEquals(sample.tabs, SessionCodec.decode(text)!!.tabs)
    }

    @Test fun privateTabInFileIsRejectedOnLoad() {
        val text = """{"tabs":[{"id":1,"url":"https://a.example/","isPrivate":true},{"id":2,"url":"https://b.example/"}],"activeTabId":1}"""
        val decoded = SessionCodec.decode(text)!!
        assertEquals(listOf(2L), decoded.tabs.map { it.id })
        assertEquals(2L, decoded.activeTabId)
    }

    @Test fun nonRestorableUrlsAndDuplicateIdsAreDropped() {
        val data = SessionData(
            tabs = listOf(
                PersistedTab(1, "javascript:alert(1)"),
                PersistedTab(2, "file:///etc/passwd"),
                PersistedTab(3, "https://ok.example/"),
                PersistedTab(3, "https://dup.example/"),
                PersistedTab(0, "https://zero-id.example/"),
            ),
            activeTabId = 99,
            nextTabId = 1,
        )
        val decoded = SessionCodec.sanitize(data)
        assertEquals(listOf(3L), decoded.tabs.map { it.id })
        assertEquals(3L, decoded.activeTabId) // unknown active tab falls back to a real one
        assertEquals(4L, decoded.nextTabId) // never reuses an existing id
    }

    @Test fun recentlyClosedIsCapped() {
        val closed = (1..40).map { ClosedTab("https://e.example/$it") }
        assertEquals(SessionCodec.MAX_RECENTLY_CLOSED, SessionCodec.sanitize(SessionData(recentlyClosed = closed)).recentlyClosed.size)
    }

    @Test fun garbageDecodesToNullAndUnknownKeysAreIgnored() {
        assertNull(SessionCodec.decode("not json"))
        assertNull(SessionCodec.decode(""))
        assertNotNull(SessionCodec.decode("""{"future":1,"tabs":[]}"""))
    }

    @Test fun storeSavesAtomicallyAndLoadsBack() {
        val dir = temp.newFolder()
        val store = SessionStore(dir)
        assertNull(store.load())
        store.save(sample)
        assertEquals(sample, store.load())
        assertFalse(File(dir, "session.json.tmp").exists())
        // Overwrite keeps working and leaves no temp file.
        store.save(sample.copy(activeTabId = 1))
        assertEquals(1L, store.load()!!.activeTabId)
    }

    @Test fun corruptSessionFileLoadsAsNull() {
        val dir = temp.newFolder()
        writeTextAtomically(File(dir, "session.json"), "{ truncated")
        assertNull(SessionStore(dir).load())
    }

    @Test fun tabStatesAreWrittenReadAndPruned() {
        val store = SessionStore(temp.newFolder())
        store.writeTabState(1, byteArrayOf(1, 2, 3))
        store.writeTabState(2, byteArrayOf(4))
        assertTrue(store.readTabState(1)!!.contentEquals(byteArrayOf(1, 2, 3)))
        store.pruneTabStates(setOf(2))
        assertNull(store.readTabState(1))
        assertNotNull(store.readTabState(2))
        store.deleteTabState(2)
        assertNull(store.readTabState(2))
    }

    // --- workspaces (session version 2) ---

    @Test fun versionOneFileWithoutWorkspacesLoadsIntoMainWorkspace() {
        val text = """{"version":1,"tabs":[{"id":1,"url":"https://a.example/","title":"A"},{"id":2,"url":"https://b.example/"}],"activeTabId":2,"nextTabId":3}"""
        val decoded = SessionCodec.decode(text)!!
        assertEquals(2, decoded.version)
        assertEquals(listOf(WorkspaceOps.MAIN_ID), decoded.workspaces.map { it.id })
        assertEquals(WorkspaceOps.MAIN_ID, decoded.currentWorkspaceId)
        assertEquals(listOf(WorkspaceOps.MAIN_ID, WorkspaceOps.MAIN_ID), decoded.tabs.map { it.workspaceId })
        assertEquals(2L, decoded.activeTabId)
    }

    private val withWorkspaces = SessionData(
        tabs = listOf(
            PersistedTab(1, "https://a.example/", "A"),
            PersistedTab(2, "https://b.example/", "B", workspaceId = "ws-work"),
            PersistedTab(3, "https://c.example/", "C", workspaceId = "ws-work"),
        ),
        activeTabId = 3,
        nextTabId = 4,
        workspaces = listOf(Workspace("main"), Workspace("ws-work", "Praca", "★")),
        currentWorkspaceId = "ws-work",
        activeTabIdByWorkspace = mapOf("main" to 1L, "ws-work" to 3L),
    )

    @Test fun workspacesRoundTrip() {
        assertEquals(withWorkspaces, SessionCodec.decode(SessionCodec.encode(withWorkspaces)))
    }

    @Test fun unknownWorkspaceOfATabFallsBackToMain() {
        val data = withWorkspaces.copy(tabs = withWorkspaces.tabs + PersistedTab(4, "https://d.example/", workspaceId = "ghost"))
        val decoded = SessionCodec.sanitize(data)
        assertEquals("main", decoded.tabs.first { it.id == 4L }.workspaceId)
    }

    @Test fun activeTabDecidesTheCurrentWorkspace() {
        val decoded = SessionCodec.sanitize(withWorkspaces.copy(currentWorkspaceId = "main", activeTabId = 2))
        assertEquals("ws-work", decoded.currentWorkspaceId)
        assertEquals(2L, decoded.activeTabId)
    }

    @Test fun badCurrentWorkspaceFallsBackToMain() {
        val decoded = SessionCodec.sanitize(withWorkspaces.copy(currentWorkspaceId = "ghost", activeTabId = 99, activeTabIdByWorkspace = emptyMap()))
        assertEquals("main", decoded.currentWorkspaceId)
        assertEquals(1L, decoded.activeTabId)
    }

    @Test fun emptyCurrentWorkspaceHasNoActiveTab() {
        val data = withWorkspaces.copy(
            workspaces = withWorkspaces.workspaces + Workspace("ws-empty", "Pusta"),
            currentWorkspaceId = "ws-empty", activeTabId = 0, activeTabIdByWorkspace = emptyMap(),
        )
        val decoded = SessionCodec.sanitize(data)
        assertEquals("ws-empty", decoded.currentWorkspaceId)
        assertEquals(0L, decoded.activeTabId) // the caller opens a new tab there
    }

    @Test fun staleActivePerWorkspaceEntriesAreDropped() {
        val data = withWorkspaces.copy(activeTabIdByWorkspace = mapOf("main" to 2L, "ws-work" to 3L, "ghost" to 1L, "x" to 77L))
        assertEquals(mapOf("ws-work" to 3L), SessionCodec.sanitize(data).activeTabIdByWorkspace)
    }

    @Test fun mainWorkspaceIsRestoredWhenMissingFromTheFile() {
        val decoded = SessionCodec.sanitize(withWorkspaces.copy(workspaces = listOf(Workspace("ws-work", "Praca"))))
        assertEquals(listOf("main", "ws-work"), decoded.workspaces.map { it.id })
    }

    @Test fun desktopStyleWorkspaceFieldsAreIgnoredSafely() {
        val text = """{"tabs":[{"id":1,"url":"https://a.example/"}],"activeTabId":1,"workspaces":[{"id":"main","name":"","icon":"◼","color":null,"activeIndex":0}],"currentWorkspaceId":"main"}"""
        assertNotNull(SessionCodec.decode(text))
    }
}
