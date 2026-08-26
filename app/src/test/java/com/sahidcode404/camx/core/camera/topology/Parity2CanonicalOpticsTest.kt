package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity2CanonicalOpticsTest {
    private val environment = CameraEnvironmentFingerprint("parity2-optics")

    @Test
    fun `public deep and physical aliases keep one stable optical fingerprint`() {
        val public = evidence("0", CameraRouteSource.JAVA_PUBLIC)
        val deep = evidence("100", CameraRouteSource.JAVA_DEEP_PROBED)
        val physical = evidence("61", CameraRouteSource.JAVA_PHYSICAL).copy(
            physicalId = PhysicalCameraId("0"),
            logicalParentId = CameraTransportId("61"),
        )
        val publicOnly = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, public))
        val aliases = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, public),
            snapshot(CameraRouteSource.JAVA_DEEP_PROBED, deep),
            snapshot(CameraRouteSource.JAVA_PHYSICAL, physical),
        )
        assertEquals(3, aliases.routes.size)
        assertEquals(3, aliases.canonicalLenses.single().profiles.size)
        assertEquals(publicOnly.canonicalLenses.single().fingerprint, aliases.canonicalLenses.single().fingerprint)
        assertTrue(aliases.canonicalLenses.single().fingerprint.value.startsWith("lens:optical:"))
    }

    @Test
    fun `evidence permutation and verified sibling do not change canonical fingerprint`() {
        val public = evidence("main", CameraRouteSource.JAVA_PUBLIC)
        val deep = evidence("hidden", CameraRouteSource.JAVA_DEEP_PROBED)
        val forwardSnapshots = listOf(
            snapshot(CameraRouteSource.JAVA_PUBLIC, public),
            snapshot(CameraRouteSource.JAVA_DEEP_PROBED, deep),
        )
        val forward = resolve(*forwardSnapshots.toTypedArray())
        val reverse = resolve(*forwardSnapshots.reversed().toTypedArray())
        assertEquals(forward.canonicalLenses.single().fingerprint, reverse.canonicalLenses.single().fingerprint)

        val trusted = withVerifiedPreview(forward, "hidden")
        val afterTrust = resolve(*forwardSnapshots.toTypedArray(), previous = trusted)
        assertEquals(forward.canonicalLenses.single().fingerprint, afterTrust.canonicalLenses.single().fingerprint)
        assertEquals(PreviewTrust.VERIFIED, afterTrust.routes.single { it.openCameraId.value == "hidden" }.previewTrust)
    }

    @Test
    fun `canonical metadata is permutation invariant and tolerates minor provider drift`() {
        val java = evidence("same", CameraRouteSource.JAVA_PUBLIC).copy(
            sensorPhysicalWidthMillimetres = 5.60001f,
            activeArray = IntSize(4000, 3000),
        )
        val ndk = evidence("same", CameraRouteSource.NDK_ADVERTISED).copy(
            sensorPhysicalWidthMillimetres = 5.60002f,
            activeArray = IntSize(3990, 2990),
        )
        val forward = resolve(
            snapshot(CameraRouteSource.JAVA_PUBLIC, java),
            snapshot(CameraRouteSource.NDK_ADVERTISED, ndk),
        )
        val reverse = resolve(
            snapshot(CameraRouteSource.NDK_ADVERTISED, ndk),
            snapshot(CameraRouteSource.JAVA_PUBLIC, java),
        )
        val forwardMetadata = CanonicalLensOptics.resolve(forward, forward.canonicalLenses.single())
        val reverseMetadata = CanonicalLensOptics.resolve(reverse, reverse.canonicalLenses.single())
        assertEquals(forward.canonicalLenses.single().fingerprint, reverse.canonicalLenses.single().fingerprint)
        assertEquals(forwardMetadata, reverseMetadata)
        assertEquals(IntSize(4000, 3000), forwardMetadata.activeArray)
    }

    @Test
    fun `meaningfully different sensor and CFA remain distinct`() {
        val sensorDifferent = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence("sensor-a", CameraRouteSource.JAVA_PUBLIC, sensorWidth = 5.6f),
                evidence("sensor-b", CameraRouteSource.JAVA_PUBLIC, sensorWidth = 6.4f),
            ),
        )
        assertEquals(2, sensorDifferent.canonicalLenses.size)
        assertEquals(2, sensorDifferent.canonicalLenses.map { it.fingerprint }.distinct().size)

        val cfaDifferent = resolve(
            snapshot(
                CameraRouteSource.JAVA_PUBLIC,
                evidence("cfa-a", CameraRouteSource.JAVA_PUBLIC, cfa = 0),
                evidence("cfa-b", CameraRouteSource.JAVA_PUBLIC, cfa = 1),
            ),
        )
        assertEquals(2, cfaDifferent.canonicalLenses.size)
        assertNotEquals(cfaDifferent.canonicalLenses[0].fingerprint, cfaDifferent.canonicalLenses[1].fingerprint)
    }

    @Test
    fun `different authoritative physical members remain separate even with identical optics`() {
        val first = evidence("logical", CameraRouteSource.JAVA_PHYSICAL).copy(
            physicalId = PhysicalCameraId("member-a"),
            logicalParentId = CameraTransportId("logical"),
        )
        val second = evidence("logical", CameraRouteSource.JAVA_PHYSICAL).copy(
            physicalId = PhysicalCameraId("member-b"),
            logicalParentId = CameraTransportId("logical"),
        )
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PHYSICAL, first, second))
        assertEquals(2, topology.routes.size)
        assertEquals(2, topology.canonicalLenses.size)
        assertEquals(2, topology.canonicalLenses.map { it.fingerprint }.distinct().size)
        assertTrue(topology.canonicalLenses.all { it.fingerprint.value.startsWith("lens:fallback:") })
    }

    @Test
    fun `sparse uncertain profiles use distinct device scoped fallbacks`() {
        val first = sparse("unknown-a")
        val second = sparse("unknown-b")
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, first, second))
        assertEquals(2, topology.canonicalLenses.size)
        assertEquals(2, topology.canonicalLenses.map { it.fingerprint }.distinct().size)
        assertTrue(topology.canonicalLenses.all { it.fingerprint.value.startsWith("lens:fallback:") })
    }

    private fun withVerifiedPreview(topology: CameraTopologySnapshot, transportId: String): CameraTopologySnapshot {
        val routes = topology.routes.map { route ->
            if (route.openCameraId.value == transportId) route.copy(
                metadataTrust = CameraTrust.VERIFIED,
                previewTrust = PreviewTrust.VERIFIED,
            ) else route
        }
        val byId = routes.associateBy { it.id }
        return topology.copy(
            routes = routes,
            canonicalLenses = topology.canonicalLenses.map { lens ->
                lens.copy(profiles = lens.profiles.map { profile ->
                    profile.copy(route = byId.getValue(profile.route.id))
                })
            },
        )
    }

    private fun resolve(
        vararg snapshots: CameraEvidenceSnapshot,
        previous: CameraTopologySnapshot? = null,
    ) = CameraTopologyResolver.resolve(
        environment = environment,
        snapshots = snapshots.toList(),
        generatedAtElapsedRealtimeNs = 100L,
        previousTrustedTopology = previous,
    )

    private fun snapshot(source: CameraRouteSource, vararg evidence: CameraMetadataEvidence) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence.map { it.copy(source = source) },
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun evidence(
        id: String,
        source: CameraRouteSource,
        sensorWidth: Float = 5.6f,
        cfa: Int = 0,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = sensorWidth,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        apertureValues = listOf(1.8f),
        colorFilterArrangement = cfa,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(1280, 720), 33_333_333L),
            ),
            fpsRanges = listOf(CameraFpsCapability(30, 30)),
        ),
    )

    private fun sparse(id: String) = CameraMetadataEvidence(
        source = CameraRouteSource.JAVA_PUBLIC,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        capabilities = CameraCapabilities(),
    )
}
