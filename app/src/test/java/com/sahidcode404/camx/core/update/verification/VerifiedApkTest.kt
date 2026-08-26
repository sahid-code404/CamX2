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
        assertEquals(apk.canonicalFile, PromotedApkFileValidator.revalidate(identity))
    }

    @Test
    fun fileChangedAfterVerificationIsRejectedBeforeInstallerCanUseIt() {
        val directory = temporaryFolder.newFolder("updates")
        val apk = File(directory, DevOtaTrust.APK_ASSET_NAME).apply { writeText("verified") }
        val identity = PromotedApkFileValidator.validate(apk, directory, apk.digest())

        apk.writeText("changed-content")

        assertThrows(IllegalStateException::class.java) {
            PromotedApkFileValidator.revalidate(identity)
        }
    }

    @Test
    fun fileChangedWithoutLengthChangeIsAlsoRejected() {
        val directory = temporaryFolder.newFolder("updates")
        val apk = File(directory, DevOtaTrust.APK_ASSET_NAME).apply { writeText("aaaa") }
        val identity = PromotedApkFileValidator.validate(apk, directory, apk.digest())
        val originalModified = apk.lastModified()

        apk.writeText("bbbb")
        apk.setLastModified(originalModified)

        assertThrows(IllegalStateException::class.java) {
            PromotedApkFileValidator.revalidate(identity)
        }
    }

    @Test
    fun fileOutsidePrivateBoundaryHasStableFailureCode() {
        val directory = temporaryFolder.newFolder("updates")
        val outside = temporaryFolder.newFile(DevOtaTrust.APK_ASSET_NAME).apply { writeText("outside") }
        val failure = assertThrows(RejectedApkCandidate::class.java) {
            PromotedApkFileValidator.preflight(outside, directory)
        }
        assertEquals(UpdateFailureCode.STORAGE_BOUNDARY_VIOLATION, failure.code)
    }

    @Test
    fun wrongVerifiedFilenameHasStableFailureCode() {
        val directory = temporaryFolder.newFolder("updates")
        val wrong = File(directory, "Other.apk").apply { writeText("candidate") }
        val failure = assertThrows(RejectedApkCandidate::class.java) {
            PromotedApkFileValidator.preflight(wrong, directory)
        }
        assertEquals(UpdateFailureCode.INVALID_ASSET_NAME, failure.code)
    }

    @Test
    fun emptyOrOversizedCandidateIsRejected() {
        val directory = temporaryFolder.newFolder("updates")
        val empty = File(directory, DevOtaTrust.APK_ASSET_NAME).apply { createNewFile() }
        val inspectionFailure = assertThrows(RejectedApkCandidate::class.java) {
            PromotedApkFileValidator.preflight(empty, directory)
        }
        assertEquals(UpdateFailureCode.APK_INSPECTION_FAILED, inspectionFailure.code)
    }

    private fun File.digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
