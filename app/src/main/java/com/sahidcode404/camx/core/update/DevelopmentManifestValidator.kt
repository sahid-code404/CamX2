package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import com.sahidcode404.camx.core.update.verification.InstalledAppIdentity

internal sealed interface DevelopmentManifestCheck {
    data class Available(val manifest: DevOtaManifest) : DevelopmentManifestCheck
    data class UpToDate(val manifest: DevOtaManifest) : DevelopmentManifestCheck
    data class Rejected(val code: UpdateFailureCode) : DevelopmentManifestCheck
}

internal object DevelopmentManifestValidator {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ANDROID_VERSION_CODE = 2_100_000_000L

    fun validate(
        manifest: DevOtaManifest,
        installed: InstalledAppIdentity,
    ): DevelopmentManifestCheck {
        fun reject(code: UpdateFailureCode) = DevelopmentManifestCheck.Rejected(code)
        if (manifest.schema != DevOtaTrust.SCHEMA) return reject(UpdateFailureCode.INVALID_SCHEMA)
        if (manifest.channel != DevOtaTrust.CHANNEL) return reject(UpdateFailureCode.CHANNEL_MISMATCH)
        if (manifest.applicationId != DevOtaTrust.APPLICATION_ID ||
            installed.applicationId != DevOtaTrust.APPLICATION_ID
        ) {
            return reject(UpdateFailureCode.PACKAGE_MISMATCH)
        }
        if (manifest.apkAssetName != DevOtaTrust.APK_ASSET_NAME ||
            manifest.apkAssetName.any { it == '/' || it == '\\' }
        ) {
            return reject(UpdateFailureCode.INVALID_ASSET_NAME)
        }
        if (manifest.minSdk != DevOtaTrust.APPLICATION_MIN_SDK ||
            manifest.minSdk > installed.sdkInt
        ) {
            return reject(UpdateFailureCode.MIN_SDK_UNSUPPORTED)
        }
        if (manifest.signingCertSha256.normalizedDevOtaSha256OrNull() != DevOtaTrust.CERT_SHA256 ||
            installed.signingCertSha256.normalizedDevOtaSha256OrNull() != DevOtaTrust.CERT_SHA256
        ) {
            return reject(UpdateFailureCode.SIGNATURE_MISMATCH)
        }
        if (manifest.sha256.normalizedDevOtaSha256OrNull() == null) {
            return reject(UpdateFailureCode.INVALID_DIGEST)
        }
        if (!versionFieldsAreBounded(manifest)) return reject(UpdateFailureCode.INVALID_VERSION)
        if (manifest.versionCode <= installed.versionCode) {
            return DevelopmentManifestCheck.UpToDate(manifest)
        }
        return DevelopmentManifestCheck.Available(manifest)
    }

    private fun versionFieldsAreBounded(manifest: DevOtaManifest): Boolean =
        manifest.versionCode in 1..MAX_ANDROID_VERSION_CODE &&
            manifest.versionName.isNotBlank() &&
            manifest.versionName.length <= 128 &&
            manifest.versionName.none { it.isISOControl() } &&
            manifest.gitSha.length in 7..64 &&
            manifest.gitSha.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } &&
            manifest.buildTimestamp.isNotBlank() &&
            manifest.buildTimestamp.length <= 64 &&
            manifest.buildTimestamp.none { it.isISOControl() } &&
            manifest.changelog.length <= 4_096
}

internal fun String.normalizedDevOtaSha256OrNull(): String? {
    val normalized = lowercase().replace(":", "")
    return normalized.takeIf {
        it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' }
    }
}
