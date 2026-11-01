package com.voluene.monobrowser.notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteTextTest {
    @Test fun titleIsTheFirstContentLineWithoutMarkers() {
        assertEquals("Shopping", NoteText.title("# Shopping\n- milk"))
        assertEquals("Milk", NoteText.title("\n\n  - Milk"))
        assertEquals("quote", NoteText.title("> quote"))
        assertEquals("", NoteText.title("   \n\n"))
        assertEquals(80, NoteText.title("x".repeat(200)).length)
    }

    @Test fun previewSkipsTheTitleLine() {
        assertEquals("one two", NoteText.preview("# T\none\ntwo"))
        assertEquals("", NoteText.preview("only title"))
    }

    @Test fun appendPageBuildsATidyList() {
        assertEquals("- [Example](https://e.example/)\n", NoteText.appendPage("", "Example", "https://e.example/"))
        assertEquals("# Links\n- [A](https://a/)\n- [B](https://b/)\n", NoteText.appendPage("# Links\n- [A](https://a/)\n\n\n", "B", "https://b/"))
        assertEquals("- https://e.example/\n", NoteText.appendPage("", "  ", "https://e.example/"))
        assertEquals("- [a (b) c](https://e/)\n", NoteText.appendPage("", "a [b]\nc", "https://e/"))
    }

    @Test fun clampLimitsSize() {
        assertEquals(NoteText.MAX_CHARS, NoteText.clamp("x".repeat(NoteText.MAX_CHARS + 5)).length)
    }
}

class NotesStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun writeReadListDelete() {
        val dir = File(temp.root, "notes")
        val store = NotesStore(dir)
        assertEquals(emptyList<NoteMeta>(), store.list()) // missing directory is fine
        assertTrue(store.write("abc", "# Hello\nbody ąę"))
        assertEquals("# Hello\nbody ąę", store.read("abc"))
        assertEquals(listOf("abc"), store.list().map { it.id })
        assertEquals("Hello", store.list().single().title)
        assertFalse(File(dir, "abc.md.tmp").exists()) // atomic write leaves no temp file
        store.delete("abc")
        assertNull(store.read("abc"))
    }

    @Test fun fileIsPlainMarkdownLikeTheDesktopNote() {
        val dir = File(temp.root, "notes").apply { mkdirs() }
        File(dir, "notes.md").writeText("# From desktop\nline") // a desktop notes.md copied over
        val store = NotesStore(dir)
        assertEquals("From desktop", store.list().single().title)
        store.write("notes", "changed")
        assertEquals("changed", File(dir, "notes.md").readText())
    }

    @Test fun idsCannotEscapeTheDirectory() {
        val store = NotesStore(File(temp.root, "notes"))
        assertFalse(store.write("../evil", "x"))
        assertFalse(store.write("a/b", "x"))
        assertFalse(store.write("", "x"))
        assertNull(store.read("../evil"))
        assertFalse(File(temp.root, "evil.md").exists())
    }

    @Test fun newIdsAreValidAndUnique() {
        val store = NotesStore(File(temp.root, "notes"))
        val ids = (1..20).map { store.newId().also { id -> store.write(id, "x") } }
        assertEquals(20, ids.toSet().size)
        assertTrue(ids.all(store::isValidId))
    }

    @Test fun listIsNewestFirst() {
        val dir = File(temp.root, "notes").apply { mkdirs() }
        File(dir, "old.md").apply { writeText("old"); setLastModified(1_000_000) }
        File(dir, "new.md").apply { writeText("new"); setLastModified(5_000_000) }
        assertEquals(listOf("new", "old"), NotesStore(dir).list().map { it.id })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NotesRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repo(scope: TestScope, clock: () -> Long = { 0 }) =
        NotesRepository(NotesStore(File(temp.root, "notes")), scope, StandardTestDispatcher(scope.testScheduler), debounceMs = 500, clock = clock)

    @Test fun editsAreWrittenAfterTheDebounceAndFlushWritesAtOnce() = runTest {
        val repository = repo(this)
        val id = repository.create()
        repository.save(id, "first")
        runCurrent()
        assertEquals("", File(temp.root, "notes/$id.md").readText()) // not yet
        advanceTimeBy(501)
        runCurrent()
        assertEquals("first", File(temp.root, "notes/$id.md").readText())
        repository.save(id, "second")
        repository.flush()
        assertEquals("second", File(temp.root, "notes/$id.md").readText())
    }

    @Test fun listUpdatesImmediatelyAndMostRecentComesFirst() = runTest {
        var now = 1L
        val repository = repo(this) { now++ }
        val a = repository.create("# A")
        val b = repository.create("# B")
        assertEquals(listOf(b, a), repository.notes.value.map { it.id })
        repository.save(a, "# A edited")
        assertEquals(listOf(a, b), repository.notes.value.map { it.id })
        assertEquals("A edited", repository.notes.value.first().title)
        assertEquals("# A edited", repository.read(a)) // pending edit is visible before it hits the disk
    }

    @Test fun blankNotesAreDiscardedOthersKept() = runTest {
        val repository = repo(this)
        val blank = repository.create()
        val kept = repository.create("text")
        repository.discardIfBlank(blank)
        repository.discardIfBlank(kept)
        assertEquals(listOf(kept), repository.notes.value.map { it.id })
        assertFalse(File(temp.root, "notes/$blank.md").exists())
    }

    @Test fun appendPageUsesTheLatestNoteOrCreatesOne() = runTest {
        val repository = repo(this)
        val created = repository.appendPage("Site", "https://s.example/")
        assertEquals(listOf(created), repository.notes.value.map { it.id })
        val again = repository.appendPage("Other", "https://o.example/")
        assertEquals(created, again)
        assertEquals("- [Site](https://s.example/)\n- [Other](https://o.example/)\n", repository.read(created))
    }

    @Test fun deleteRemovesFileAndPendingEdit() = runTest {
        val repository = repo(this)
        val id = repository.create("x")
        repository.save(id, "y")
        repository.delete(id)
        repository.flush()
        assertFalse(File(temp.root, "notes/$id.md").exists())
        assertTrue(repository.notes.value.isEmpty())
    }
}
