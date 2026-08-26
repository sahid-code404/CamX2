package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaDeepTopologyEligibilityTest {
    private val environment = CameraEnvironmentFingerprint("java-deep-topology")

    @Test
    fun `NDK deep evidence alone is retained but cannot create selectable route`() {
        val topology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(ndkDeep("hidden"))),
            generatedAtElapsedRealtimeNs = 1L,
        )

        assertTrue(topology.routes.isEmpty())
        assertTrue(topology.canonicalLenses.isEmpty())
        assertEquals(CameraRouteSource.NDK_DEEP, topology.evidence.single().source)
    }

    @Test
    fun `Java deep certification plus NDK deep evidence creates one unverified route`() {
        val topology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(ndkDeep("hidden")), snapshot(javaDeep("hidden"))),
            generatedAtElapsedRealtimeNs = 2L,
        )

        val route = topology.routes.single()
        assertEquals(CameraRouteSource.JAVA_DEEP_PROBED, route.source)
        assertEquals(setOf(CameraRouteSource.JAVA_DEEP_PROBED, CameraRouteSource.NDK_DEEP), route.sources)
        assertEquals(CameraTrust.ADVERTISED, route.metadataTrust)
        assertEquals(PreviewTrust.UNKNOWN, route.previewTrust)
        assertEquals("hidden", route.openCameraId.value)
        assertEquals(1, topology.canonicalLenses.size)
    }

    @Test
    fun `malformed Java deep evidence without private preview cannot create route`() {
        val malformed = javaDeep("hidden").copy(
            capabilities = CameraCapabilities(fpsRanges = listOf(CameraFpsCapability(30, 30))),
        )
        val topology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(ndkDeep("hidden")), snapshot(malformed)),
            generatedAtElapsedRealtimeNs = 3L,
        )

        assertTrue(topology.routes.isEmpty())
    }

    @Test
    fun `known Java public route plus NDK deep enrichment remains one route`() {
        val public = javaDeep("known").copy(source = CameraRouteSource.JAVA_PUBLIC)
        val topology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(public), snapshot(ndkDeep("known"))),
            generatedAtElapsedRealtimeNs = 4L,
        )

        assertEquals(1, topology.routes.size)
        assertEquals(CameraRouteSource.JAVA_PUBLIC, topology.routes.single().source)
        assertEquals(
            setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.NDK_DEEP),
            topology.routes.single().sources,
        )
    }

    @Test
    fun `known Java physical member prevents NDK deep direct duplicate route`() {
        val physical = javaDeep("logical-parent").copy(
            source = CameraRouteSource.JAVA_PHYSICAL,
            physicalId = PhysicalCameraId("hidden-child"),
            logicalParentId = CameraTransportId("logical-parent"),
        )
        val topology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(physical), snapshot(ndkDeep("hidden-child"))),
            generatedAtElapsedRealtimeNs = 5L,
        )

        assertEquals(1, topology.routes.size)
        assertEquals("logical-parent", topology.routes.single().openCameraId.value)
        assertEquals("hidden-child", topology.routes.single().physicalCameraId?.value)
    }

    private fun snapshot(evidence: CameraMetadataEvidence) = CameraEvidenceSnapshot(
        source = evidence.source,
        environment = environment,
        evidence = listOf(evidence),
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun javaDeep(id: String) = CameraMetadataEvidence(
        source = CameraRouteSource.JAVA_DEEP_PROBED,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1280, 720),
                    33_333_333L,
                ),
            ),
            fpsRanges = listOf(CameraFpsCapability(30, 30)),
        ),
    )

    private fun ndkDeep(id: String) = javaDeep(id).copy(
        source = CameraRouteSource.NDK_DEEP,
        capabilities = CameraCapabilities(
            previewStreams = javaDeep(id).capabilities.previewStreams,
        ),
    )
}
