package com.voluene.monobrowser.adblock

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Snapshot for the settings screen. */
enum class ListsState { IDLE, LOADING, READY, ERROR, WAITING_NETWORK, WAITING_WIFI }

data class ListsStatus(
    val state: ListsState = ListsState.IDLE,
    /** Epoch ms of the last complete engine build (0 = never). */
    val lastUpdateMs: Long = 0,
    val listsOnDisk: Int = 0,
    val listsTotal: Int = 0,
    val error: String? = null,
    val failures: Int = 0,
) {
    val updating: Boolean get() = state == ListsState.LOADING
}

/**
 * Keeps the filter engine up to date, off the main thread:
 *  1. loads the serialized engine cache (fast, no list parsing),
 *  2. downloads the lists when there is no cache or it is older than [RefreshPolicy.REFRESH_INTERVAL_MS],
 *  3. rebuilds the engine, writes the cache and publishes it through [onEngine].
 * Until the first engine is published the app runs with a no-op blocker (non-blocking start).
 *
 * Network robustness (first launches used to fail while the radio/Wi-Fi was not ready yet):
 *  - no download is attempted without an internet-capable network; the manager waits for a connectivity
 *    callback ([NetworkGate.watch]) and re-checks every minute as a fallback,
 *  - failed rounds retry with exponential backoff ([RetryBackoff]) and only fetch the lists that are missing,
 *  - short timeouts, bounded round duration, every list logs its outcome (tag [NativeAdblock.TAG]),
 *  - "Wi-Fi only" refuses metered connections ([wifiOnly]); an explicit [updateNow] ignores that switch.
 *
 * Files under [dir]: `lists/<id>.txt` (raw lists) and `engine-v<N>.bin` (serialized engine, its mtime is
 * the "last built" timestamp, like on desktop).
 */
internal class FilterListManager(
    private val dir: File,
    private val lists: List<FilterList> = FilterLists.ALL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gate: NetworkGate,
    private val onEngine: (NativeEngine) -> Unit,
) {
    @Volatile var state: ListsState = ListsState.IDLE
        private set

    @Volatile var lastError: String? = null
        private set

    /** Refuse metered networks for automatic updates (set from the settings). */
    @Volatile var wifiOnly: Boolean = false

    private val _status = MutableStateFlow(ListsStatus(listsTotal = lists.size))
    val status: StateFlow<ListsStatus> = _status.asStateFlow()

    @Volatile private var hasEngine = false
    private var cacheLoaded = false
    private var failures = 0
    private var lastRoundStart = 0L
    /** Lists that still have to be (re)downloaded after a partially failed round. */
    private val pending = LinkedHashSet<String>()
    private var nextRun: ScheduledFuture<*>? = null
    private var stopWatching: (() -> Unit)? = null
    private val started = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)
    @Volatile private var forceNext = false

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "adblock-lists").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val cacheFile get() = File(dir, "engine-v${FilterLists.SET_VERSION}.bin")
    private val listsDir get() = File(dir, "lists")

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!NativeAdblock.available) {
            state = ListsState.ERROR
            lastError = "native library missing"
            publishStatus()
            return
        }
        Log.i(NativeAdblock.TAG, "native engine loaded, starting list manager")
        stopWatching = gate.watch { onConnectivityChanged() }
        requestRun(force = false)
    }

    /** User pressed "update now": runs a full round as soon as possible (ignores staleness and Wi-Fi-only). */
    fun updateNow() {
        if (state == ListsState.LOADING) return
        requestRun(force = true)
    }

    /** The Wi-Fi-only switch changed: re-evaluate waiting state. */
    fun settingsChanged() = requestRun(force = false)

    private fun onConnectivityChanged() {
        val waiting = state == ListsState.WAITING_NETWORK || state == ListsState.WAITING_WIFI || state == ListsState.ERROR || failures > 0
        if (!waiting || state == ListsState.LOADING) return
        if (clock() - lastRoundStart < RetryBackoff.MIN_CALLBACK_GAP_MS) return
        Log.i(NativeAdblock.TAG, "connectivity changed while waiting (state=$state), retrying now")
        requestRun(force = false)
    }

    private fun requestRun(force: Boolean) {
        if (force) forceNext = true
        if (!queued.compareAndSet(false, true)) return
        scheduler.execute { runRound() }
    }

    private fun scheduleNext(delayMs: Long) {
        nextRun?.cancel(false)
        Log.d(NativeAdblock.TAG, "next list check in ${delayMs / 1000} s")
        nextRun = scheduler.schedule({ requestRun(force = false) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun lastBuilt(): Long = cacheFile.lastModified()

    private fun runRound() {
        queued.set(false)
        val force = forceNext
        forceNext = false
        try {
            if (!cacheLoaded) {
                cacheLoaded = true
                loadCache()
            }
            val aged = RefreshPolicy.isStale(lastBuilt(), clock())
            val wantFull = force || (pending.isEmpty() && (aged || !hasEngine))
            if (!wantFull && pending.isEmpty()) {
                state = if (hasEngine) ListsState.READY else ListsState.ERROR
                publishStatus()
                scheduleNext(RefreshPolicy.nextDelayMs(lastBuilt(), clock(), false))
                return
            }
            val net = gate.status(wifiOnly && !force)
            if (net != NetStatus.OK) {
                state = if (net == NetStatus.WIFI_REQUIRED) ListsState.WAITING_WIFI else ListsState.WAITING_NETWORK
                Log.i(NativeAdblock.TAG, "list update postponed: $net")
                publishStatus()
                scheduleNext(RetryBackoff.NETWORK_RECHECK_MS)
                return
            }
            lastRoundStart = clock()
            val ok = refresh(fullRound = wantFull)
            if (ok) {
                failures = 0
                scheduleNext(RefreshPolicy.nextDelayMs(lastBuilt(), clock(), false))
            } else {
                failures++
                val delay = RetryBackoff.delayMs(failures)
                Log.w(NativeAdblock.TAG, "list update incomplete (failure #$failures): $lastError")
                scheduleNext(delay)
            }
        } catch (t: Throwable) {
            Log.e(NativeAdblock.TAG, "list refresh crashed", t)
            lastError = t.message ?: t.javaClass.simpleName
            failures++
            scheduleNext(RetryBackoff.delayMs(failures))
        } finally {
            if (state == ListsState.LOADING) state = if (hasEngine) ListsState.READY else ListsState.ERROR
            publishStatus()
        }
    }

    private fun loadCache() {
        val f = cacheFile
        if (!f.exists()) return
        state = ListsState.LOADING
        publishStatus()
        val t0 = clock()
        val engine = NativeEngine.load(f.absolutePath)
        if (engine == null) {
            Log.w(NativeAdblock.TAG, "cache unreadable, will rebuild")
            f.delete()
            return
        }
        publish(engine)
        Log.i(NativeAdblock.TAG, "engine loaded from cache in ${clock() - t0} ms")
    }

    /** Downloads lists and rebuilds the engine. Returns true when every list is present and the engine is fresh. */
    private fun refresh(fullRound: Boolean): Boolean {
        state = ListsState.LOADING
        lastError = null
        publishStatus()
        val toFetch = if (fullRound) lists else lists.filter { it.id in pending }
        Log.i(NativeAdblock.TAG, "refreshing ${toFetch.size}/${lists.size} lists (full=$fullRound, failures so far=$failures)")
        listsDir.mkdirs()
        val t0 = clock()
        val pool = Executors.newFixedThreadPool(6)
        val results = try {
            val futures = pool.invokeAll(
                toFetch.map { list -> Callable { download(list, File(listsDir, "${list.id}.txt")) } },
                ROUND_TIMEOUT_S, TimeUnit.SECONDS,
            )
            futures.map { f -> runCatching { f.get() }.getOrDefault(false) }
        } finally {
            pool.shutdownNow()
        }
        val failedIds = toFetch.filterIndexed { i, _ -> !results[i] }.map { it.id }
        val downloaded = results.count { it }
        pending.clear()
        pending.addAll(failedIds)
        val files = lists.map { File(listsDir, "${it.id}.txt") }.filter { it.isFile && it.length() > 0 }
        Log.i(NativeAdblock.TAG, "lists: downloaded $downloaded/${toFetch.size} in ${clock() - t0} ms, failed=$failedIds, on disk ${files.size}")
        if (downloaded == 0 && toFetch.isNotEmpty() && (hasEngine || files.isEmpty())) {
            lastError = if (files.isEmpty()) "no lists available (network?)" else "offline"
            state = if (hasEngine) ListsState.READY else ListsState.ERROR
            return false
        }
        if (files.isEmpty()) {
            lastError = "no lists available"
            state = if (hasEngine) ListsState.READY else ListsState.ERROR
            return false
        }
        if (downloaded > 0 || !hasEngine) {
            val b0 = clock()
            val engine = NativeEngine.fromFiles(files.map { it.absolutePath })
            if (engine == null) {
                lastError = "engine build failed"
                return false
            }
            Log.i(NativeAdblock.TAG, "engine built from ${files.size} lists in ${clock() - b0} ms")
            if (pending.isEmpty() && files.size == lists.size) {
                if (!engine.save(cacheFile.absolutePath)) Log.w(NativeAdblock.TAG, "cache save failed")
            }
            publish(engine)
        }
        val complete = pending.isEmpty() && files.size == lists.size
        if (!complete) lastError = "missing lists: ${pending.joinToString()}"
        return complete
    }

    private fun publish(engine: NativeEngine) {
        onEngine(engine)
        hasEngine = true
        state = ListsState.READY
    }

    private fun publishStatus() {
        val onDisk = lists.count { File(listsDir, "${it.id}.txt").let { f -> f.isFile && f.length() > 0 } }
        _status.value = ListsStatus(state, lastBuilt(), onDisk, lists.size, lastError, failures)
    }

    private fun download(list: FilterList, target: File): Boolean {
        repeat(MAX_ATTEMPTS) { attempt ->
            val t0 = clock()
            try {
                val conn = gate.open(list.url).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", "MonoBrowserMobile-adblock")
                }
                try {
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // Reject empty bodies and HTML error pages.
                    val head = tmp.inputStream().use { s -> ByteArray(16).let { b -> String(b, 0, s.read(b).coerceAtLeast(0)) } }
                    if (tmp.length() == 0L || head.trimStart().startsWith("<")) {
                        tmp.delete()
                        throw IOException("bad body")
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    Log.d(NativeAdblock.TAG, "list ${list.id} ok: ${target.length()} bytes in ${clock() - t0} ms")
                    return true
                } finally {
                    conn.disconnect()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (e: Exception) {
                Log.w(NativeAdblock.TAG, "list ${list.id} failed (attempt ${attempt + 1}/$MAX_ATTEMPTS, ${clock() - t0} ms): $e")
                if (Thread.currentThread().isInterrupted) return false
                if (attempt < MAX_ATTEMPTS - 1) {
                    try { Thread.sleep(1_000L * (attempt + 1)) } catch (_: InterruptedException) { return false }
                }
            }
        }
        return false
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        /** A whole round never blocks longer than this (the old code could hang ~100 s without any log line). */
        const val ROUND_TIMEOUT_S = 120L
    }
}
