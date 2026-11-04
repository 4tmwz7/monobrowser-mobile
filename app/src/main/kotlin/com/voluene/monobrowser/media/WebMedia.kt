package com.voluene.monobrowser.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Audio / video playing in a tab, as reported by the page ([SCRIPT]). */
data class WebMediaState(
    val tabId: Long,
    val playing: Boolean,
    val title: String,
    val artist: String,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val private: Boolean,
    /** Cover art: the page's media session artwork, else its og:image (absolute URL; empty = none). */
    val artworkUrl: String = "",
    /** Playback position and length in ms (0 = unknown, e.g. live streams). */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** When [positionMs] was measured (elapsedRealtime), so the system can move the progress bar on. */
    val updatedAt: Long = 0,
    val rate: Float = 1f,
)

/** Commands the media notification sends to the tab that plays (implemented by TabManager). */
interface WebMediaControl {
    fun play(tabId: Long)
    fun pause(tabId: Long)
    fun skip(tabId: Long, forward: Boolean)
    fun seekTo(tabId: Long, positionMs: Long)
}

/**
 * Process-wide bridge between the tabs (TabManager) and WebMediaService: the page that played last is the one
 * the notification, Now Bar, lock screen and watch control.
 */
class WebMediaHub(private val context: android.content.Context) {
    private val _state = MutableStateFlow<WebMediaState?>(null)
    /** Cover of the current page: downloaded once per URL (never for private tabs); monogram fallback. */
    private val _art = MutableStateFlow<Pair<String, android.graphics.Bitmap?>?>(null)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val state: StateFlow<WebMediaState?> = _state.asStateFlow()
    @Volatile var control: WebMediaControl? = null

    fun update(state: WebMediaState?) {
        _state.value = state
        val url = state?.artworkUrl?.takeIf { !state.private && it.startsWith("http") }.orEmpty()
        if (state != null && _art.value?.first != url) {
            _art.value = url to null
            if (url.isNotEmpty()) scope.launch {
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Artwork.download(url) }
                if (_art.value?.first == url) _art.value = url to bitmap
            }
        }
    }

    /** The tab went away (closed, navigated, slept): its media is no longer controllable. */
    fun clear(tabId: Long) {
        if (_state.value?.tabId == tabId) _state.value = null
    }

    val source: MediaSource = object : MediaSource {
        override val info = kotlinx.coroutines.flow.combine(_state, _art) { s, _ -> s?.toInfo() }
        override fun current() = _state.value?.toInfo()
        override fun seekTo(positionMs: Long) { _state.value?.let { control?.seekTo(it.tabId, positionMs) } }
        override fun play() { _state.value?.let { control?.play(it.tabId) } }
        override fun pause() { _state.value?.let { control?.pause(it.tabId) } }
        override fun skip(forward: Boolean) { _state.value?.let { control?.skip(it.tabId, forward) } }
        override fun stop() {
            _state.value?.let { control?.pause(it.tabId) }
            _state.value = null
        }
    }

    private fun WebMediaState.toInfo() = MediaInfo(
        title, artist, playing, canSkip = canNext || canPrevious, private = private,
        rate = rate, positionMs = positionMs, durationMs = durationMs, updatedAt = updatedAt, canSeek = durationMs > 0,
        artwork = if (private) null else _art.value?.second ?: Artwork.monogram(context, artist),
    )

    companion object {
        const val BRIDGE = "MonoMedia"

        /**
         * Runs in every page (document start): reports play / pause of <video> and <audio> (the events are caught
         * in the capture phase, they do not bubble) with the Media Session metadata when the page sets it, and
         * remembers the page's media session action handlers so next / previous track can call them. Only state is
         * reported; the bridge has no other function.
         */
        val SCRIPT: String = """
            (function () {
              if (window.__monoMedia) return;
              window.__monoMedia = true;
              var handlers = {}, last = null;
              var ms = navigator.mediaSession;
              if (ms && ms.setActionHandler) {
                var original = ms.setActionHandler.bind(ms);
                ms.setActionHandler = function (action, handler) {
                  handlers[action] = handler;
                  try { original(action, handler); } catch (e) {}
                };
              }
              function artwork(md) {
                var best = '', size = -1;
                ((md && md.artwork) || []).forEach(function (a) {
                  var s = parseInt(String(a.sizes || '0').split('x')[0], 10) || 0;
                  if (a.src && s > size) { size = s; best = a.src; }
                });
                if (!best) {
                  var og = document.querySelector('meta[property="og:image"]');
                  best = og && og.content ? og.content : '';
                }
                try { return best ? new URL(best, location.href).href : ''; } catch (e) { return ''; }
              }
              function report() {
                var media = document.querySelectorAll('video,audio'), playing = false;
                for (var i = 0; i < media.length; i++) {
                  if (!media[i].paused && !media[i].ended) { playing = true; last = media[i]; break; }
                }
                var md = ms && ms.metadata;
                var title = (md && md.title) || document.title || '';
                var artist = (md && md.artist) || location.hostname.replace(/^www\./, '');
                var pos = last ? Math.round((last.currentTime || 0) * 1000) : 0;
                var dur = last && isFinite(last.duration) ? Math.round(last.duration * 1000) : 0;
                var rate = last ? (last.playbackRate || 1) : 1;
                try { $BRIDGE.state(playing, title, artist, !!handlers.nexttrack, !!handlers.previoustrack, artwork(md), pos, dur, rate); } catch (e) {}
              }
              ['play', 'playing', 'pause', 'ended', 'emptied', 'seeked', 'ratechange', 'durationchange', 'loadedmetadata'].forEach(function (type) {
                document.addEventListener(type, function (e) {
                  var t = e.target;
                  if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
                    if (type === 'play' || type === 'playing') last = t;
                    report();
                  }
                }, true);
              });
              window.__monoMediaPlay = function () {
                if (handlers.play) { try { handlers.play({ action: 'play' }); return; } catch (e) {} }
                if (last) last.play();
              };
              window.__monoMediaPause = function () {
                if (handlers.pause) { try { handlers.pause({ action: 'pause' }); } catch (e) {} }
                var media = document.querySelectorAll('video,audio');
                for (var i = 0; i < media.length; i++) media[i].pause();
              };
              window.__monoMediaSeek = function (ms) {
                if (handlers.seekto) { try { handlers.seekto({ action: 'seekto', seekTime: ms / 1000 }); return; } catch (e) {} }
                if (last) last.currentTime = ms / 1000;
              };
              window.__monoMediaSkip = function (forward) {
                var h = handlers[forward ? 'nexttrack' : 'previoustrack'];
                if (h) { try { h({ action: forward ? 'nexttrack' : 'previoustrack' }); } catch (e) {} }
              };
            })();
        """.trimIndent()
    }
}
