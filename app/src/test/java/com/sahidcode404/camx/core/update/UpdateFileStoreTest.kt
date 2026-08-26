package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun initializationDeletesOnlyStalePartAndKeepsSingleVerifiedCandidate() {
        val cache = temporaryFolder.newFolder()
        val root = File(cache, "updates").apply { mkdirs() }
        val verifiedDir = File(root, "verified").apply { mkdirs() }
        val stalePart = File(root, "${DevOtaTrust.APK_ASSET_NAME}.part").apply { writeText("partial") }
        val verified = File(verifiedDir, DevOtaTrust.APK_ASSET_NAME).apply { writeText("verified") }

        val store = UpdateFileStore(cache)
        store.initialize()

        assertFalse(stalePart.exists())
        assertTrue(verified.exists())
        assertEquals(verified.canonicalFile, store.verifiedFile)
    }

    @Test
    fun candidateUsesPrivatePartThenExactVerifiedBasename() {
        val store = UpdateFileStore(temporaryFolder.newFolder())
        store.initialize()
        val part = store.preparePart()
        part.writeText("complete-apk")

        val promoted = store.promotePart()

        assertFalse(store.partFile.exists())
        assertEquals(DevOtaTrust.APK_ASSET_NAME, promoted.name)
        assertEquals(store.verifiedDirectory, promoted.parentFile)
        assertEquals("complete-apk", promoted.readText())
    }

    @Test
    fun retryStartsWithCleanPart() {
        val store = UpdateFileStore(temporaryFolder.newFolder())
        store.initialize()
        store.preparePart().writeText("corrupt")
        store.deletePart()

        val retry = store.preparePart()

        assertTrue(retry.isFile)
        assertEquals(0L, retry.length())
    }
}
