package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class DevelopmentOtaContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fixedBoundsTimeoutsAndPrivatePathsMatchDevelopmentContract() {
        assertEquals(64 * 1024, DevelopmentManifestValidator.MAX_MANIFEST_BYTES)
        assertEquals(256L * 1024L * 1024L, DevOtaTrust.MAX_APK_BYTES)
        assertEquals(10_000, DevelopmentNetworkPolicy.CONNECT_TIMEOUT_MILLIS)
        assertEquals(20_000, DevelopmentNetworkPolicy.READ_TIMEOUT_MILLIS)
        assertEquals(5, DevelopmentNetworkPolicy.MAX_REDIRECTS)

        val cacheDir = temporaryFolder.newFolder()
        val store = UpdateFileStore(cacheDir)
        store.initialize()
        assertEquals(
            File(cacheDir, "updates/CamX-dev.apk.part").canonicalFile,
            store.partFile,
        )
        assertEquals(
            File(cacheDir, "updates/verified/CamX-dev.apk").canonicalFile,
            store.verifiedFile,
        )
    }
}
