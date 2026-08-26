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
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CamexOpticalParityTest {
    private val environment = CameraEnvironmentFingerprint("camex-parity")

    @Test
    fun `main vendor aliases become one lens with three exact profiles`() {
        val public = complete("0", 5.15f)
        val deep = complete("100", 5.15f)
        val physical = complete("61", 5.15f).copy(
            source = CameraRouteSource.JAVA_PHYSICAL,
            physicalId = PhysicalCameraId("0"),
            logicalParentId = CameraTransportId("61"),
        )
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(public)),
            snapshot(CameraRouteSource.NDK_DEEP, listOf(deep)),
            snapshot(CameraRouteSource.JAVA_DEEP_PROBED, listOf(deep)),
            snapshot(CameraRouteSource.JAVA_PHYSICAL, listOf(physical)),
        )

        assertEquals(3, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(3, topology.canonicalLenses.single().profiles.size)
    }

    @Test
    fun `front public and deep aliases become one lens with two profiles`() {
        val front = complete("front-public", 3.7f, LensFacing.FRONT)
        val alias = complete("front-deep", 3.7f, LensFacing.FRONT)
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(front)),
            snapshot(CameraRouteSource.NDK_DEEP, listOf(alias)),
            snapshot(CameraRouteSource.JAVA_DEEP_PROBED, listOf(alias)),
        )

        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(2, topology.canonicalLenses.single().profiles.size)
        assertEquals(LensFacing.FRONT, topology.canonicalLenses.single().facing)
    }

    @Test
    fun `different public transport IDs with strong optics group`() {
        val topology = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                listOf(complete("public-a", 4.70f), complete("public-b", 4.72f)),
            ),
        )
        assertEquals(2, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(2, topology.canonicalLenses.single().profiles.size)
    }

    @Test
    fun `same exact transport merges provider observations before optical grouping`() {
        val topology = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("same-route", 4.70f))),
            snapshot(CameraRouteSource.NDK_ADVERTISED, listOf(complete("same-route", 4.72f))),
        )
        assertEquals(1, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(1, topology.canonicalLenses.single().profiles.size)
        assertEquals(
            setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.NDK_ADVERTISED),
            topology.routes.single().sources,
        )
    }

    @Test
    fun `same resolution with meaningfully different focal stays separate`() {
        val topology = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                listOf(complete("wide", 4.0f), complete("other", 4.2f)),
            ),
        )
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun `same focal with meaningfully different sensor dimensions stays separate`() {
        val a = complete("sensor-a", 5.0f)
        val b = complete("sensor-b", 5.0f).copy(
            sensorPhysicalWidthMillimetres = 5.6f,
            sensorPhysicalHeightMillimetres = 4.2f,
        )
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(a, b)))
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun `different authoritative CFA stays separate`() {
        val a = complete("cfa-a", 5.0f).copy(colorFilterArrangement = 0)
        val b = complete("cfa-b", 5.0f).copy(colorFilterArrangement = 1)
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(a, b)))
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun `sparse focal only metadata is insufficient and stays separate`() {
        val a = CameraMetadataEvidence(
            source = CameraRouteSource.JAVA_PUBLIC,
            transportId = CameraTransportId("sparse-a"),
            facing = LensFacing.BACK,
            focalLengthsMillimetres = listOf(5.0f),
        )
        val b = a.copy(transportId = CameraTransportId("sparse-b"))
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(a, b)))
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun `different physical members of same logical parent remain separate despite cloned metadata`() {
        val left = complete("logical", 4.72f).copy(
            source = CameraRouteSource.JAVA_PHYSICAL,
            physicalId = PhysicalCameraId("member-a"),
            logicalParentId = CameraTransportId("logical"),
        )
        val right = left.copy(physicalId = PhysicalCameraId("member-b"))
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PHYSICAL, listOf(left, right)))
        assertEquals(2, topology.routes.size)
        assertEquals(2, topology.canonicalLenses.size)
        val comparison = OpticalLensMatcher.compare(listOf(left), listOf(right))
        assertEquals(OpticalLensMatch.CONFLICT, comparison.match)
    }

    @Test
    fun `geometry fields remain one corroborating family`() {
        val a = complete("geometry-a", 4.72f)
        val b = complete("geometry-b", 4.72f)
        val comparison = OpticalLensMatcher.compare(listOf(a), listOf(b))
        assertEquals(OpticalLensMatch.STRONG_MATCH, comparison.match)
        assertEquals(
            setOf(OpticalEvidenceFamily.OPTICAL, OpticalEvidenceFamily.SENSOR, OpticalEvidenceFamily.GEOMETRY),
            comparison.evidenceFamilies,
        )
        assertEquals(3, comparison.evidenceCount)
    }

    @Test
    fun `complete link prevents transitive alias chain collapse`() {
        val a = complete("a", 4.70f)
        val b = complete("b", 4.75f)
        val c = complete("c", 4.80f)
        assertEquals(OpticalLensMatch.STRONG_MATCH, OpticalLensMatcher.compare(listOf(a), listOf(b)).match)
        assertEquals(OpticalLensMatch.STRONG_MATCH, OpticalLensMatcher.compare(listOf(b), listOf(c)).match)
        assertNotEquals(OpticalLensMatch.STRONG_MATCH, OpticalLensMatcher.compare(listOf(a), listOf(c)).match)

        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(a, b, c)))
        assertEquals(2, topology.canonicalLenses.size)
        assertEquals(listOf(1, 2), topology.canonicalLenses.map { it.profiles.size }.sorted())
    }

    @Test
    fun `three strong aliases become one canonical lens`() {
        val topology = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                listOf(complete("alias-a", 4.72f), complete("alias-b", 4.72f), complete("alias-c", 4.72f)),
            ),
        )
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(3, topology.canonicalLenses.single().profiles.size)
    }

    private fun resolve(vararg snapshots: CameraEvidenceSnapshot) = CameraTopologyResolver.resolve(
        environment = environment,
        snapshots = snapshots.toList(),
        generatedAtElapsedRealtimeNs = 10L,
    )

    private fun complete(
        id: String,
        focal: Float,
        facing: LensFacing = LensFacing.BACK,
    ) = CameraMetadataEvidence(
        source = CameraRouteSource.JAVA_PUBLIC,
        transportId = CameraTransportId(id),
        facing = facing,
        focalLengthsMillimetres = listOf(focal),
        sensorPhysicalWidthMillimetres = 7.2f,
        sensorPhysicalHeightMillimetres = 5.4f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4000, 3000),
        sensorOrientationDegrees = 90,
        apertureValues = listOf(1.8f),
        colorFilterArrangement = 0,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1920, 1080),
                    33_333_333L,
                ),
            ),
            fpsRanges = listOf(CameraFpsCapability(30, 30)),
            rawSizes = listOf(IntSize(4000, 3000)),
        ),
    )

    private fun snapshot(
        source: CameraRouteSource,
        evidence: List<CameraMetadataEvidence>,
    ) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence.map { it.copy(source = source) },
        completedAtElapsedRealtimeNs = 5L,
    )
}
