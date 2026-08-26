package com.sahidcode404.camx.core.camera.runtime

import com.sahidcode404.camx.core.camera.diagnostics.MediaStoreFailure
import com.sahidcode404.camx.core.camera.diagnostics.RawSessionRejected
import com.sahidcode404.camx.core.camera.diagnostics.SafeBaselineConfigurationRejected
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SameCanonicalFailoverPolicyTest {
    private val selected = CanonicalLensFingerprint("lens:selected")
    private val active = profile("active", selected)
    private val sibling = profile("sibling", selected)
    private val other = profile("other", CanonicalLensFingerprint("lens:other"))
    private val topology = CameraTopologySnapshot(
        schema = 1,
        environment = CameraEnvironmentFingerprint("environment"),
        routes = listOf(active.route, sibling.route, other.route),
        canonicalLenses = listOf(
            CanonicalLens(selected, LensFacing.BACK, listOf(active, sibling)),
            CanonicalLens(other.canonicalFingerprint, LensFacing.BACK, listOf(other)),
        ),
        generatedAtElapsedRealtimeNs = 1L,
    )

    @Test
    fun structuralFailureReturnsOnlySiblingProfile() {
        val result = SameCanonicalFailoverPolicy.nextProfile(
            topology,
            selected,
            active.fingerprint,
            setOf(active.fingerprint),
            SafeBaselineConfigurationRejected,
        )
        assertEquals(sibling.fingerprint, result?.fingerprint)
    }

    @Test
    fun storageFailureCannotRotateProfile() {
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topology,
                selected,
                active.fingerprint,
                emptySet(),
                MediaStoreFailure("disk full"),
            ),
        )
    }

    @Test
    fun attemptedSiblingEndsFailoverWithoutCrossingLens() {
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topology,
                selected,
                active.fingerprint,
                setOf(sibling.fingerprint),
                SafeBaselineConfigurationRejected,
            ),
        )
    }

    @Test
    fun mismatchedActiveProfileFailsClosedInsteadOfCrossingOptics() {
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topology,
                selected,
                other.fingerprint,
                emptySet(),
                SafeBaselineConfigurationRejected,
            ),
        )
    }

    @Test
    fun knownRejectedSiblingIsNeverRetried() {
        val rejected = sibling.copy(
            route = sibling.route.copy(previewTrust = PreviewTrust.STRUCTURALLY_REJECTED),
        )
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topologyWithSibling(rejected),
                selected,
                active.fingerprint,
                setOf(active.fingerprint),
                SafeBaselineConfigurationRejected,
            ),
        )
    }

    @Test
    fun rawFailoverRequiresRawCapabilityAndNonRejectedTrust() {
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topology,
                selected,
                active.fingerprint,
                setOf(active.fingerprint),
                RawSessionRejected,
            ),
        )
        val rawRejected = sibling.copy(
            route = sibling.route.copy(
                capabilities = CameraCapabilities(rawSizes = listOf(IntSize(4000, 3000))),
                rawTrust = RawTrust.STRUCTURALLY_REJECTED,
            ),
        )
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topologyWithSibling(rawRejected),
                selected,
                active.fingerprint,
                setOf(active.fingerprint),
                RawSessionRejected,
            ),
        )
        val previewRejected = sibling.copy(
            route = sibling.route.copy(
                capabilities = CameraCapabilities(rawSizes = listOf(IntSize(4000, 3000))),
                previewTrust = PreviewTrust.STRUCTURALLY_REJECTED,
            ),
        )
        assertNull(
            SameCanonicalFailoverPolicy.nextProfile(
                topologyWithSibling(previewRejected),
                selected,
                active.fingerprint,
                setOf(active.fingerprint),
                RawSessionRejected,
            ),
        )
    }

    private fun topologyWithSibling(candidate: CameraProfile) = CameraTopologySnapshot(
        schema = 1,
        environment = CameraEnvironmentFingerprint("environment"),
        routes = listOf(active.route, candidate.route, other.route),
        canonicalLenses = listOf(
            CanonicalLens(selected, LensFacing.BACK, listOf(active, candidate)),
            CanonicalLens(other.canonicalFingerprint, LensFacing.BACK, listOf(other)),
        ),
        generatedAtElapsedRealtimeNs = 1L,
    )

    private fun profile(
        name: String,
        canonical: CanonicalLensFingerprint,
    ): CameraProfile {
        val fingerprint = CameraProfileFingerprint("profile:$name")
        return CameraProfile(
            fingerprint = fingerprint,
            canonicalFingerprint = canonical,
            route = CameraRoute(
                id = CameraRouteId("route:$name"),
                source = CameraRouteSource.JAVA_PUBLIC,
                openCameraId = CameraTransportId("opaque-$name"),
                capabilities = CameraCapabilities(),
                metadataTrust = CameraTrust.ADVERTISED,
            ),
        )
    }
}
