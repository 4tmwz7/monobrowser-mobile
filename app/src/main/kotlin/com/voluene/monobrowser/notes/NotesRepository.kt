package com.voluene.monobrowser.notes

import com.voluene.monobrowser.util.writeTextAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A note in the list. [modified] is the file's last-modified time (ms). */
data class NoteMeta(val id: String, val title: String, val preview: String, val modified: Long)

/**
 * Notes as plain Markdown files: `<dir>/<id>.md`, UTF-8, contents exactly what the user typed. This is the same
 * format as the desktop `notes.md`, so a note can be copied between devices by copying the file (the file name
 * only has to stay a valid [id]). Writes are atomic.
 */
class NotesStore(private val dir: File) {
    fun isValidId(id: String) = ID_PATTERN.matches(id)

    fun list(): List<NoteMeta> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }.orEmpty()
            .mapNotNull { file ->
                val id = file.name.removeSuffix(EXTENSION)
                if (!isValidId(id)) return@mapNotNull null
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                NoteMeta(id, NoteText.title(text), NoteText.preview(text), file.lastModified())
            }
            .sortedByDescending { it.modified }

    fun read(id: String): String? =
        if (!isValidId(id)) null else runCatching { File(dir, id + EXTENSION).readText() }.getOrNull()

    fun write(id: String, text: String): Boolean {
        if (!isValidId(id)) return false
        return runCatching { writeTextAtomically(File(dir, id + EXTENSION), NoteText.clamp(text)) }.isSuccess
    }

    fun delete(id: String) {
        if (isValidId(id)) File(dir, id + EXTENSION).delete()
    }

    fun newId(random: () -> Long = { kotlin.random.Random.nextLong(0, Long.MAX_VALUE) }): String {
        repeat(20) {
            val id = "n-" + System.currentTimeMillis().toString(36) + "-" + (random() % 46656).toString(36)
            if (!File(dir, id + EXTENSION).exists()) return id
        }
        return "n-" + System.nanoTime().toString(36)
    }

    companion object {
        const val EXTENSION = ".md"
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
    }
}

/** Observable note list on top of [NotesStore]; edits are written after a short debounce (and by [flush]). */
class NotesRepository(
    private val store: NotesStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _notes = MutableStateFlow(store.list())
    val notes: StateFlow<List<NoteMeta>> = _notes.asStateFlow()

    private val pending = HashMap<String, String>()
    private val lock = Any()
    private var writeJob: Job? = null

    /** Creates an empty note (the file is written right away so it survives a crash) and returns its id. */
    fun create(initial: String = ""): String {
        val id = store.newId()
        store.write(id, initial)
        _notes.update { listOf(metaFor(id, initial)) + it }
        return id
    }

    fun read(id: String): String = synchronized(lock) { pending[id] } ?: store.read(id).orEmpty()

    /** Updates the list immediately; the file follows after the debounce. */
    fun save(id: String, text: String) {
        val clamped = NoteText.clamp(text)
        synchronized(lock) { pending[id] = clamped }
        _notes.update { list -> listOf(metaFor(id, clamped)) + list.filterNot { it.id == id } }
        writeJob?.cancel()
        writeJob = scope.launch(dispatcher) {
            delay(debounceMs)
            flush()
        }
    }

    /** Writes every pending edit now (blocking; called when the editor closes and from `onStop`). */
    fun flush() {
        val batch = synchronized(lock) { HashMap(pending).also { pending.clear() } }
        batch.forEach { (id, text) -> store.write(id, text) }
    }

    fun delete(id: String) {
        synchronized(lock) { pending.remove(id) }
        store.delete(id)
        _notes.update { list -> list.filterNot { it.id == id } }
    }

    /** Deletes a note that was left blank (nobody wants an empty entry in the list). */
    fun discardIfBlank(id: String) {
        if (read(id).isBlank()) delete(id)
    }

    /** Appends a page link to the most recently edited note (a new note when there is none). Returns the note id. */
    fun appendPage(pageTitle: String, url: String): String {
        val id = _notes.value.firstOrNull()?.id ?: create()
        save(id, NoteText.appendPage(read(id), pageTitle, url))
        return id
    }

    fun titleOf(id: String): String = _notes.value.firstOrNull { it.id == id }?.title.orEmpty()

    private fun metaFor(id: String, text: String) =
        NoteMeta(id, NoteText.title(text), NoteText.preview(text), clock())
}
