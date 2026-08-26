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
    fun acceptsOnlyBoundForwardUpgrade() {
        val manifest = manifest()
        val downloaded = DownloadedApkIdentity(
            applicationId = DevOtaTrust.APPLICATION_ID,
            versionCode = 11L,
            versionName = "0.1.0-dev.11",
            minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
            sha256 = digest,
            signingCertSha256 = DevOtaTrust.CERT_SHA256,
        )
        assertEquals(UpdateVerification.Accepted, DevelopmentUpdateVerifier.verify(manifest, installed, downloaded))
    }

    @Test
    fun rejectsPathLikeAssetName() {
        val downloaded = DownloadedApkIdentity(
            applicationId = DevOtaTrust.APPLICATION_ID,
            versionCode = 11L,
            versionName = "0.1.0-dev.11",
            minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
            sha256 = digest,
            signingCertSha256 = DevOtaTrust.CERT_SHA256,
        )
        val result = DevelopmentUpdateVerifier.verify(
            manifest().copy(apkAssetName = "../CamX-dev.apk"),
            installed,
            downloaded,
        )
        assertTrue(result is UpdateVerification.Rejected)
        assertEquals(UpdateFailureCode.INVALID_ASSET_NAME, (result as UpdateVerification.Rejected).code)
    }

    @Test
    fun rejectsSignerMismatch() {
        val downloaded = DownloadedApkIdentity(
            applicationId = DevOtaTrust.APPLICATION_ID,
            versionCode = 11L,
            versionName = "0.1.0-dev.11",
            minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
            sha256 = digest,
            signingCertSha256 = "b".repeat(64),
        )
        val result = DevelopmentUpdateVerifier.verify(manifest(), installed, downloaded)
        assertEquals(
            UpdateFailureCode.SIGNATURE_MISMATCH,
            (result as UpdateVerification.Rejected).code,
        )
    }

    @Test
    fun rejectsDevelopmentArtifactThatRaisesApplicationBaseline() {
        val downloaded = DownloadedApkIdentity(
            applicationId = DevOtaTrust.APPLICATION_ID,
            versionCode = 11L,
            versionName = "0.1.0-dev.11",
            minSdk = 24,
            sha256 = digest,
            signingCertSha256 = DevOtaTrust.CERT_SHA256,
        )
        val result = DevelopmentUpdateVerifier.verify(
            manifest().copy(minSdk = 24),
            installed,
            downloaded,
        )

        assertEquals(
            UpdateFailureCode.MIN_SDK_UNSUPPORTED,
            (result as UpdateVerification.Rejected).code,
        )
    }

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
        buildTimestamp = "2026-08-24T00:00:00Z",
        changelog = "foundation",
        mandatory = false,
    )
}
