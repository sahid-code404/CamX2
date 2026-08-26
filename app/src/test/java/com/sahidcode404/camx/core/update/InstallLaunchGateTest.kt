package com.sahidcode404.camx.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallLaunchGateTest {
    @Test
    fun duplicateInstallerLaunchIsRejectedUntilResumeOrPermissionFlowResetsGate() {
        val gate = InstallLaunchGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.reset()
        assertTrue(gate.tryStart())
    }

    @Test
    fun unknownSourcePermissionReturnOnlyUnlocksExplicitInstallRetry() {
        val permission = InstallPermissionReturnGate()
        permission.markAwaiting()

        assertEquals(
            InstallPermissionResumeResult.STILL_WAITING,
            permission.onHostResume(canRequestPackageInstalls = false),
        )
        assertEquals(
            InstallPermissionResumeResult.PERMISSION_GRANTED,
            permission.onHostResume(canRequestPackageInstalls = true),
        )
        assertEquals(
            InstallPermissionResumeResult.NOT_WAITING,
            permission.onHostResume(canRequestPackageInstalls = true),
        )
    }
}
