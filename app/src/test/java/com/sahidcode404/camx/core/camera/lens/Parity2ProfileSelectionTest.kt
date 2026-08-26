package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity2ProfileSelectionTest {
    private val lensFingerprint = CanonicalLensFingerprint("lens:test")

    @Test
    fun `credible unverified control quality ranks public then physical then deep`() {
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        val physical = target("physical", CameraRouteSource.JAVA_PHYSICAL, physical = true)
        val deep = target("deep", CameraRouteSource.JAVA_DEEP_PROBED)
        assertEquals(
            listOf("public", "physical", "deep"),
            LensProfileRanker.rank(listOf(deep, physical, public), null, false)
                .map { it.profileFingerprint.value.removePrefix("profile:") },
        )
    }

    @Test
    fun `verified deep sibling outranks unverified public without changing lens identity`() {
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        val deep = target("deep", CameraRouteSource.JAVA_DEEP_PROBED, previewTrust = PreviewTrust.VERIFIED)
        val ranked = LensProfileRanker.rank(listOf(public, deep), null, false)
        assertEquals(deep.profileFingerprint, ranked.first().profileFingerprint)
        assertTrue(ranked.all { it.canonicalFingerprint == lensFingerprint })
    }

    @Test
    fun `verified physical sibling may win without changing lens identity`() {
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        val physical = target(
            "physical",
            CameraRouteSource.JAVA_PHYSICAL,
            physical = true,
            previewTrust = PreviewTrust.VERIFIED,
        )
        val ranked = LensProfileRanker.rank(listOf(public, physical), null, false)
        assertEquals(physical.profileFingerprint, ranked.first().profileFingerprint)
        assertEquals(lensFingerprint, ranked.first().canonicalFingerprint)
    }

    @Test
    fun `transient failure loses to healthy sibling but is not structurally blacklisted`() {
        val transient = target(
            "transient",
            CameraRouteSource.JAVA_PUBLIC,
            metadataTrust = CameraTrust.TEMPORARILY_UNAVAILABLE,
            previewTrust = PreviewTrust.TEMPORARILY_UNAVAILABLE,
        )
        val healthy = target("healthy", CameraRouteSource.JAVA_DEEP_PROBED)
        val ranked = LensProfileRanker.rank(listOf(transient, healthy), null, false)
        assertEquals(healthy.profileFingerprint, ranked.first().profileFingerprint)
        assertTrue(ranked.any { it.profileFingerprint == transient.profileFingerprint })
    }

    @Test
    fun `active first frame verified sibling dominates deterministic route preference`() {
        val public = target("public", CameraRouteSource.JAVA_PUBLIC)
        val physical = target("physical", CameraRouteSource.JAVA_PHYSICAL, physical = true)
        val active = ActiveCameraSelection(
            canonicalLensFingerprint = lensFingerprint,
            profileFingerprint = physical.profileFingerprint,
            routeId = physical.routeId,
            selectionGeneration = SelectionGeneration(1L),
            sessionGeneration = SessionGeneration(1L),
        )
        assertEquals(
            physical.profileFingerprint,
            LensProfileRanker.rank(listOf(public, physical), active, true).first().profileFingerprint,
        )
    }

    @Test
    fun `one verified sibling keeps whole lens verified when another structurally fails`() {
        val lens = lens(
            route("failed", CameraRouteSource.JAVA_PUBLIC, CameraTrust.STRUCTURALLY_REJECTED, PreviewTrust.STRUCTURALLY_REJECTED),
            route("verified", CameraRouteSource.JAVA_DEEP_PROBED, CameraTrust.VERIFIED, PreviewTrust.VERIFIED),
        )
        val trust = CanonicalLensTrustAggregator.aggregate(lens)
        assertEquals(CameraTrust.VERIFIED, trust.metadataTrust)
        assertEquals(PreviewTrust.VERIFIED, trust.previewTrust)
        assertFalse(trust.structurallyUnavailable)
    }

    @Test
    fun `all structurally rejected siblings make whole lens unavailable`() {
        val lens = lens(
            route("failed-a", CameraRouteSource.JAVA_PUBLIC, CameraTrust.STRUCTURALLY_REJECTED, PreviewTrust.STRUCTURALLY_REJECTED),
            route("failed-b", CameraRouteSource.JAVA_DEEP_PROBED, CameraTrust.STRUCTURALLY_REJECTED, PreviewTrust.STRUCTURALLY_REJECTED),
        )
        val trust = CanonicalLensTrustAggregator.aggregate(lens)
        assertEquals(CameraTrust.STRUCTURALLY_REJECTED, trust.metadataTrust)
        assertEquals(PreviewTrust.STRUCTURALLY_REJECTED, trust.previewTrust)
        assertTrue(trust.structurallyUnavailable)
    }

    @Test
    fun `transient sibling cannot permanently reject healthy canonical lens`() {
        val lens = lens(
            route("transient", CameraRouteSource.JAVA_PUBLIC, CameraTrust.TEMPORARILY_UNAVAILABLE, PreviewTrust.TEMPORARILY_UNAVAILABLE),
            route("healthy", CameraRouteSource.JAVA_DEEP_PROBED, CameraTrust.ADVERTISED, PreviewTrust.ADVERTISED),
        )
        val trust = CanonicalLensTrustAggregator.aggregate(lens)
        assertEquals(CameraTrust.ADVERTISED, trust.metadataTrust)
        assertEquals(PreviewTrust.ADVERTISED, trust.previewTrust)
        assertFalse(trust.structurallyUnavailable)
    }

    private fun target(
        name: String,
        source: CameraRouteSource,
        physical: Boolean = false,
        metadataTrust: CameraTrust = CameraTrust.ADVERTISED,
        previewTrust: PreviewTrust = PreviewTrust.ADVERTISED,
    ): LensSelectionTarget {
        val route = route(name, source, metadataTrust, previewTrust, physical)
        return LensSelectionTarget(
            canonicalFingerprint = lensFingerprint,
            profileFingerprint = CameraProfileFingerprint("profile:$name"),
            routeId = route.id,
            route = route,
            previewMetadata = LensPreviewMetadata(90, LensFacing.BACK),
        )
    }

    private fun lens(vararg routes: CameraRoute): CanonicalLens = CanonicalLens(
        fingerprint = lensFingerprint,
        facing = LensFacing.BACK,
        profiles = routes.map { route ->
            CameraProfile(
                fingerprint = CameraProfileFingerprint("profile:${route.id.value}"),
                canonicalFingerprint = lensFingerprint,
                route = route,
            )
        },
    )

    private fun route(
        name: String,
        source: CameraRouteSource,
        metadataTrust: CameraTrust,
        previewTrust: PreviewTrust,
        physical: Boolean = false,
    ) = CameraRoute(
        id = CameraRouteId("route:$name"),
        source = source,
        openCameraId = CameraTransportId(if (physical) "logical:$name" else "transport:$name"),
        physicalCameraId = if (physical) PhysicalCameraId("member:$name") else null,
        capabilities = CameraCapabilities(),
        metadataTrust = metadataTrust,
        previewTrust = previewTrust,
        rawTrust = RawTrust.UNKNOWN,
        sources = setOf(source),
    )
}
