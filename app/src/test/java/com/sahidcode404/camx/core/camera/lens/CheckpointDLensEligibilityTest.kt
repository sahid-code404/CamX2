package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckpointDLensEligibilityTest {
    @Test
    fun `java deep probed direct route is selectable without becoming verified`() {
        val fixture = fixture(
            route("deep", CameraRouteSource.JAVA_DEEP_PROBED),
            evidence("deep", CameraRouteSource.JAVA_DEEP_PROBED),
        )
        val result = resolve(fixture, api = 23)
        assertTrue(result is LensProfileEligibility.Eligible)
        assertEquals(1, CameraLensUiProjector.project(input(fixture, 23)).items.size)
    }

    @Test
    fun `ndk deep alone is typed ndk only and nonselectable`() {
        val fixture = fixture(
            route("hidden", CameraRouteSource.NDK_DEEP),
            evidence("hidden", CameraRouteSource.NDK_DEEP),
        )
        val result = resolve(fixture)
        assertEquals(
            LensProfileRejectionReason.NDK_ONLY,
            (result as LensProfileEligibility.Rejected).reason,
        )
        assertTrue(CameraLensUiProjector.project(input(fixture)).items.isEmpty())
    }

    @Test
    fun `ndk advertised alone remains nonselectable`() {
        val fixture = fixture(
            route("advertised", CameraRouteSource.NDK_ADVERTISED),
            evidence("advertised", CameraRouteSource.NDK_ADVERTISED),
        )
        assertEquals(
            LensProfileRejectionReason.NDK_ONLY,
            (resolve(fixture) as LensProfileEligibility.Rejected).reason,
        )
    }

    @Test
    fun `unrelated ndk orientation conflict cannot poison authoritative java route`() {
        val public = route("main", CameraRouteSource.JAVA_PUBLIC)
        val fixture = fixture(
            public,
            evidence("main", CameraRouteSource.JAVA_PUBLIC, orientation = 90),
            evidence("main", CameraRouteSource.NDK_ADVERTISED, orientation = 270),
        )
        assertTrue(resolve(fixture) is LensProfileEligibility.Eligible)
        assertEquals(1, CameraLensUiProjector.project(input(fixture)).items.size)
    }

    @Test
    fun `conflicting authoritative java orientation is rejected with typed reason`() {
        val fixture = fixture(
            route("main", CameraRouteSource.JAVA_PUBLIC),
            evidence("main", CameraRouteSource.JAVA_PUBLIC, orientation = 90),
            evidence("main", CameraRouteSource.JAVA_PUBLIC, orientation = 270),
        )
        assertEquals(
            LensProfileRejectionReason.CONFLICTING_AUTHORITATIVE_ORIENTATION,
            (resolve(fixture) as LensProfileEligibility.Rejected).reason,
        )
    }

    @Test
    fun `java physical requires api28`() {
        val physical = route(
            id = "physical",
            source = CameraRouteSource.JAVA_PHYSICAL,
            openId = "logical",
            physicalId = "member",
        )
        val fixture = fixture(
            physical,
            evidence(
                id = "logical",
                source = CameraRouteSource.JAVA_PHYSICAL,
                physicalId = "member",
                logicalParentId = "logical",
            ),
        )
        assertEquals(
            LensProfileRejectionReason.API_TOO_LOW_FOR_PHYSICAL_TARGET,
            (resolve(fixture, api = 27) as LensProfileEligibility.Rejected).reason,
        )
        assertTrue(resolve(fixture, api = 28) is LensProfileEligibility.Eligible)
    }

    @Test
    fun `queryable physical member is not fabricated as independent deep direct route`() {
        val fakeDirect = route(
            id = "member-direct",
            source = CameraRouteSource.JAVA_DEEP_PROBED,
            openId = "member",
        )
        val fixture = fixture(
            fakeDirect,
            evidence(
                id = "logical",
                source = CameraRouteSource.JAVA_PHYSICAL,
                physicalId = "member",
                logicalParentId = "logical",
            ),
        )
        val result = resolve(fixture)
        assertTrue(result is LensProfileEligibility.Rejected)
        assertTrue(CameraLensUiProjector.project(input(fixture)).items.isEmpty())
    }

    @Test
    fun `one canonical lens with multiple eligible profiles still produces one ui item`() {
        val public = profile(route("public", CameraRouteSource.JAVA_PUBLIC), "lens:shared")
        val deep = profile(route("deep", CameraRouteSource.JAVA_DEEP_PROBED), "lens:shared")
        val lens = CanonicalLens(CanonicalLensFingerprint("lens:shared"), LensFacing.BACK, listOf(public, deep))
        val topology = topology(
            routes = listOf(public.route, deep.route),
            lenses = listOf(lens),
            evidence = listOf(
                evidence("public", CameraRouteSource.JAVA_PUBLIC),
                evidence("deep", CameraRouteSource.JAVA_DEEP_PROBED),
            ),
        )
        val projection = CameraLensUiProjector.project(input(topology))
        assertEquals(1, projection.items.size)
        assertEquals(2, projection.eligibilityByProfile.size)
        assertTrue(projection.eligibilityByProfile.values.all { it is LensProfileEligibility.Eligible })
    }

    private fun resolve(topology: CameraTopologySnapshot, api: Int = 35): LensProfileEligibility {
        val lens = topology.canonicalLenses.single()
        return LensProfileEligibilityResolver.resolve(topology, lens, lens.profiles.single(), api)
    }

    private fun input(topology: CameraTopologySnapshot, api: Int = 35) = CameraLensProjectionInput(
        topology = topology,
        runtimeApiLevel = api,
        activeSelection = null,
    )

    private fun fixture(route: CameraRoute, vararg evidence: CameraMetadataEvidence): CameraTopologySnapshot {
        val lens = CanonicalLens(
            fingerprint = CanonicalLensFingerprint("lens:${route.id.value}"),
            facing = LensFacing.BACK,
            profiles = listOf(profile(route, "lens:${route.id.value}")),
        )
        return topology(listOf(route), listOf(lens), evidence.toList())
    }

    private fun topology(
        routes: List<CameraRoute>,
        lenses: List<CanonicalLens>,
        evidence: List<CameraMetadataEvidence>,
    ) = CameraTopologySnapshot(
        schema = CameraSchemaVersions.TOPOLOGY,
        environment = CameraEnvironmentFingerprint("checkpoint-d-eligibility"),
        routes = routes,
        canonicalLenses = lenses,
        generatedAtElapsedRealtimeNs = 1L,
        evidence = evidence,
    )

    private fun profile(route: CameraRoute, canonical: String) = CameraProfile(
        fingerprint = CameraProfileFingerprint("profile:${route.id.value}"),
        canonicalFingerprint = CanonicalLensFingerprint(canonical),
        route = route,
    )

    private fun route(
        id: String,
        source: CameraRouteSource,
        openId: String = id,
        physicalId: String? = null,
    ) = CameraRoute(
        id = CameraRouteId(id),
        source = source,
        openCameraId = CameraTransportId(openId),
        physicalCameraId = physicalId?.let(::PhysicalCameraId),
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    type = PreviewStreamType.CAMERA2_PRIVATE,
                    size = IntSize(1280, 720),
                    minimumFrameDurationNs = 33_333_333L,
                ),
            ),
        ),
        metadataTrust = CameraTrust.ADVERTISED,
    )

    private fun evidence(
        id: String,
        source: CameraRouteSource,
        orientation: Int = 90,
        physicalId: String? = null,
        logicalParentId: String? = null,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        physicalId = physicalId?.let(::PhysicalCameraId),
        logicalParentId = logicalParentId?.let(::CameraTransportId),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4f),
        sensorPhysicalWidthMillimetres = 6f,
        sensorPhysicalHeightMillimetres = 4.5f,
        sensorOrientationDegrees = orientation,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1280, 720),
                    33_333_333L,
                ),
            ),
        ),
    )
}
