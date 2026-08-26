package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import com.sahidcode404.camx.core.update.verification.InstalledAppIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentManifestValidatorTest {
    private val installed = InstalledAppIdentity(
        applicationId = DevOtaTrust.APPLICATION_ID,
        versionCode = 100L,
        signingCertSha256 = DevOtaTrust.CERT_SHA256,
        sdkInt = 35,
    )

    @Test
    fun validNewerManifestIsAvailable() {
        val result = DevelopmentManifestValidator.validate(manifest(versionCode = 101L), installed)
        assertTrue(result is DevelopmentManifestCheck.Available)
    }

    @Test
    fun sameVersionIsNormalUpToDate() {
        val result = DevelopmentManifestValidator.validate(manifest(versionCode = 100L), installed)
        assertTrue(result is DevelopmentManifestCheck.UpToDate)
    }

    @Test
    fun olderVersionIsNormalUpToDate() {
        val result = DevelopmentManifestValidator.validate(manifest(versionCode = 99L), installed)
        assertTrue(result is DevelopmentManifestCheck.UpToDate)
    }

    @Test
    fun wrongSchemaFails() {
        assertRejected(UpdateFailureCode.INVALID_SCHEMA, manifest().copy(schema = 2))
    }

    @Test
    fun wrongChannelFails() {
        assertRejected(UpdateFailureCode.CHANNEL_MISMATCH, manifest().copy(channel = "stable"))
    }

    @Test
    fun wrongPackageFails() {
        assertRejected(UpdateFailureCode.PACKAGE_MISMATCH, manifest().copy(applicationId = "other.app"))
    }

    @Test
    fun wrongSignerFails() {
        assertRejected(UpdateFailureCode.SIGNATURE_MISMATCH, manifest().copy(signingCertSha256 = "b".repeat(64)))
    }

    @Test
    fun wrongApkAssetNameFails() {
        assertRejected(UpdateFailureCode.INVALID_ASSET_NAME, manifest().copy(apkAssetName = "Other.apk"))
        assertRejected(UpdateFailureCode.INVALID_ASSET_NAME, manifest().copy(apkAssetName = "../CamX-dev.apk"))
    }

    @Test
    fun unsupportedMinSdkFails() {
        assertRejected(UpdateFailureCode.MIN_SDK_UNSUPPORTED, manifest().copy(minSdk = 24))
        val api22 = installed.copy(sdkInt = 22)
        val result = DevelopmentManifestValidator.validate(manifest(), api22)
        assertEquals(
            UpdateFailureCode.MIN_SDK_UNSUPPORTED,
            (result as DevelopmentManifestCheck.Rejected).code,
        )
    }

    @Test
    fun malformedShaFailsBeforeDownload() {
        assertRejected(UpdateFailureCode.INVALID_DIGEST, manifest().copy(sha256 = "not-a-sha"))
    }

    @Test
    fun invalidVersionFieldsFail() {
        assertRejected(UpdateFailureCode.INVALID_VERSION, manifest().copy(versionCode = 0L))
        assertRejected(UpdateFailureCode.INVALID_VERSION, manifest().copy(versionName = ""))
        assertRejected(UpdateFailureCode.INVALID_VERSION, manifest().copy(changelog = "x".repeat(4_097)))
    }

    private fun assertRejected(code: UpdateFailureCode, candidate: DevOtaManifest) {
        val result = DevelopmentManifestValidator.validate(candidate, installed)
        assertEquals(code, (result as DevelopmentManifestCheck.Rejected).code)
    }

    private fun manifest(versionCode: Long = 101L) = DevOtaManifest(
        schema = DevOtaTrust.SCHEMA,
        channel = DevOtaTrust.CHANNEL,
        applicationId = DevOtaTrust.APPLICATION_ID,
        versionCode = versionCode,
        versionName = "0.1.0-dev.$versionCode",
        minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
        apkAssetName = DevOtaTrust.APK_ASSET_NAME,
        sha256 = "a".repeat(64),
        signingCertSha256 = DevOtaTrust.CERT_SHA256,
        gitSha = "a".repeat(40),
        buildTimestamp = "2026-08-25T00:00:00Z",
        changelog = "CAMX-111",
        mandatory = false,
    )
}
