package com.voluene.monobrowser.update

/** Decides whether a downloaded APK may be offered to the installer (pure; the Android lookups happen in [UpdateManager]). */
object ApkCheck {
    /**
     * @param archiveSignedByUs null when the signing certificates could not be read (API < 28): the system installer
     * still enforces the signature, so we do not block in that case.
     */
    fun verdict(
        expectedPackage: String, archivePackage: String?,
        installedVersionCode: Long, archiveVersionCode: Long,
        archiveSignedByUs: Boolean?,
    ): UpdateError? = when {
        archivePackage != expectedPackage -> UpdateError.PACKAGE_REJECTED
        archiveVersionCode <= installedVersionCode -> UpdateError.PACKAGE_REJECTED
        archiveSignedByUs == false -> UpdateError.SIGNATURE_MISMATCH
        else -> null
    }
}
