package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
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
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaDeepTopologyCacheTest {
    @Test
    fun `topology cache preserves explicit Java deep provenance and five source bound`() {
        val environment = CameraEnvironmentFingerprint("java-deep-cache")
        val capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1280, 720),
                    33_333_333L,
                ),
            ),
            fpsRanges = listOf(CameraFpsCapability(30, 30)),
        )
        val route = CameraRoute(
            id = CameraRouteId("route:deep"),
            source = CameraRouteSource.JAVA_DEEP_PROBED,
            openCameraId = CameraTransportId("opaque-hidden"),
            capabilities = capabilities,
            metadataTrust = CameraTrust.ADVERTISED,
            sources = CameraRouteSource.values().toSet(),
        )
        val lensId = CanonicalLensFingerprint("lens:deep")
        val profile = CameraProfile(
            fingerprint = CameraProfileFingerprint("profile:deep"),
            canonicalFingerprint = lensId,
            route = route,
        )
        val snapshot = CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = environment,
            routes = listOf(route),
            canonicalLenses = listOf(CanonicalLens(lensId, LensFacing.BACK, listOf(profile))),
            generatedAtElapsedRealtimeNs = 5L,
            evidence = listOf(
                CameraMetadataEvidence(
                    source = CameraRouteSource.JAVA_DEEP_PROBED,
                    transportId = CameraTransportId("opaque-hidden"),
                    facing = LensFacing.BACK,
                    focalLengthsMillimetres = listOf(4.2f),
                    sensorOrientationDegrees = 90,
                    capabilities = capabilities,
                ),
            ),
        )

        val decoded = TopologyCacheCodec.decode(TopologyCacheCodec.encode(snapshot), environment)

        val hit = decoded as CacheRead.Hit<CameraTopologySnapshot>
        assertEquals(CameraRouteSource.JAVA_DEEP_PROBED, hit.value.routes.single().source)
        assertEquals(CameraRouteSource.values().toSet(), hit.value.routes.single().sources)
        assertTrue(CameraRouteSource.JAVA_DEEP_PROBED in hit.value.evidence.map { it.source })
    }
}
