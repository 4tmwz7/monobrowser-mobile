package com.voluene.monobrowser.update

enum class UpdatePhase {
    IDLE, CHECKING, UP_TO_DATE, AVAILABLE, NEEDS_PERMISSION, DOWNLOADING, VERIFYING, INSTALLING, FAILED;

    /** Phases in which the update has an entry in the Downloads screen. */
    val isBusy: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == INSTALLING
}

enum class UpdateError {
    /** Cannot reach the server / timed out. */
    NETWORK,
    /** The server answered, but not with a usable release. */
    BAD_RESPONSE,
    /** The release is missing the APK or its `.sha256`. */
    CHECKSUM_UNAVAILABLE,
    UNTRUSTED_URL,
    CHECKSUM_MISMATCH,
    /** Wrong package, not newer, or signed with a different key than the installed app. */
    PACKAGE_REJECTED,
    SIGNATURE_MISMATCH,
    STORAGE,
    INSTALL_CANCELLED,
    INSTALL_FAILED,
}

data class UpdateStatus(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    /** Newest known release (null until one was found). */
    val release: ReleaseInfo? = null,
    val lastCheckMs: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: UpdateError? = null,
    /** Raw system message of a failed install, for diagnostics. */
    val errorDetail: String? = null,
    /** Last automatic or manual check ended with a network/parse error. */
    val lastCheckFailed: Boolean = false,
) {
    /** 0..100, or -1 when unknown. */
    val percent: Int get() = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else -1
}

/** Maps `PackageInstaller.STATUS_*` codes (plain ints, so this stays testable on the JVM). */
object InstallStatus {
    const val SUCCESS = 0
    const val PENDING_USER_ACTION = -1
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7

    fun toError(status: Int): UpdateError? = when (status) {
        SUCCESS, PENDING_USER_ACTION -> null
        FAILURE_ABORTED -> UpdateError.INSTALL_CANCELLED
        FAILURE_INCOMPATIBLE, FAILURE_CONFLICT -> UpdateError.SIGNATURE_MISMATCH
        FAILURE_STORAGE -> UpdateError.STORAGE
        else -> UpdateError.INSTALL_FAILED
    }
}
