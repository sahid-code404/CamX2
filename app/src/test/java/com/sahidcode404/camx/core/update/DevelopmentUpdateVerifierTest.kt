package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import com.sahidcode404.camx.core.update.verification.DevelopmentUpdateVerifier
import com.sahidcode404.camx.core.update.verification.DownloadedApkIdentity
import com.sahidcode404.camx.core.update.verification.InstalledAppIdentity
import com.sahidcode404.camx.core.update.verification.UpdateVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentUpdateVerifierTest {
    private val digest = "a".repeat(64)
    private val installed = InstalledAppIdentity(
        applicationId = DevOtaTrust.APPLICATION_ID,
        versionCode = 10L,
        signingCertSha256 = DevOtaTrust.CERT_SHA256,
        sdkInt = 36,
    )

    @Test
    fun validDownloadedApkIsAccepted() {
        assertEquals(
            UpdateVerification.Accepted,
            DevelopmentUpdateVerifier.verify(manifest(), installed, downloaded()),
        )
    }

    @Test
    fun packageMismatchIsRejected() {
        assertRejected(
            UpdateFailureCode.PACKAGE_MISMATCH,
            downloaded().copy(applicationId = "other.app"),
        )
    }

    @Test
    fun versionCodeMismatchIsRejected() {
        assertRejected(
            UpdateFailureCode.APK_VERSION_MISMATCH,
            downloaded().copy(versionCode = 12L),
        )
    }

    @Test
    fun versionNameMismatchIsRejected() {
        assertRejected(
            UpdateFailureCode.APK_VERSION_MISMATCH,
            downloaded().copy(versionName = "wrong"),
        )
    }

    @Test
    fun minSdkMismatchIsRejected() {
        val result = DevelopmentUpdateVerifier.verify(
            manifest().copy(minSdk = 24),
            installed,
            downloaded().copy(minSdk = 24),
        )
        assertEquals(
            UpdateFailureCode.MIN_SDK_UNSUPPORTED,
            (result as UpdateVerification.Rejected).code,
        )
    }

    @Test
    fun shaMismatchIsRejected() {
        assertRejected(
            UpdateFailureCode.SHA256_MISMATCH,
            downloaded().copy(sha256 = "b".repeat(64)),
        )
    }

    @Test
    fun signerMismatchIsRejected() {
        assertRejected(
            UpdateFailureCode.SIGNATURE_MISMATCH,
            downloaded().copy(signingCertSha256 = "b".repeat(64)),
        )
    }

    @Test
    fun wrongOrPathLikeAssetNameIsRejected() {
        for (name in listOf("Other.apk", "../CamX-dev.apk", "nested/CamX-dev.apk")) {
            val result = DevelopmentUpdateVerifier.verify(
                manifest().copy(apkAssetName = name),
                installed,
                downloaded(),
            )
            assertEquals(
                UpdateFailureCode.INVALID_ASSET_NAME,
                (result as UpdateVerification.Rejected).code,
            )
        }
    }

    @Test
    fun finalVerifierStillRejectsNonUpgradeIfCalledOutsideCheckPipeline() {
        val result = DevelopmentUpdateVerifier.verify(
            manifest().copy(versionCode = installed.versionCode),
            installed,
            downloaded().copy(versionCode = installed.versionCode),
        )
        assertTrue(result is UpdateVerification.Rejected)
        assertEquals(
            UpdateFailureCode.NOT_AN_UPGRADE,
            (result as UpdateVerification.Rejected).code,
        )
    }

    private fun assertRejected(
        code: UpdateFailureCode,
        candidate: DownloadedApkIdentity,
    ) {
        val result = DevelopmentUpdateVerifier.verify(manifest(), installed, candidate)
        assertEquals(code, (result as UpdateVerification.Rejected).code)
    }

    private fun downloaded() = DownloadedApkIdentity(
        applicationId = DevOtaTrust.APPLICATION_ID,
        versionCode = 11L,
        versionName = "0.1.0-dev.11",
        minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
        sha256 = digest,
        signingCertSha256 = DevOtaTrust.CERT_SHA256,
    )

    private fun manifest() = DevOtaManifest(
        schema = DevOtaTrust.SCHEMA,
        channel = DevOtaTrust.CHANNEL,
        applicationId = DevOtaTrust.APPLICATION_ID,
        versionCode = 11L,
        versionName = "0.1.0-dev.11",
        minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
        apkAssetName = DevOtaTrust.APK_ASSET_NAME,
        sha256 = digest,
        signingCertSha256 = DevOtaTrust.CERT_SHA256,
        gitSha = "a".repeat(40),
        buildTimestamp = "2026-08-25T00:00:00Z",
        changelog = "CAMX-111",
        mandatory = false,
    )
}
