package com.sahidcode404.camx.core.camera.bootstrap

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
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity4TopologyMigrationInventoryTest {
    private val environment = CameraEnvironmentFingerprint("parity4-migration")

    @Test
    fun legacyTopologyNeverSeedsUiAndFreshCurrentTopologyPublishesOnceThenWarmsImmediately() {
        val coherent = topology(
            schema = CameraSchemaVersions.TOPOLOGY,
            Spec("ultra", 2.5f),
            Spec("main", 5f),
            Spec("tele", 10f),
            Spec("front", 3f, LensFacing.FRONT),
        )
        val legacy = coherent.copy(schema = 1)
        val migrationLaunch = LensInventoryCoordinator(environment, runtimeApiLevel = 35)

        val rejected = migrationLaunch.acceptCompatibleCache(
            snapshot = legacy,
            persistedReference = CanonicalLensFingerprint("lens:main"),
        )
        assertFalse(rejected.structuralPublished)
        assertNull(migrationLaunch.topology.value)
        assertEquals(LensInventoryReadiness.DISCOVERING_INITIAL, migrationLaunch.status.value.readiness)
        assertEquals(0L, migrationLaunch.status.value.structuralPublicationCount)

        migrationLaunch.observeCandidate(
            topology(CameraSchemaVersions.TOPOLOGY, Spec("main", 5f)),
        )
        assertNull(migrationLaunch.topology.value)
        migrationLaunch.observeCandidate(
            topology(
                CameraSchemaVersions.TOPOLOGY,
                Spec("ultra", 2.5f),
                Spec("main", 5f),
            ),
        )
        assertNull(migrationLaunch.topology.value)
        migrationLaunch.observeCandidate(coherent)
        assertNull(migrationLaunch.topology.value)

        val completion = migrationLaunch.completeAutomaticReconciliation()
        assertTrue(completion.structuralPublished)
        assertEquals(coherent, completion.topologyToPersist)
        assertEquals(4, migrationLaunch.topology.value?.canonicalLenses?.size)
        assertEquals(LensInventoryReadiness.READY, migrationLaunch.status.value.readiness)
        assertEquals(LensInventorySource.INITIAL_RECONCILIATION, migrationLaunch.status.value.source)
        assertEquals(1L, migrationLaunch.status.value.structuralPublicationCount)
        assertEquals(CanonicalLensFingerprint("lens:main"), migrationLaunch.stableOneXReference.value)

        val persisted = requireNotNull(completion.topologyToPersist)
        assertEquals(CameraSchemaVersions.TOPOLOGY, persisted.schema)
        val secondLaunch = LensInventoryCoordinator(environment, runtimeApiLevel = 35)
        val warm = secondLaunch.acceptCompatibleCache(
            snapshot = persisted,
            persistedReference = completion.referenceToPersist?.canonicalFingerprint,
        )

        assertTrue(warm.structuralPublished)
        assertEquals(LensInventoryReadiness.READY, secondLaunch.status.value.readiness)
        assertEquals(LensInventorySource.CACHE, secondLaunch.status.value.source)
        assertEquals(4, secondLaunch.topology.value?.canonicalLenses?.size)
        assertEquals(1L, secondLaunch.status.value.structuralPublicationCount)
        assertEquals(CanonicalLensFingerprint("lens:main"), secondLaunch.stableOneXReference.value)
    }

    private data class Spec(
        val name: String,
        val focal: Float,
        val facing: LensFacing = LensFacing.BACK,
    )

    private fun topology(schema: Int, vararg specs: Spec): CameraTopologySnapshot {
        val profiles = specs.map { spec ->
            val route = CameraRoute(
                id = CameraRouteId("route:${spec.name}"),
                source = CameraRouteSource.JAVA_PUBLIC,
                openCameraId = CameraTransportId("transport:${spec.name}"),
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
            CameraProfile(
                fingerprint = CameraProfileFingerprint("profile:${spec.name}"),
                canonicalFingerprint = CanonicalLensFingerprint("lens:${spec.name}"),
                route = route,
            )
        }
        val lenses = profiles.mapIndexed { index, profile ->
            CanonicalLens(
                fingerprint = profile.canonicalFingerprint,
                facing = specs[index].facing,
                profiles = listOf(profile),
            )
        }
        val evidence = specs.mapIndexed { index, spec ->
            CameraMetadataEvidence(
                source = CameraRouteSource.JAVA_PUBLIC,
                transportId = profiles[index].route.openCameraId,
                facing = spec.facing,
                focalLengthsMillimetres = listOf(spec.focal),
                sensorPhysicalWidthMillimetres = 6f,
                sensorPhysicalHeightMillimetres = 4.5f,
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4000, 3000),
                sensorOrientationDegrees = 90,
                capabilities = profiles[index].route.capabilities,
            )
        }
        return CameraTopologySnapshot(
            schema = schema,
            environment = environment,
            routes = profiles.map { it.route },
            canonicalLenses = lenses,
            generatedAtElapsedRealtimeNs = 1L,
            evidence = evidence,
        )
    }
}
