package com.sahidcode404.camx.core.update.verification

import com.sahidcode404.camx.core.update.UpdateFailureCode
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedApkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun tokenRevalidatesUnchangedPrivatePromotedFile() {
        val directory = temporaryFolder.newFolder("updates")
        val apk = File(directory, DevOtaTrust.APK_ASSET_NAME).apply { writeText("verified") }
        val digest = apk.digest()
        val identity = PromotedApkFileValidator.validate(apk, directory, digest)
        assertEquals(apk.canonicalFile, identity.canonicalApk)
        assertEquals(digest, identity.sha256)
    }

    @Test
    fun tokenRejectsMutationAndFilesOutsidePrivateDirectory() {
        val directory = temporaryFolder.newFolder("updates")
        val apk = File(directory, DevOtaTrust.APK_ASSET_NAME).apply { writeText("verified") }
        val identity = PromotedApkFileValidator.validate(apk, directory, apk.digest())
        apk.writeText("changed-content")
        assertThrows(IllegalStateException::class.java) {
            PromotedApkFileValidator.revalidate(identity)
        }

        val outside = temporaryFolder.newFile(DevOtaTrust.APK_ASSET_NAME).apply { writeText("outside") }
        assertThrows(IllegalArgumentException::class.java) {
            PromotedApkFileValidator.validate(outside, directory, outside.digest())
        }
    }

    @Test
    fun hostileCandidateFailuresHaveStableCodes() {
        val trustedDirectory = temporaryFolder.newFolder("trusted")
        val outside = temporaryFolder.newFile(DevOtaTrust.APK_ASSET_NAME).apply {
            writeText("outside")
        }
        val boundaryFailure = assertThrows(RejectedApkCandidate::class.java) {
            PromotedApkFileValidator.preflight(outside, trustedDirectory)
        }
        assertEquals(UpdateFailureCode.STORAGE_BOUNDARY_VIOLATION, boundaryFailure.code)

        val empty = File(trustedDirectory, DevOtaTrust.APK_ASSET_NAME).apply { createNewFile() }
        val inspectionFailure = assertThrows(RejectedApkCandidate::class.java) {
            PromotedApkFileValidator.preflight(empty, trustedDirectory)
        }
        assertEquals(UpdateFailureCode.APK_INSPECTION_FAILED, inspectionFailure.code)
    }

    private fun File.digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
