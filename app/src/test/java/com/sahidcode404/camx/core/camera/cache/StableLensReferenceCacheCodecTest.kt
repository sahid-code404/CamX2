package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.StableLensReferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableLensReferenceCacheCodecTest {
    @Test
    fun roundTripPreservesTypedCanonicalReference() {
        val environment = CameraEnvironmentFingerprint("reference-codec")
        val expected = StableLensReferenceSnapshot(
            schema = CameraSchemaVersions.LENS_REFERENCE,
            environment = environment,
            canonicalFingerprint = CanonicalLensFingerprint("lens:optical:abc123"),
        )
        val decoded = StableLensReferenceCacheCodec.decode(
            StableLensReferenceCacheCodec.encode(expected),
            environment,
        )
        assertTrue(decoded is CacheRead.Hit)
        assertEquals(expected, (decoded as CacheRead.Hit).value)
    }

    @Test
    fun environmentMismatchIsSafeMiss() {
        val stored = StableLensReferenceSnapshot(
            schema = CameraSchemaVersions.LENS_REFERENCE,
            environment = CameraEnvironmentFingerprint("device-a"),
            canonicalFingerprint = CanonicalLensFingerprint("lens:optical:abc123"),
        )
        val decoded = StableLensReferenceCacheCodec.decode(
            StableLensReferenceCacheCodec.encode(stored),
            CameraEnvironmentFingerprint("device-b"),
        )
        assertEquals(CacheRead.Miss, decoded)
    }
}
