package com.voluene.monobrowser.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.voluene.monobrowser.BuildConfig
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.util.StateFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class PersistedUpdate(val lastCheckMs: Long = 0L, val release: ReleaseInfo? = null)

/**
 * Self-updater (release builds). Mirrors the desktop app: checks GitHub Releases on start and every 6 h while running,
 * never downloads on its own, and installs through the `PackageInstaller` session API after verifying the SHA-256.
 * One instance per process, owned by [com.voluene.monobrowser.MonoApp] so the [InstallReceiver] can reach it.
 */
class UpdateManager(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentVersion: String = BuildConfig.VERSION_NAME
    private val updatesDir = File(this.context.cacheDir, "updates")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Update checks run only in release builds (or debug builds started with `-Pupdate.enabled=true`). */
    val enabled: Boolean = BuildConfig.UPDATE_CHECKS

    private val persisted = StateFile(
        File(this.context.filesDir, "update-state.json"), PersistedUpdate(),
        { runCatching { json.decodeFromString(PersistedUpdate.serializer(), it) }.getOrNull() },
        { json.encodeToString(PersistedUpdate.serializer(), it) }, scope,
    )

    private val _status = MutableStateFlow(UpdateStatus(lastCheckMs = persisted.value.lastCheckMs))
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Version whose banner the user dismissed with "Later" (once per version per app run). */
    private val _dismissedVersion = MutableStateFlow<String?>(null)
    val dismissedVersion: StateFlow<String?> = _dismissedVersion.asStateFlow()

    private var started = false
    private var job: Job? = null
    private var sessionId = -1
    /** Downloaded + verified APK that survives a cancelled system prompt. */
    private var readyFile: File? = null
    @Volatile private var cancelled = false

    val currentVersionName: String get() = currentVersion

    // ---- lifecycle ----

    /** Starts the schedule (idempotent). Call after the first frame. */
    fun start(settings: StateFlow<AppSettings>) {
        if (started || !enabled) return
        started = true
        scope.launch { runCatching { updatesDir.deleteRecursively() } } // leftovers of a previous run / finished install
        val known = persisted.value.release?.takeIf { Versions.isNewer(it.version, currentVersion) }
        if (known != null) _status.update { it.copy(phase = UpdatePhase.AVAILABLE, release = known) }
        else if (persisted.value.lastCheckMs > 0) _status.update { it.copy(phase = UpdatePhase.UP_TO_DATE) } // e.g. just updated
        scope.launch {
            delay(UpdateSchedule.initialDelayMs(_status.value.lastCheckMs, System.currentTimeMillis()))
            while (isActive) {
                if (settings.value.autoUpdateCheck &&
                    UpdateSchedule.isAutoCheckDue(_status.value.lastCheckMs, System.currentTimeMillis())
                ) check(manual = false)
                delay(UpdateSchedule.PERIOD_MS)
            }
        }
    }

    /** The user may have granted "install unknown apps" in the system settings: continue where we stopped. */
    fun onAppResumed() {
        if (_status.value.phase == UpdatePhase.NEEDS_PERMISSION && canInstall()) startUpdate()
    }

    // ---- check ----

    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val s = _status.value
        if (s.phase == UpdatePhase.CHECKING || s.phase.isBusy || s.phase == UpdatePhase.NEEDS_PERMISSION) return
        if (manual && !UpdateSchedule.isManualCheckAllowed(s.lastCheckMs, now)) return
        _status.update { it.copy(phase = UpdatePhase.CHECKING) }
        scope.launch {
            val checkedAt = System.currentTimeMillis()
            try {
                val text = httpText("${BuildConfig.UPDATE_BASE_URL}/releases/latest", 1_000_000)
                val release = text?.let { ReleaseParser.parse(it, "${BuildConfig.RELEASES_PAGE_URL}") }
                when {
                    text == null -> finishCheck(checkedAt, null, failed = false) // 404: no release yet
                    release == null -> finishCheck(checkedAt, null, failed = true)
                    Versions.isNewer(release.version, currentVersion) -> finishCheck(checkedAt, release, failed = false)
                    else -> finishCheck(checkedAt, null, failed = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                finishCheck(checkedAt, null, failed = true)
            }
        }
    }

    private fun finishCheck(checkedAt: Long, release: ReleaseInfo?, failed: Boolean) {
        if (!failed) persisted.update { PersistedUpdate(checkedAt, release) }
        _status.update { old ->
            val newRelease = if (failed) old.release else release
            old.copy(
                phase = when {
                    newRelease != null -> UpdatePhase.AVAILABLE
                    failed -> UpdatePhase.IDLE
                    else -> UpdatePhase.UP_TO_DATE
                },
                release = newRelease,
                lastCheckMs = if (failed) old.lastCheckMs else checkedAt,
                lastCheckFailed = failed,
                error = null, errorDetail = null,
            )
        }
    }

    // ---- download + install ----

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** System screen where the user allows this app to install packages. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dismissBanner() {
        val s = _status.value
        _dismissedVersion.value = s.release?.version
        if (s.phase == UpdatePhase.FAILED || s.phase == UpdatePhase.NEEDS_PERMISSION) {
            _status.update { it.copy(phase = UpdatePhase.AVAILABLE, error = null, errorDetail = null) }
        }
    }

    /** "Update": permission check, download, SHA-256 + package check, install session. */
    fun startUpdate() {
        val s = _status.value
        val release = s.release ?: return
        if (!enabled || s.phase.isBusy || s.phase == UpdatePhase.CHECKING) return
        if (!canInstall()) {
            _status.update { it.copy(phase = UpdatePhase.NEEDS_PERMISSION, error = null, errorDetail = null) }
            return
        }
        cancelled = false
        _status.update {
            it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, totalBytes = release.apkSize, error = null, errorDetail = null)
        }
        job = scope.launch {
            try {
                val ready = readyFile?.takeIf { it.isFile && it.name == release.apkName }
                val file = ready ?: download(release)
                if (file == null) return@launch // cancelled
                readyFile = file
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                fail(e.error, e.detail)
            } catch (e: IOException) {
                fail(if (updatesDir.usableSpace in 0 until maxOf(release.apkSize, 1L) * 2) UpdateError.STORAGE else UpdateError.NETWORK, e.message)
            } catch (e: Exception) {
                fail(UpdateError.INSTALL_FAILED, e.message)
            }
        }
    }

    /** Aborts a download or a pending install; the release stays available. */
    fun cancel() {
        cancelled = true
        job?.cancel()
        job = null
        abandonSession()
        runCatching { updatesDir.deleteRecursively() }
        readyFile = null
        _status.update { if (it.release != null) it.copy(phase = UpdatePhase.AVAILABLE, error = null, errorDetail = null) else it }
    }

    private fun fail(error: UpdateError, detail: String? = null) {
        _status.update { it.copy(phase = UpdatePhase.FAILED, error = error, errorDetail = detail) }
        if (error != UpdateError.INSTALL_CANCELLED) {
            runCatching { updatesDir.deleteRecursively() }
            readyFile = null
        }
    }

    private fun download(release: ReleaseInfo): File? {
        val testBase = BuildConfig.UPDATE_BASE_URL.takeIf { !it.startsWith("https://api.github.com/") }
        if (!TrustedUrls.isTrusted(release.apkUrl, testBase) || !TrustedUrls.isTrusted(release.shaUrl, testBase)) throw UpdateException(UpdateError.UNTRUSTED_URL)
        val shaText = httpText(release.shaUrl, 4_096) ?: throw UpdateException(UpdateError.CHECKSUM_UNAVAILABLE)
        val expected = Checksum.parse(shaText, release.apkName) ?: throw UpdateException(UpdateError.CHECKSUM_UNAVAILABLE)

        updatesDir.deleteRecursively()
        updatesDir.mkdirs()
        val part = File(updatesDir, "${release.apkName}.part")
        val connection = open(release.apkUrl, readTimeoutMs = 20_000)
        try {
            if (connection.responseCode !in 200..299) throw UpdateException(UpdateError.NETWORK, "HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            _status.update { it.copy(totalBytes = total) }
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) return null
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw UpdateException(UpdateError.BAD_RESPONSE, "too large")
                        val now = System.nanoTime()
                        if (now - lastReport > 150_000_000L) {
                            lastReport = now
                            _status.update { it.copy(downloadedBytes = done) }
                        }
                    }
                }
            }
            _status.update { it.copy(downloadedBytes = done, phase = UpdatePhase.VERIFYING) }
        } finally {
            connection.disconnect()
        }
        if (cancelled) return null
        if (!Checksum.matches(expected, Checksum.sha256Hex(part))) throw UpdateException(UpdateError.CHECKSUM_MISMATCH)
        val file = File(updatesDir, release.apkName)
        if (!part.renameTo(file)) throw IOException("rename failed")
        return file
    }

    /** Package, version and signing certificate of the file must fit the installed app. */
    private fun verifyArchive(file: File) {
        val pm = context.packageManager
        val ours = context.packageName
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(ours, flags)
        val signedByUs: Boolean? = if (Build.VERSION.SDK_INT >= 28) {
            val theirs = archive?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val mine = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            if (theirs.isNullOrEmpty() || mine.isNullOrEmpty()) null else theirs.any { it in mine }
        } else null
        val error = ApkCheck.verdict(ours, archive?.packageName, versionCodeOf(installed), archive?.let(::versionCodeOf) ?: -1L, signedByUs)
        if (error != null) throw UpdateException(error)
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun install(file: File) {
        if (!canInstall()) {
            _status.update { it.copy(phase = UpdatePhase.NEEDS_PERMISSION) }
            return
        }
        verifyArchive(file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        sessionId = id
        _status.update { it.copy(phase = UpdatePhase.INSTALLING) }
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION).setPackage(context.packageName)
                val pending = android.app.PendingIntent.getBroadcast(
                    context, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            abandonSession()
            throw e
        }
    }

    private fun abandonSession() {
        val id = sessionId
        sessionId = -1
        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    /** Result of the install session (from [InstallReceiver], main thread). */
    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            InstallStatus.PENDING_USER_ACTION -> {
                if (confirm != null) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { fail(UpdateError.INSTALL_FAILED, it.message) }
                }
            }
            InstallStatus.SUCCESS -> {
                // The system replaces the app and kills this process; clean up in case it survives.
                sessionId = -1
                runCatching { updatesDir.deleteRecursively() }
                readyFile = null
                _status.update { it.copy(phase = UpdatePhase.IDLE, release = null) }
            }
            else -> {
                sessionId = -1
                fail(InstallStatus.toError(status) ?: UpdateError.INSTALL_FAILED, message)
            }
        }
    }

    // ---- http ----

    private class UpdateException(val error: UpdateError, val detail: String? = null) : Exception(error.name)

    private fun open(url: String, readTimeoutMs: Int = 10_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MonoBrowser/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain")
        }

    /** Body as text; null on 404; throws on other errors or an oversized body. */
    private fun httpText(url: String, maxBytes: Int): String? {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw UpdateException(UpdateError.NETWORK, "HTTP $code")
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw UpdateException(UpdateError.BAD_RESPONSE, "too large")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
