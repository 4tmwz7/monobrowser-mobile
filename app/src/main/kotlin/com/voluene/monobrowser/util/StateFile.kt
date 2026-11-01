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
) {
    /** True when the file existed and was readable at construction (used for "first launch" detection). */
    val existed: Boolean
    private val _state: MutableStateFlow<T>
    private val lock = Any()

    init {
        val loaded = file?.takeIf { it.isFile }?.let { runCatching { decode(it.readText()) }.getOrNull() }
        existed = loaded != null
        _state = MutableStateFlow(loaded ?: initial)
    }

    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    fun update(transform: (T) -> T) {
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
