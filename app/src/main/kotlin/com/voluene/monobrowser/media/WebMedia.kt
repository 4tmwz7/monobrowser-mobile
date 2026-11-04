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
    /** Size of the playing video in pixels (0 = audio or unknown); used for the picture-in-picture window. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    val isVideo: Boolean get() = videoWidth > 0 && videoHeight > 0
}

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
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Artwork.cover(url) }
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
         * Runs in every page (document start):
         * - reports play / pause of <video> and <audio> (caught in the capture phase, the events do not bubble) with
         *   title, artist and cover: the Media Session metadata when the page sets it, else the page title without
         *   the site suffix and the video's poster (YouTube: the video's thumbnail). Pages that set their metadata
         *   or title after playback started (YouTube) are reported again when they do, and every few seconds while
         *   playing (position, title);
         * - keeps the page "visible" (Page Visibility API) so sites that stop playback when the app goes to the
         *   background (YouTube) keep playing, like in a desktop browser with the window behind another one;
         * - remembers the page's media session action handlers so next / previous track can call them;
         * - [__monoPip] lets the playing video fill the page for the picture-in-picture window.
         * Only state is reported; the bridge has no other function.
         */
        val SCRIPT: String = """
            (function () {
              if (window.__monoMedia) return;
              window.__monoMedia = true;
              // Background playback: the page never learns it was hidden.
              try {
                var dp = Document.prototype;
                Object.defineProperty(dp, 'hidden', { get: function () { return false; }, configurable: true });
                Object.defineProperty(dp, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
                Object.defineProperty(dp, 'webkitHidden', { get: function () { return false; }, configurable: true });
                Object.defineProperty(dp, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
                ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
                  window.addEventListener(type, function (e) { e.stopImmediatePropagation(); }, true);
                });
              } catch (e) {}
              var handlers = {}, last = null, ticker = 0, pending = 0;
              var TYPES = ['play', 'playing', 'pause', 'ended', 'emptied', 'seeked', 'ratechange', 'durationchange', 'loadedmetadata', 'resize'];
              var ms = navigator.mediaSession;
              if (ms && ms.setActionHandler) {
                var original = ms.setActionHandler.bind(ms);
                ms.setActionHandler = function (action, handler) {
                  handlers[action] = handler;
                  try { original(action, handler); } catch (e) {}
                };
              }
              // Metadata set after playback started (the usual case) is reported right away.
              try {
                var proto = window.MediaSession && MediaSession.prototype;
                ['metadata', 'playbackState'].forEach(function (name) {
                  var d = proto && Object.getOwnPropertyDescriptor(proto, name);
                  if (d && d.set) {
                    Object.defineProperty(proto, name, {
                      configurable: true, enumerable: d.enumerable,
                      get: d.get, set: function (v) { d.set.call(this, v); soon(); }
                    });
                  }
                });
              } catch (e) {}
              function youtubeId() {
                try {
                  var u = new URL(location.href), h = u.hostname.replace(/^(www|m|music)\./, '');
                  if (h === 'youtu.be') return u.pathname.slice(1, 12);
                  if (h !== 'youtube.com' && h !== 'youtube-nocookie.com') return '';
                  var v = u.searchParams.get('v');
                  if (v) return v;
                  var m = u.pathname.match(/^\/(shorts|embed|live)\/([\w-]{6,})/);
                  return m ? m[2] : '';
                } catch (e) { return ''; }
              }
              function abs(src) { try { return src ? new URL(src, location.href).href : ''; } catch (e) { return ''; } }
              function artwork(md) {
                var best = '', size = -1;
                ((md && md.artwork) || []).forEach(function (a) {
                  var s = parseInt(String(a.sizes || '0').split('x')[0], 10) || 0;
                  if (a.src && s > size) { size = s; best = a.src; }
                });
                if (best) return abs(best);
                var yt = youtubeData(), id = (yt && yt.video_id) || youtubeId();
                if (id) return 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';
                if (last && last.poster) return abs(last.poster);
                var og = document.querySelector('meta[property="og:image"]');
                return abs(og && og.content);
              }
              function site() {
                var og = document.querySelector('meta[property="og:site_name"]');
                return (og && og.content) || location.hostname.replace(/^(www|m)\./, '');
              }
              // "(3) Song name - YouTube" -> "Song name"
              function pageTitle() {
                var t = (document.title || '').replace(/^\(\d+\+?\)\s*/, '').trim();
                var s = site();
                var cut = t.match(/^(.+?)\s+[-\u2013\u2014|\u00b7]\s+([^-\u2013\u2014|\u00b7]{1,40})$/);
                if (cut && (cut[2].toLowerCase() === s.toLowerCase() || location.hostname.indexOf(cut[2].toLowerCase().replace(/\s+/g, '')) >= 0)) t = cut[1];
                return t;
              }
              // YouTube's own player knows the current video (title, channel, id), also right after a switch.
              function youtubeData() {
                try {
                  var p = document.getElementById('movie_player');
                  var d = p && p.getVideoData && p.getVideoData();
                  return d && d.video_id ? d : null;
                } catch (e) { return null; }
              }
              // Players that never put their element in the page (new Audio(): SoundCloud, many music sites).
              var detached = [];
              function track(el) {
                if (!el || el.__monoTracked) return;
                el.__monoTracked = true;
                TYPES.forEach(function (type) { el.addEventListener(type, function () { if (type === 'play' || type === 'playing') last = el; soon(); }); });
                if (!el.isConnected) {
                  detached.push(el);
                  if (detached.length > 32) detached.shift();
                }
              }
              function allMedia() {
                var list = Array.prototype.slice.call(document.querySelectorAll('video,audio'));
                for (var i = 0; i < detached.length; i++) if (list.indexOf(detached[i]) < 0) list.push(detached[i]);
                if (last && list.indexOf(last) < 0) list.push(last);
                return list;
              }
              try {
                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function () {
                  track(this);
                  last = this;
                  var result = origPlay.apply(this, arguments);
                  soon();
                  return result;
                };
              } catch (e) {}
              // While the app is in the background or a floating window, the page may not pause by itself (YouTube
              // pauses when it thinks it is not watched); pauses asked for by the user (notification, window
              // button, lock screen) go through __monoMediaPause and are let through.
              var guard = false, allowPause = 0;
              try {
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function () {
                  if (guard && Date.now() > allowPause && !this.paused && !this.muted) return;
                  return origPause.apply(this, arguments);
                };
              } catch (e) {}
              window.__monoGuard = function (on) { guard = !!on; };
              window.__monoMediaPlaying = function () {
                var media = allMedia();
                for (var i = 0; i < media.length; i++) if (!media[i].paused && !media[i].ended && !media[i].muted) return true;
                return false;
              };
              function report() {
                pending = 0;
                var media = allMedia(), playing = false;
                for (var i = 0; i < media.length; i++) {
                  if (!media[i].paused && !media[i].ended) { playing = true; last = media[i]; break; }
                }
                // Players without a media element (Web Audio) count when they declare it through the Media Session.
                if (!playing && ms && ms.metadata && ms.playbackState === 'playing') playing = true;
                var md = ms && ms.metadata, yt = youtubeData();
                var title = (md && md.title) || (yt && yt.title) || pageTitle();
                var artist = (md && md.artist) || (yt && yt.author) || site();
                var pos = last ? Math.round((last.currentTime || 0) * 1000) : 0;
                var dur = last && isFinite(last.duration) ? Math.round(last.duration * 1000) : 0;
                var rate = last ? (last.playbackRate || 1) : 1;
                var w = last && last.tagName === 'VIDEO' ? (last.videoWidth || 0) : 0;
                var h = last && last.tagName === 'VIDEO' ? (last.videoHeight || 0) : 0;
                try { $BRIDGE.state(playing, title, artist, !!handlers.nexttrack, !!handlers.previoustrack, artwork(md), pos, dur, rate, w, h); } catch (e) {}
                // While playing: again every few seconds (title and metadata that change without an event).
                if (playing && !ticker) ticker = setInterval(report, 4000);
                if (!playing && ticker) { clearInterval(ticker); ticker = 0; }
              }
              function soon() { if (!pending) pending = setTimeout(report, 150); }
              TYPES.forEach(function (type) {
                document.addEventListener(type, function (e) {
                  var t = e.target;
                  if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
                    if (type === 'play' || type === 'playing') last = t;
                    soon();
                  }
                }, true);
              });
              // A new title (single-page sites switch videos without loading a page).
              document.addEventListener('DOMContentLoaded', function () {
                var t = document.querySelector('title');
                if (t && window.MutationObserver) new MutationObserver(function () { if (last) soon(); }).observe(t, { childList: true, characterData: true, subtree: true });
              });
              window.__monoMediaPlay = function () {
                if (handlers.play) { try { handlers.play({ action: 'play' }); return; } catch (e) {} }
                if (last) last.play();
              };
              window.__monoMediaPause = function () {
                allowPause = Date.now() + 1500;
                if (handlers.pause) { try { handlers.pause({ action: 'pause' }); } catch (e) {} }
                var media = allMedia();
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
              // Picture in picture: only the playing video, covering the whole window. Everything else is hidden
              // (visibility, so the page keeps its state), and the video's ancestors lose transforms and filters:
              // those would make "position: fixed" relative to them (YouTube's mini player) instead of the window.
              window.__monoPip = function (on) {
                var style = document.getElementById('__mono_pip');
                var marked = document.querySelectorAll('[data-mono-pip],[data-mono-pip-up]');
                for (var i = 0; i < marked.length; i++) { marked[i].removeAttribute('data-mono-pip'); marked[i].removeAttribute('data-mono-pip-up'); }
                if (!on) { if (style) style.remove(); return; }
                var video = null, media = allMedia();
                for (var k = 0; k < media.length; k++) {
                  var m = media[k];
                  if (m.tagName === 'VIDEO' && m.isConnected && !m.paused && !m.ended) { video = m; break; }
                }
                if (!video && last && last.tagName === 'VIDEO' && last.isConnected) video = last;
                if (!video) return;
                video.setAttribute('data-mono-pip', '');
                for (var el = video.parentElement; el; el = el.parentElement) el.setAttribute('data-mono-pip-up', '');
                if (!style) {
                  style = document.createElement('style');
                  style.id = '__mono_pip';
                  style.textContent =
                    'html,body{visibility:hidden!important;overflow:hidden!important;background:#000!important}' +
                    '[data-mono-pip-up]{transform:none!important;filter:none!important;perspective:none!important;' +
                    'contain:none!important;will-change:auto!important;backdrop-filter:none!important}' +
                    'video[data-mono-pip]{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;' +
                    'right:auto!important;bottom:auto!important;margin:0!important;' +
                    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
                    'z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important}';
                  (document.head || document.documentElement).appendChild(style);
                }
              };
            })();
        """.trimIndent()
    }
}
