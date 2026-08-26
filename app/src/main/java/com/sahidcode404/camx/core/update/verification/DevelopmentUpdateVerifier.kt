package com.sahidcode404.camx.core.update.verification

import com.sahidcode404.camx.core.update.DevOtaManifest
import com.sahidcode404.camx.core.update.UpdateFailureCode

data class InstalledAppIdentity(
    val applicationId: String,
    val versionCode: Long,
    val signingCertSha256: String,
    val sdkInt: Int,
)

data class DownloadedApkIdentity(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sha256: String,
    val signingCertSha256: String,
)

sealed interface UpdateVerification {
    data object Accepted : UpdateVerification
    data class Rejected(val code: UpdateFailureCode) : UpdateVerification
}

object DevOtaTrust {
    const val SCHEMA = 1
    const val CHANNEL = "development"
    const val APPLICATION_ID = "com.sahidcode404.camx"
    const val APK_ASSET_NAME = "CamX-dev.apk"
    const val APPLICATION_MIN_SDK = 23
    const val VERIFIED_UPDATE_RELATIVE_DIRECTORY = "updates/verified"
    const val CERT_SHA256 = "f6b8a3f492d4fb9d2dbf58937d3995f8f1e0f79433c4f3e25b9930218e694d8c"
    const val MAX_APK_BYTES = 256L * 1024L * 1024L
}

object DevelopmentUpdateVerifier {
    fun verify(
        manifest: DevOtaManifest,
        installed: InstalledAppIdentity,
        downloaded: DownloadedApkIdentity,
    ): UpdateVerification {
        fun reject(code: UpdateFailureCode) = UpdateVerification.Rejected(code)
        if (manifest.schema != DevOtaTrust.SCHEMA) return reject(UpdateFailureCode.INVALID_SCHEMA)
        if (!manifestFieldsAreBounded(manifest)) return reject(UpdateFailureCode.INVALID_SCHEMA)
        if (manifest.channel != DevOtaTrust.CHANNEL) return reject(UpdateFailureCode.CHANNEL_MISMATCH)
        if (manifest.applicationId != DevOtaTrust.APPLICATION_ID ||
            installed.applicationId != DevOtaTrust.APPLICATION_ID ||
            downloaded.applicationId != DevOtaTrust.APPLICATION_ID
        ) return reject(UpdateFailureCode.PACKAGE_MISMATCH)
        if (manifest.versionCode <= installed.versionCode) return reject(UpdateFailureCode.NOT_AN_UPGRADE)
        if (manifest.minSdk != DevOtaTrust.APPLICATION_MIN_SDK) {
            return reject(UpdateFailureCode.MIN_SDK_UNSUPPORTED)
        }
        if (manifest.minSdk > installed.sdkInt || downloaded.minSdk > installed.sdkInt) {
            return reject(UpdateFailureCode.MIN_SDK_UNSUPPORTED)
        }
        if (!isSafeAssetName(manifest.apkAssetName)) return reject(UpdateFailureCode.INVALID_ASSET_NAME)
        if (manifest.apkAssetName != DevOtaTrust.APK_ASSET_NAME) {
            return reject(UpdateFailureCode.INVALID_ASSET_NAME)
        }
        if (downloaded.versionCode != manifest.versionCode ||
            downloaded.versionName != manifest.versionName ||
            downloaded.minSdk != manifest.minSdk
        ) {
            return reject(UpdateFailureCode.APK_VERSION_MISMATCH)
        }
        val expectedDigest = manifest.sha256.normalizedDigestOrNull()
            ?: return reject(UpdateFailureCode.SHA256_MISMATCH)
        if (downloaded.sha256.normalizedDigestOrNull() != expectedDigest) {
            return reject(UpdateFailureCode.SHA256_MISMATCH)
        }
        val pinnedSigner = DevOtaTrust.CERT_SHA256
        val manifestSigner = manifest.signingCertSha256.normalizedDigestOrNull()
        val installedSigner = installed.signingCertSha256.normalizedDigestOrNull()
        val downloadedSigner = downloaded.signingCertSha256.normalizedDigestOrNull()
        if (manifestSigner != pinnedSigner || installedSigner != pinnedSigner || downloadedSigner != pinnedSigner) {
            return reject(UpdateFailureCode.SIGNATURE_MISMATCH)
        }
        return UpdateVerification.Accepted
    }

    private fun isSafeAssetName(name: String): Boolean =
        name.isNotBlank() && name.length <= 96 && '/' !in name && '\\' !in name && name.endsWith(".apk")

    private fun manifestFieldsAreBounded(manifest: DevOtaManifest): Boolean =
        manifest.versionCode > 0L &&
            manifest.versionName.isNotBlank() && manifest.versionName.length <= 128 &&
            manifest.minSdk > 0 &&
            manifest.gitSha.length in 7..64 &&
            manifest.gitSha.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } &&
            manifest.buildTimestamp.isNotBlank() && manifest.buildTimestamp.length <= 64 &&
            manifest.changelog.length <= 4_096

    private fun String.normalizedDigestOrNull(): String? {
        val normalized = lowercase().replace(":", "")
        return normalized.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }
}
