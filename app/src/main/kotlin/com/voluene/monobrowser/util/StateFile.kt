package com.voluene.monobrowser.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Small persisted value: an observable [state] mirrored to a JSON [file] with atomic writes (see
 * [writeTextAtomically]). Reads happen once at construction; writes go to [dispatcher] and always persist the
 * newest value, so rapid updates never leave an older snapshot on disk. [file] may be null (tests).
 */
class StateFile<T : Any>(
    private val file: File?,
    initial: T,
    private val decode: (String) -> T?,
    private val encode: (T) -> String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Read the file on [dispatcher] instead of during construction (data the first frame does not need). [state]
     * starts at the initial value; [value] and [update] read the file right away if it has not been read yet.
     */
    loadInBackground: Boolean = false,
) {
    /** True when the file existed and was readable at construction (used for "first launch" detection; always false with loadInBackground). */
    val existed: Boolean
    private val _state: MutableStateFlow<T>
    private val lock = Any()
    @Volatile private var loaded = false

    private fun read(): T? = file?.takeIf { it.isFile }?.let { runCatching { decode(it.readText()) }.getOrNull() }

    init {
        if (loadInBackground && file != null) {
            existed = false
            _state = MutableStateFlow(initial)
            scope.launch(dispatcher) { ensureLoaded() }
        } else {
            val value = read()
            existed = value != null
            _state = MutableStateFlow(value ?: initial)
            loaded = true
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            read()?.let { _state.value = it }
            loaded = true
        }
    }

    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() { ensureLoaded(); return _state.value }

    fun update(transform: (T) -> T) {
        ensureLoaded()
        _state.update(transform)
        persist()
    }

    private fun persist() {
        val target = file ?: return
        scope.launch(dispatcher) {
            synchronized(lock) { runCatching { writeTextAtomically(target, encode(_state.value)) } }
        }
    }
}
