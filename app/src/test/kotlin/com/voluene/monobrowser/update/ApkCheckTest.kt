package com.voluene.monobrowser.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkCheckTest {
    private val pkg = "com.voluene.monobrowser"

    @Test fun acceptsNewerSignedBySameKey() = assertNull(ApkCheck.verdict(pkg, pkg, 100, 101, true))

    @Test fun acceptsWhenSignaturesCannotBeRead() = assertNull(ApkCheck.verdict(pkg, pkg, 100, 101, null))

    @Test fun rejectsOtherPackage() = assertEquals(UpdateError.PACKAGE_REJECTED, ApkCheck.verdict(pkg, "evil.app", 100, 101, true))

    @Test fun rejectsMissingPackage() = assertEquals(UpdateError.PACKAGE_REJECTED, ApkCheck.verdict(pkg, null, 100, 101, true))

    @Test fun rejectsSameOrOlderVersion() {
        assertEquals(UpdateError.PACKAGE_REJECTED, ApkCheck.verdict(pkg, pkg, 100, 100, true))
        assertEquals(UpdateError.PACKAGE_REJECTED, ApkCheck.verdict(pkg, pkg, 100, 99, true))
    }

    @Test fun rejectsDifferentSigningKey() = assertEquals(UpdateError.SIGNATURE_MISMATCH, ApkCheck.verdict(pkg, pkg, 100, 101, false))

    @Test fun installStatusConstantsMatchPackageInstaller() {
        assertEquals(PackageInstaller.STATUS_SUCCESS, InstallStatus.SUCCESS)
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, InstallStatus.PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, InstallStatus.FAILURE_ABORTED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, InstallStatus.FAILURE_INCOMPATIBLE)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, InstallStatus.FAILURE_CONFLICT)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, InstallStatus.FAILURE_STORAGE)
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, InstallStatus.FAILURE_BLOCKED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, InstallStatus.FAILURE_INVALID)
        assertEquals(PackageInstaller.STATUS_FAILURE, InstallStatus.FAILURE)
    }

    @Test fun installStatusMapsToErrors() {
        assertNull(InstallStatus.toError(InstallStatus.SUCCESS))
        assertNull(InstallStatus.toError(InstallStatus.PENDING_USER_ACTION))
        assertEquals(UpdateError.INSTALL_CANCELLED, InstallStatus.toError(InstallStatus.FAILURE_ABORTED))
        assertEquals(UpdateError.SIGNATURE_MISMATCH, InstallStatus.toError(InstallStatus.FAILURE_INCOMPATIBLE))
        assertEquals(UpdateError.STORAGE, InstallStatus.toError(InstallStatus.FAILURE_STORAGE))
        assertEquals(UpdateError.INSTALL_FAILED, InstallStatus.toError(InstallStatus.FAILURE_BLOCKED))
        assertEquals(UpdateError.INSTALL_FAILED, InstallStatus.toError(42))
    }
}
