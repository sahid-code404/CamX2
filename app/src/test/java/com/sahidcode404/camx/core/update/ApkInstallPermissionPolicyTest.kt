package com.sahidcode404.camx.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallPermissionPolicyTest {
    @Test
    fun api25NeverNeedsInstallUnknownAppsPermission() {
        assertFalse(
            ApkInstallPermissionPolicy.permissionRequired(
                sdkInt = 25,
                canRequestPackageInstalls = false,
            ),
        )
    }

    @Test
    fun api26PlusRequiresOneTimePermissionWhenMissing() {
        assertTrue(
            ApkInstallPermissionPolicy.permissionRequired(
                sdkInt = 26,
                canRequestPackageInstalls = false,
            ),
        )
        assertFalse(
            ApkInstallPermissionPolicy.permissionRequired(
                sdkInt = 35,
                canRequestPackageInstalls = true,
            ),
        )
    }
}
