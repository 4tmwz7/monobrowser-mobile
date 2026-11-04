package com.voluene.monobrowser.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Where read-aloud is: [block] is the article block being spoken (-1 = none). */
data class ReadAloudState(
    val speaking: Boolean = false,
    val block: Int = -1,
    val rate: Float = 1f,
    val available: Boolean = true,
    /** The article being read (null = nothing loaded); title / site feed the media notification and Now Bar. */
    val articleUrl: String? = null,
    val title: String = "",
    val site: String = "",
    /** Read to the end. */
    val finished: Boolean = false,
)

/**
 * Reader view's "read aloud": the system TextToSpeech engine speaks the article block by block (offline voices
 * where installed), in the page's language when the engine has it. The current block is published so the
 * reader can highlight it and scroll along. One instance per process (MonoApp): reading goes on after the
 * reader closes, controlled from the media notification, the lock screen, Samsung's Now Bar or a watch
 * (ReadAloudService publishes it as a media session).
 */
class ReadAloud(context: Context) {
    private val app = context.applicationContext
    private var current: ReaderArticle? = null

    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private var ready = false
    private var pendingStart: (() -> Unit)? = null
    private var chunks: List<Pair<Int, String>> = emptyList()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) _state.value = _state.value.copy(available = false, speaking = false)
        pendingStart?.takeIf { ready }?.invoke()
        pendingStart = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val block = utteranceId.substringBefore(':').toIntOrNull() ?: return
                _state.value = _state.value.copy(speaking = true, block = block)
            }

            override fun onDone(utteranceId: String) {
                if (utteranceId.endsWith(LAST)) _state.value = _state.value.copy(speaking = false, block = -1, finished = true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                _state.value = _state.value.copy(speaking = false)
            }
        })
    }

    /** Speaks [article] from block [from] on (and shows the media controls). */
    fun play(article: ReaderArticle, from: Int = 0) {
        current = article
        _state.value = _state.value.copy(articleUrl = article.url, title = article.title, site = article.site, finished = false)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, com.voluene.monobrowser.ReadAloudService::class.java))
        }
        val start = {
            tts.language = pickLocale(article.language)
            tts.setSpeechRate(_state.value.rate)
            chunks = Speech.chunks(article, tts.maxInputLength())
            tts.stop()
            val todo = chunks.filter { it.first >= from }
            todo.forEachIndexed { i, (block, text) ->
                val id = if (i == todo.lastIndex) "$block:$i$LAST" else "$block:$i"
                tts.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
            _state.value = _state.value.copy(speaking = todo.isNotEmpty(), block = todo.firstOrNull()?.first ?: -1)
        }
        if (ready) start() else pendingStart = start
    }

    /** Stops speaking; [resumeFrom] tells play where to continue. */
    fun pause() {
        tts.stop()
        _state.value = _state.value.copy(speaking = false)
    }

    val resumeFrom: Int get() = _state.value.block.coerceAtLeast(0)

    /** Continues the loaded article where it paused. */
    fun resume() {
        current?.let { play(it, resumeFrom) }
    }

    /** Next / previous paragraph (headings and list items count; code is skipped). */
    fun skip(forward: Boolean) {
        val article = current ?: return
        val blocks = Speech.chunks(article, Int.MAX_VALUE).map { it.first }.distinct()
        val at = _state.value.block.coerceAtLeast(0)
        val target = if (forward) blocks.firstOrNull { it > at } else blocks.lastOrNull { it < at }
        if (target != null) play(article, target) else if (!forward) play(article, 0)
    }

    /** Stops and forgets the article (the media controls go away). */
    fun stop() {
        current = null
        runCatching { tts.stop() }
        _state.value = ReadAloudState(rate = _state.value.rate, available = _state.value.available)
    }

    /** 0.8x -> 1x -> 1.25x -> 1.5x -> 0.8x; applies from the next block on (the engine reads rates per call). */
    fun cycleRate(article: ReaderArticle) {
        val next = Speech.nextRate(_state.value.rate)
        _state.value = _state.value.copy(rate = next)
        if (_state.value.speaking) play(article, resumeFrom)
    }

    fun shutdown() {
        pendingStart = null
        runCatching { tts.stop(); tts.shutdown() }
    }

    private fun pickLocale(language: String): Locale {
        val wanted = language.takeIf { it.isNotBlank() }?.replace('_', '-')?.let(Locale::forLanguageTag)
        if (wanted != null && tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE) return wanted
        return Locale.getDefault()
    }

    private fun TextToSpeech.maxInputLength(): Int = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000)

    private companion object {
        const val LAST = ":last"
    }
}

/** Pure parts of read-aloud (unit-tested). */
object Speech {
    val RATES = listOf(0.8f, 1f, 1.25f, 1.5f)

    fun nextRate(current: Float): Float = RATES.firstOrNull { it > current + 0.01f } ?: RATES.first()

    /**
     * The article as (block index, text) pieces no longer than [max] characters, split at sentence ends (or spaces)
     * when a block is too long. The title is spoken first (block index -1 is not used: it belongs to block 0's turn).
     */
    fun chunks(article: ReaderArticle, max: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        if (article.title.isNotBlank()) out.add(0 to article.title)
        article.blocks.forEachIndexed { index, block ->
            if (block.kind == BlockKind.CODE) return@forEachIndexed // code is not read out
            split(block.text, max).forEach { out.add(index to it) }
        }
        return out
    }

    fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(". ", "! ", "? ", "; ", ", ", " ").firstNotNullOfOrNull { sep ->
                window.lastIndexOf(sep).takeIf { it > max / 3 }?.let { it + sep.length }
            } ?: max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
