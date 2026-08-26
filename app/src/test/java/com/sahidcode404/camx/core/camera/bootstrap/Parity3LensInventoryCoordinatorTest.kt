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

class Parity3LensInventoryCoordinatorTest {
    private val environment = CameraEnvironmentFingerprint("parity3-inventory")

    @Test
    fun compatibleCachePublishesCompleteKnownInventoryOnce() {
        val cached = topology(
            Spec("ultra", 2.5f),
            Spec("main", 5f),
            Spec("tele", 10f),
            Spec("front", 3f, LensFacing.FRONT),
        )
        val inventory = LensInventoryCoordinator(environment, runtimeApiLevel = 35)

        val completion = inventory.acceptCompatibleCache(cached, persistedReference = null)

        assertTrue(completion.structuralPublished)
        assertEquals(4, inventory.topology.value?.canonicalLenses?.size)
        assertEquals(LensInventoryReadiness.READY, inventory.status.value.readiness)
        assertEquals(LensInventorySource.CACHE, inventory.status.value.source)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)
        assertEquals(CanonicalLensFingerprint("lens:main"), inventory.stableOneXReference.value)
    }

    @Test
    fun firstInstallHidesIncrementalCandidatesUntilOneCoherentCompletion() {
        val inventory = LensInventoryCoordinator(environment, runtimeApiLevel = 35)
        inventory.observeCandidate(topology(Spec("main", 5f)))
        assertNull(inventory.topology.value)
        inventory.observeCandidate(topology(Spec("ultra", 2.5f), Spec("main", 5f)))
        assertNull(inventory.topology.value)
        val complete = topology(
            Spec("ultra", 2.5f),
            Spec("main", 5f),
            Spec("front", 3f, LensFacing.FRONT),
        )
        inventory.observeCandidate(complete)

        val completion = inventory.completeAutomaticReconciliation()

        assertTrue(completion.structuralPublished)
        assertEquals(3, inventory.topology.value?.canonicalLenses?.size)
        assertEquals(LensInventorySource.INITIAL_RECONCILIATION, inventory.status.value.source)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)
    }

    @Test
    fun warmBackgroundReconciliationPersistsLatestWithoutStructuralUiChurn() {
        val cached = topology(
            Spec("ultra", 2.5f),
            Spec("main", 5f),
            Spec("front", 3f, LensFacing.FRONT),
        )
        val inventory = LensInventoryCoordinator(environment, runtimeApiLevel = 35)
        inventory.acceptCompatibleCache(cached, CanonicalLensFingerprint("lens:main"))
        val before = inventory.topology.value
        val refreshed = topology(
            Spec("front", 3f, LensFacing.FRONT),
            Spec("main", 5f),
            Spec("ultra", 2.5f),
        )
        inventory.observeCandidate(refreshed)

        val completion = inventory.completeAutomaticReconciliation()

        assertFalse(completion.structuralPublished)
        assertEquals(before, inventory.topology.value)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)
        assertEquals(refreshed, completion.topologyToPersist)
        assertEquals(CanonicalLensFingerprint("lens:main"), completion.referenceToPersist?.canonicalFingerprint)
    }

    @Test
    fun persistedValidCanonicalReferenceWinsAndMissingReferenceReelectsDeterministically() {
        val cached = topology(
            Spec("ultra", 2.5f),
            Spec("main", 5f),
            Spec("tele", 10f),
        )
        val persistedTele = LensInventoryCoordinator(environment, runtimeApiLevel = 35)
        persistedTele.acceptCompatibleCache(cached, CanonicalLensFingerprint("lens:tele"))
        assertEquals(CanonicalLensFingerprint("lens:tele"), persistedTele.stableOneXReference.value)

        val missing = LensInventoryCoordinator(environment, runtimeApiLevel = 35)
        missing.acceptCompatibleCache(cached, CanonicalLensFingerprint("lens:missing"))
        assertEquals(CanonicalLensFingerprint("lens:main"), missing.stableOneXReference.value)
    }

    private data class Spec(
        val name: String,
        val focal: Float,
        val facing: LensFacing = LensFacing.BACK,
    )

    private fun topology(vararg specs: Spec): CameraTopologySnapshot {
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
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = environment,
            routes = profiles.map { it.route },
            canonicalLenses = lenses,
            generatedAtElapsedRealtimeNs = 1L,
            evidence = evidence,
        )
    }
}
