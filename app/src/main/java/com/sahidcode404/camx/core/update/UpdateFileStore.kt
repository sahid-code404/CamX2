package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import java.io.File

internal class UpdateStorageException(
    val code: UpdateFailureCode = UpdateFailureCode.STORAGE_IO,
) : Exception(code.name)

internal class UpdateFileStore(cacheDir: File) {
    private val cacheRoot = cacheDir.canonicalFile
    private val root = File(cacheRoot, "updates").canonicalFile
    val verifiedDirectory: File = File(root, "verified").canonicalFile
    val partFile: File = File(root, "${DevOtaTrust.APK_ASSET_NAME}.part").canonicalFile
    val verifiedFile: File = File(verifiedDirectory, DevOtaTrust.APK_ASSET_NAME).canonicalFile

    fun initialize() {
        ensureDirectories()
        // A .part file can never be a valid install proof and is always safe to discard.
        deletePart()
    }

    fun preparePart(): File {
        ensureDirectories()
        deletePart()
        if (!partFile.createNewFile()) throw UpdateStorageException()
        return partFile
    }

    fun promotePart(): File {
        ensureDirectories()
        if (!partFile.isFile || partFile.length() <= 0L) throw UpdateStorageException()
        validateBoundaries()
        if (verifiedFile.exists() && !verifiedFile.delete()) throw UpdateStorageException()
        // Same-cache-filesystem rename is the only promotion path. Never copy a partial candidate
        // into the verified basename if atomic rename cannot be completed.
        if (!partFile.renameTo(verifiedFile)) throw UpdateStorageException()
        return verifiedFile
    }

    fun deletePart() {
        if (partFile.exists() && !partFile.delete()) throw UpdateStorageException()
    }

    fun deleteVerifiedCandidate() {
        if (verifiedFile.exists() && !verifiedFile.delete()) throw UpdateStorageException()
    }

    private fun ensureDirectories() {
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) throw UpdateStorageException()
        if ((!verifiedDirectory.exists() && !verifiedDirectory.mkdirs()) || !verifiedDirectory.isDirectory) {
            throw UpdateStorageException()
        }
        validateBoundaries()
    }

    private fun validateBoundaries() {
        if (root.parentFile != cacheRoot ||
            verifiedDirectory.parentFile != root ||
            partFile.parentFile != root ||
            verifiedFile.parentFile != verifiedDirectory
        ) {
            throw UpdateStorageException(UpdateFailureCode.STORAGE_BOUNDARY_VIOLATION)
        }
    }
}
