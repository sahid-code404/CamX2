package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CameraTopologyIdentityTrustTest {
    private val environment = CameraEnvironmentFingerprint("camx-107-identity")

    @Test
    fun `direct public route keeps frozen seed route id contract`() {
        val topology = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("opaque|direct")))
        val expected = "route:${stableHash("opaque|direct|")}"

        assertEquals(expected, topology.routes.single().id.value)
    }

    @Test
    fun `physical route identity is unambiguous when opaque ids contain separators`() {
        val first = evidence("a|b", CameraRouteSource.JAVA_PHYSICAL).copy(
            physicalId = PhysicalCameraId("c"),
            logicalParentId = CameraTransportId("a|b"),
        )
        val second = evidence("a", CameraRouteSource.JAVA_PHYSICAL).copy(
            physicalId = PhysicalCameraId("b|c"),
            logicalParentId = CameraTransportId("a"),
            focalLengthsMillimetres = listOf(8.0f),
        )

        val topology = resolve(snapshot(CameraRouteSource.JAVA_PHYSICAL, first, second))

        assertEquals(2, topology.routes.size)
        assertNotEquals(topology.routes[0].id, topology.routes[1].id)
    }

    @Test
    fun `previous route trust requires matching previous evidence`() {
        val currentEvidence = evidence("trusted")
        val initial = resolve(snapshot(CameraRouteSource.JAVA_PUBLIC, currentEvidence))
        val trustedWithoutEvidence = withRouteTrust(initial).copy(evidence = emptyList())

        val current = CameraTopologyResolver.resolve(
            environment = environment,
            snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, currentEvidence)),
            generatedAtElapsedRealtimeNs = 2L,
            previousTrustedTopology = trustedWithoutEvidence,
        )

        assertEquals(CameraTrust.ADVERTISED, current.routes.single().metadataTrust)
        assertEquals(PreviewTrust.UNKNOWN, current.routes.single().previewTrust)
        assertEquals(RawTrust.UNKNOWN, current.routes.single().rawTrust)
    }

    private fun withRouteTrust(topology: CameraTopologySnapshot): CameraTopologySnapshot {
        val routes = topology.routes.map { route ->
            route.copy(
                metadataTrust = CameraTrust.VERIFIED,
                previewTrust = PreviewTrust.VERIFIED,
                rawTrust = RawTrust.VERIFIED,
            )
        }
        val byId = routes.associateBy { it.id }
        return topology.copy(
            routes = routes,
            canonicalLenses = topology.canonicalLenses.map { lens ->
                lens.copy(
                    profiles = lens.profiles.map { profile ->
                        profile.copy(route = byId.getValue(profile.route.id))
                    },
                )
            },
        )
    }

    private fun resolve(vararg snapshots: CameraEvidenceSnapshot) = CameraTopologyResolver.resolve(
        environment = environment,
        snapshots = snapshots.toList(),
        generatedAtElapsedRealtimeNs = 1L,
    )

    private fun snapshot(
        source: CameraRouteSource,
        vararg values: CameraMetadataEvidence,
    ) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = values.map { it.copy(source = source) },
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun evidence(
        id: String,
        source: CameraRouteSource = CameraRouteSource.JAVA_PUBLIC,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        capabilities = CameraCapabilities(),
    )

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(32) {
            repeat(16) { index -> append("%02x".format(digest[index].toInt() and 0xff)) }
        }
    }
}
