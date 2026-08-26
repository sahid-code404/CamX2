package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.diagnostics.CameraInUse
import com.sahidcode404.camx.core.camera.diagnostics.SafeBaselineConfigurationRejected
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensProfileRankerAndFailoverTest {
    @Test
    fun `verified profile outranks unverified public profile`() {
        val verifiedDeep = target("deep", CameraRouteSource.JAVA_DEEP_PROBED, previewTrust = PreviewTrust.VERIFIED)
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        assertEquals("deep", LensProfileRanker.rank(listOf(public, verifiedDeep), null, false).first().routeId.value)
    }

    @Test
    fun `active first-frame verified profile outranks every source preference`() {
        val physical = target("physical", CameraRouteSource.JAVA_PHYSICAL, physical = true)
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        val active = selection(physical)
        assertEquals(
            "physical",
            LensProfileRanker.rank(listOf(public, physical), active, true).first().routeId.value,
        )
    }

    @Test
    fun `verified physical may outrank unverified direct`() {
        val physical = target(
            "physical",
            CameraRouteSource.JAVA_PHYSICAL,
            physical = true,
            previewTrust = PreviewTrust.VERIFIED,
        )
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        assertEquals("physical", LensProfileRanker.rank(listOf(public, physical), null, false).first().routeId.value)
    }

    @Test
    fun `public direct outranks unverified deep direct`() {
        val deep = target("deep", CameraRouteSource.JAVA_DEEP_PROBED)
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        assertEquals("public", LensProfileRanker.rank(listOf(deep, public), null, false).first().routeId.value)
    }

    @Test
    fun `physical outranks unverified deep direct when trust equal`() {
        val deep = target("deep", CameraRouteSource.JAVA_DEEP_PROBED)
        val physical = target("physical", CameraRouteSource.JAVA_PHYSICAL, physical = true)
        assertEquals("physical", LensProfileRanker.rank(listOf(physical, deep), null, false).first().routeId.value)
    }

    @Test
    fun `ranking tie break is permutation deterministic`() {
        val a = target("a", CameraRouteSource.JAVA_PUBLIC)
        val b = target("b", CameraRouteSource.JAVA_PUBLIC)
        val forward = LensProfileRanker.rank(listOf(b, a), null, false).map { it.routeId }
        val reverse = LensProfileRanker.rank(listOf(a, b), null, false).map { it.routeId }
        assertEquals(forward, reverse)
        assertEquals(listOf(CameraRouteId("a"), CameraRouteId("b")), forward)
    }

    @Test
    fun `structural failure chooses at most next same canonical unattempted profile`() {
        val a = target("a", CameraRouteSource.JAVA_PUBLIC)
        val b = target("b", CameraRouteSource.JAVA_DEEP_PROBED)
        val next = LensProfileFailoverPlanner.next(
            canonicalFingerprint = a.canonicalFingerprint,
            failedProfile = a.profileFingerprint,
            attemptedProfiles = setOf(a.profileFingerprint),
            failure = SafeBaselineConfigurationRejected,
            rankedEligibleTargets = listOf(a, b),
        )
        assertEquals(b.profileFingerprint, next?.profileFingerprint)
        assertNull(
            LensProfileFailoverPlanner.next(
                canonicalFingerprint = a.canonicalFingerprint,
                failedProfile = b.profileFingerprint,
                attemptedProfiles = setOf(a.profileFingerprint, b.profileFingerprint),
                failure = SafeBaselineConfigurationRejected,
                rankedEligibleTargets = listOf(a, b),
            ),
        )
    }

    @Test
    fun `transient failure never triggers structural profile failover`() {
        val a = target("a", CameraRouteSource.JAVA_PUBLIC)
        val b = target("b", CameraRouteSource.JAVA_DEEP_PROBED)
        assertNull(
            LensProfileFailoverPlanner.next(
                canonicalFingerprint = a.canonicalFingerprint,
                failedProfile = a.profileFingerprint,
                attemptedProfiles = setOf(a.profileFingerprint),
                failure = CameraInUse,
                rankedEligibleTargets = listOf(a, b),
            ),
        )
    }

    @Test
    fun `different canonical lens is never a failover target`() {
        val a = target("a", CameraRouteSource.JAVA_PUBLIC, canonical = "lens:a")
        val other = target("other", CameraRouteSource.JAVA_PUBLIC, canonical = "lens:other")
        assertNull(
            LensProfileFailoverPlanner.next(
                canonicalFingerprint = a.canonicalFingerprint,
                failedProfile = a.profileFingerprint,
                attemptedProfiles = setOf(a.profileFingerprint),
                failure = SafeBaselineConfigurationRejected,
                rankedEligibleTargets = listOf(other),
            ),
        )
    }

    private fun selection(target: LensSelectionTarget) = ActiveCameraSelection(
        canonicalLensFingerprint = target.canonicalFingerprint,
        profileFingerprint = target.profileFingerprint,
        routeId = target.routeId,
        selectionGeneration = SelectionGeneration(1L),
        sessionGeneration = SessionGeneration(1L),
    )

    private fun target(
        id: String,
        source: CameraRouteSource,
        physical: Boolean = false,
        previewTrust: PreviewTrust = PreviewTrust.ADVERTISED,
        canonical: String = "lens:shared",
    ): LensSelectionTarget {
        val route = CameraRoute(
            id = CameraRouteId(id),
            source = source,
            openCameraId = CameraTransportId(if (physical) "logical" else id),
            physicalCameraId = if (physical) PhysicalCameraId(id) else null,
            capabilities = CameraCapabilities(
                previewStreams = listOf(
                    CameraStreamCapability(
                        PreviewStreamType.CAMERA2_PRIVATE,
                        IntSize(1280, 720),
                        33_333_333L,
                    ),
                ),
            ),
            metadataTrust = CameraTrust.ADVERTISED,
            previewTrust = previewTrust,
        )
        return LensSelectionTarget(
            canonicalFingerprint = CanonicalLensFingerprint(canonical),
            profileFingerprint = CameraProfileFingerprint("profile:$id"),
            routeId = route.id,
            route = route,
            previewMetadata = LensPreviewMetadata(90, LensFacing.BACK),
        )
    }
}
