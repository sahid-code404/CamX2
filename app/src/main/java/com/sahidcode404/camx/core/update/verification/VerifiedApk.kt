package com.sahidcode404.camx.core.update.verification

import java.io.File
import java.security.MessageDigest
import com.sahidcode404.camx.core.update.DevOtaManifest
import com.sahidcode404.camx.core.update.UpdateFailureCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface VerifiedApkPromotion {
    data class Ready(val apk: VerifiedApk) : VerifiedApkPromotion
    data class Rejected(val code: UpdateFailureCode) : VerifiedApkPromotion
}

internal data class PromotedApkFileIdentity(
    val canonicalApk: File,
    val canonicalDirectory: File,
    val sha256: String,
    val byteCount: Long,
    val lastModifiedMillis: Long,
)

internal class RejectedApkCandidate(
    val code: UpdateFailureCode,
    message: String,
) : IllegalArgumentException(message)

internal object PromotedApkFileValidator {
    fun preflight(apk: File, privateUpdateDirectory: File) {
        validateBoundary(apk, privateUpdateDirectory)
    }

    fun validate(
        apk: File,
        privateUpdateDirectory: File,
        expectedSha256: String,
    ): PromotedApkFileIdentity {
        val normalizedDigest = expectedSha256.normalizedSha256()
        val (canonicalApk, canonicalDirectory) = validateBoundary(apk, privateUpdateDirectory)
        if (canonicalApk.sha256() != normalizedDigest) {
            throw RejectedApkCandidate(
                UpdateFailureCode.SHA256_MISMATCH,
                "Verified APK SHA-256 mismatch",
            )
        }
        return PromotedApkFileIdentity(
            canonicalApk = canonicalApk,
            canonicalDirectory = canonicalDirectory,
            sha256 = normalizedDigest,
            byteCount = canonicalApk.length(),
            lastModifiedMillis = canonicalApk.lastModified(),
        )
    }

    private fun validateBoundary(apk: File, privateUpdateDirectory: File): Pair<File, File> {
        val canonicalDirectory = privateUpdateDirectory.canonicalFile
        val canonicalApk = apk.canonicalFile
        if (canonicalApk.parentFile != canonicalDirectory) {
            throw RejectedApkCandidate(
                UpdateFailureCode.STORAGE_BOUNDARY_VIOLATION,
                "Verified APK must be a direct child of the app-private update directory",
            )
        }
        if (canonicalApk.name != DevOtaTrust.APK_ASSET_NAME) {
            throw RejectedApkCandidate(
                UpdateFailureCode.INVALID_ASSET_NAME,
                "Verified APK filename does not match the development channel",
            )
        }
        if (!canonicalApk.isFile || canonicalApk.length() <= 0L) {
            throw RejectedApkCandidate(
                UpdateFailureCode.APK_INSPECTION_FAILED,
                "Verified APK is absent or empty",
            )
        }
        if (canonicalApk.length() > DevOtaTrust.MAX_APK_BYTES) {
            throw RejectedApkCandidate(
                UpdateFailureCode.DOWNLOAD_TOO_LARGE,
                "Verified APK exceeds the development-channel size bound",
            )
        }
        return canonicalApk to canonicalDirectory
    }

    fun revalidate(identity: PromotedApkFileIdentity): File {
        val current = identity.canonicalApk.canonicalFile
        check(current.parentFile == identity.canonicalDirectory) {
            "Verified APK escaped its private directory"
        }
        check(current.isFile && current.length() == identity.byteCount) {
            "Verified APK file identity changed"
        }
        check(current.lastModified() == identity.lastModifiedMillis) {
            "Verified APK modification time changed"
        }
        check(current.sha256() == identity.sha256) { "Verified APK content changed" }
        return current
    }
}

/** Opaque proof that an app-private APK was verified after atomic promotion. */
class VerifiedApk private constructor(
    private val canonicalApk: File,
    private val canonicalDirectory: File,
    val sha256: String,
    private val byteCount: Long,
    private val lastModifiedMillis: Long,
) {
    /** Revalidates file identity and content immediately before handing it to PackageInstaller. */
    internal fun revalidateForInstall(): File = PromotedApkFileValidator.revalidate(
        PromotedApkFileIdentity(
            canonicalApk = canonicalApk,
            canonicalDirectory = canonicalDirectory,
            sha256 = sha256,
            byteCount = byteCount,
            lastModifiedMillis = lastModifiedMillis,
        ),
    )

    companion object {
        /** The only production mint: inspect package/signers, verify manifest, then bind the file. */
        suspend fun verifyAndPromote(
            apk: File,
            manifest: DevOtaManifest,
            inspector: AndroidApkInspector,
        ): VerifiedApkPromotion = withContext(Dispatchers.IO) {
            val privateUpdateDirectory = inspector.verifiedUpdateDirectory()
            try {
                PromotedApkFileValidator.preflight(apk, privateUpdateDirectory)
            } catch (rejected: RejectedApkCandidate) {
                return@withContext VerifiedApkPromotion.Rejected(rejected.code)
            }
            val verification = try {
                DevelopmentUpdateVerifier.verify(
                    manifest = manifest,
                    installed = inspector.inspectInstalled(),
                    downloaded = inspector.inspect(apk),
                )
            } catch (_: IllegalArgumentException) {
                return@withContext VerifiedApkPromotion.Rejected(
                    UpdateFailureCode.APK_INSPECTION_FAILED,
                )
            } catch (_: IllegalStateException) {
                return@withContext VerifiedApkPromotion.Rejected(
                    UpdateFailureCode.APK_INSPECTION_FAILED,
                )
            }
            if (verification is UpdateVerification.Rejected) {
                return@withContext VerifiedApkPromotion.Rejected(verification.code)
            }
            val identity = try {
                PromotedApkFileValidator.validate(
                    apk = apk,
                    privateUpdateDirectory = privateUpdateDirectory,
                    expectedSha256 = manifest.sha256,
                )
            } catch (rejected: RejectedApkCandidate) {
                return@withContext VerifiedApkPromotion.Rejected(rejected.code)
            }
            VerifiedApk(
                canonicalApk = identity.canonicalApk,
                canonicalDirectory = identity.canonicalDirectory,
                sha256 = identity.sha256,
                byteCount = identity.byteCount,
                lastModifiedMillis = identity.lastModifiedMillis,
            ).let(VerifiedApkPromotion::Ready)
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun String.normalizedSha256(): String {
    val normalized = lowercase().replace(":", "")
    if (normalized.length != 64 || normalized.any { it !in '0'..'9' && it !in 'a'..'f' }) {
        throw RejectedApkCandidate(
            UpdateFailureCode.SHA256_MISMATCH,
            "Expected SHA-256 is malformed",
        )
    }
    return normalized
}
