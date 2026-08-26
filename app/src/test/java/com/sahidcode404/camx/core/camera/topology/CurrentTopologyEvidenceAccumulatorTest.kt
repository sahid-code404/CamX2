package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTopologyEvidenceAccumulatorTest {
    private val environment = CameraEnvironmentFingerprint("aux-ab-coalescing")

    @Test
    fun `minimal public then enriched public keeps one current record`() {
        val accumulator = CurrentTopologyEvidenceAccumulator(environment)
        val minimal = publicEvidence("public", enriched = false)
        val enriched = publicEvidence("public", enriched = true)

        assertEquals(EvidenceMergeResult.CHANGED, accumulator.merge(listOf(snapshot(minimal))))
        assertEquals(EvidenceMergeResult.CHANGED, accumulator.merge(listOf(snapshot(enriched))))

        assertEquals(1, accumulator.size)
        assertEquals(enriched, accumulator.snapshots().single().evidence.single())
    }

    @Test
    fun `sparse physical then enriched physical keeps one current record`() {
        val accumulator = CurrentTopologyEvidenceAccumulator(environment)
        val sparse = physicalEvidence("logical", "member", enriched = false)
        val enriched = physicalEvidence("logical", "member", enriched = true)

        accumulator.merge(listOf(snapshot(sparse)))
        accumulator.merge(listOf(snapshot(enriched)))

        assertEquals(1, accumulator.size)
        assertEquals(enriched, accumulator.snapshots().single().evidence.single())
    }

    @Test
    fun `repeated identical publication does not grow evidence`() {
        val accumulator = CurrentTopologyEvidenceAccumulator(environment)
        val evidence = publicEvidence("same", enriched = true)

        assertEquals(EvidenceMergeResult.CHANGED, accumulator.merge(listOf(snapshot(evidence))))
        repeat(20) {
            assertEquals(EvidenceMergeResult.UNCHANGED, accumulator.merge(listOf(snapshot(evidence))))
        }

        assertEquals(1, accumulator.size)
    }

    @Test
    fun `sixty four public plus sixty four physical enrichment stays at one hundred twenty eight current records`() {
        val accumulator = CurrentTopologyEvidenceAccumulator(environment)
        val minimalPublic = (0 until 64).map { publicEvidence("public-$it", enriched = false) }
        val sparsePhysical = (0 until 64).map { physicalEvidence("logical-$it", "member-$it", enriched = false) }
        val enrichedPublic = (0 until 64).map { publicEvidence("public-$it", enriched = true) }
        val enrichedPhysical = (0 until 64).map { physicalEvidence("logical-$it", "member-$it", enriched = true) }

        accumulator.merge(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, minimalPublic)))
        accumulator.merge(listOf(snapshot(CameraRouteSource.JAVA_PHYSICAL, sparsePhysical)))
        accumulator.merge(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, enrichedPublic)))
        accumulator.merge(listOf(snapshot(CameraRouteSource.JAVA_PHYSICAL, enrichedPhysical)))

        assertEquals(128, accumulator.size)
        assertEquals(64, accumulator.snapshots().single { it.source == CameraRouteSource.JAVA_PUBLIC }.evidence.size)
        assertEquals(64, accumulator.snapshots().single { it.source == CameraRouteSource.JAVA_PHYSICAL }.evidence.size)
    }

    @Test
    fun `ndk evidence still has bounded budget after java enrichment`() {
        val accumulator = CurrentTopologyEvidenceAccumulator(environment)
        val javaPublic = (0 until 64).map { publicEvidence("public-$it", enriched = true) }
        val javaPhysical = (0 until 64).map { physicalEvidence("logical-$it", "member-$it", enriched = true) }
        val ndk = (0 until 64).map { index ->
            publicEvidence("ndk-$index", enriched = true).copy(source = CameraRouteSource.NDK_ADVERTISED)
        }

        accumulator.merge(listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, javaPublic)))
        accumulator.merge(listOf(snapshot(CameraRouteSource.JAVA_PHYSICAL, javaPhysical)))
        assertEquals(EvidenceMergeResult.CHANGED, accumulator.merge(listOf(snapshot(CameraRouteSource.NDK_ADVERTISED, ndk))))

        assertEquals(192, accumulator.size)
        assertTrue(accumulator.size < CameraTopologyResolver.MAX_TOTAL_EVIDENCE)
    }

    @Test
    fun `reordered publication produces identical final topology`() {
        val minimal = publicEvidence("route", enriched = false)
        val enriched = publicEvidence("route", enriched = true)
        val sparsePhysical = physicalEvidence("logical", "member", enriched = false)
        val enrichedPhysical = physicalEvidence("logical", "member", enriched = true)
        val ndk = publicEvidence("ndk-route", enriched = true).copy(source = CameraRouteSource.NDK_ADVERTISED)

        val first = CurrentTopologyEvidenceAccumulator(environment)
        listOf(minimal, sparsePhysical, ndk, enriched, enrichedPhysical).forEach { item ->
            first.merge(listOf(snapshot(item)))
        }
        val second = CurrentTopologyEvidenceAccumulator(environment)
        listOf(enrichedPhysical, enriched, ndk, sparsePhysical, minimal).forEach { item ->
            second.merge(listOf(snapshot(item)))
        }

        val firstTopology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = first.snapshots(),
            generatedAtElapsedRealtimeNs = 99L,
        )
        val secondTopology = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = second.snapshots(),
            generatedAtElapsedRealtimeNs = 99L,
        )

        assertEquals(firstTopology, secondTopology)
    }

    private fun publicEvidence(id: String, enriched: Boolean) = CameraMetadataEvidence(
        source = CameraRouteSource.JAVA_PUBLIC,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        apertureValues = if (enriched) listOf(1.8f) else emptyList(),
        colorFilterArrangement = if (enriched) 0 else null,
        capabilities = capabilities(enriched),
    )

    private fun physicalEvidence(parent: String, member: String, enriched: Boolean) = CameraMetadataEvidence(
        source = CameraRouteSource.JAVA_PHYSICAL,
        transportId = CameraTransportId(parent),
        physicalId = PhysicalCameraId(member),
        logicalParentId = CameraTransportId(parent),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = if (enriched) listOf(7.0f) else emptyList(),
        sensorPhysicalWidthMillimetres = if (enriched) 4.0f else null,
        sensorPhysicalHeightMillimetres = if (enriched) 3.0f else null,
        activeArray = if (enriched) IntSize(3000, 2000) else null,
        pixelArray = if (enriched) IntSize(3024, 2016) else null,
        sensorOrientationDegrees = if (enriched) 90 else null,
        apertureValues = if (enriched) listOf(2.0f) else emptyList(),
        colorFilterArrangement = if (enriched) 1 else null,
        capabilities = if (enriched) capabilities(true) else CameraCapabilities(),
    )

    private fun capabilities(enriched: Boolean) = CameraCapabilities(
        previewStreams = listOf(
            CameraStreamCapability(
                type = PreviewStreamType.CAMERA2_PRIVATE,
                size = IntSize(1920, 1080),
                minimumFrameDurationNs = 33_333_333L,
            ),
        ),
        fpsRanges = if (enriched) listOf(CameraFpsCapability(15, 30)) else emptyList(),
        rawSizes = if (enriched) listOf(IntSize(4000, 3000)) else emptyList(),
    )

    private fun snapshot(item: CameraMetadataEvidence) = snapshot(item.source, listOf(item))

    private fun snapshot(
        source: CameraRouteSource,
        evidence: List<CameraMetadataEvidence>,
    ) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence,
        completedAtElapsedRealtimeNs = 1L,
    )
}
