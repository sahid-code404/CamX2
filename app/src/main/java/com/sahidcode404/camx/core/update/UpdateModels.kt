package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.VerifiedApk
import kotlinx.serialization.Serializable

@Serializable
data class DevOtaManifest(
    val schema: Int,
    val channel: String,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkAssetName: String,
    val sha256: String,
    val signingCertSha256: String,
    val gitSha: String,
    val buildTimestamp: String,
    val changelog: String,
    val mandatory: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: DevOtaManifest) : UpdateState
    data class Downloading(val versionCode: Long) : UpdateState
    data class ReadyToInstall(val apk: VerifiedApk, val manifest: DevOtaManifest) : UpdateState
    data class Failed(val code: UpdateFailureCode) : UpdateState
}

enum class UpdateFailureCode {
    INVALID_SCHEMA,
    CHANNEL_MISMATCH,
    PACKAGE_MISMATCH,
    NOT_AN_UPGRADE,
    MIN_SDK_UNSUPPORTED,
    INVALID_ASSET_NAME,
    APK_INSPECTION_FAILED,
    STORAGE_BOUNDARY_VIOLATION,
    APK_VERSION_MISMATCH,
    SHA256_MISMATCH,
    SIGNATURE_MISMATCH,
    DOWNLOAD_TOO_LARGE,
    NETWORK,
    CANCELLED,
}
