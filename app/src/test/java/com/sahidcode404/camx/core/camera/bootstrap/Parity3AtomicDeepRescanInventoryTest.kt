package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.lens.CameraLensProjectionInput
import com.sahidcode404.camx.core.camera.lens.CameraLensUiProjector
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
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
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity3AtomicDeepRescanInventoryTest {
    private val environment = CameraEnvironmentFingerprint("parity3c-inventory")

    @Test
    fun `explicit rescan preserves published strip through every intermediate candidate`() {
        val inventory = ready(standard())
        val published = inventory.topology.value
        val reference = inventory.stableOneXReference.value
        val generation = checkNotNull(inventory.beginExplicitRescan())

        listOf(
            topology(Spec("main", 5f)),
            topology(Spec("ultra", 2.5f), Spec("main", 5f)),
            topology(
                Spec("ultra", 2.5f),
                Spec("main", 5f),
                Spec("candidate", 8f, source = CameraRouteSource.JAVA_DEEP_PROBED),
            ),
        ).forEach { intermediate ->
            inventory.observeCandidate(intermediate)
            assertSame(published, inventory.topology.value)
            assertEquals(reference, inventory.stableOneXReference.value)
            assertEquals(1L, inventory.status.value.structuralPublicationCount)
            assertEquals(LensInventoryReadiness.REFRESH_PENDING, inventory.status.value.readiness)
        }

        inventory.cancelExplicitRescan(generation)
        assertSame(published, inventory.topology.value)
        assertEquals(LensInventoryRefreshOutcome.FAILED_OR_CANCELLED, inventory.status.value.lastRefreshOutcome)
    }

    @Test
    fun `equivalent rescan aliases profile changes and trust changes produce zero structural replacement`() {
        val inventory = ready(standard())
        val before = inventory.topology.value
        val beforeLabels = labels(inventory)
        val beforeReference = inventory.stableOneXReference.value
        val generation = checkNotNull(inventory.beginExplicitRescan())
        val equivalent = topology(
            Spec("front", 3f, facing = LensFacing.FRONT),
            Spec("main", 5f, profile = "main-deep", source = CameraRouteSource.JAVA_DEEP_PROBED),
            Spec("ultra", 2.5f),
            Spec(
                "main",
                5f,
                profile = "main-public",
                metadataTrust = CameraTrust.VERIFIED,
                previewTrust = PreviewTrust.VERIFIED,
            ),
            Spec("front", 3f, profile = "front-alias", facing = LensFacing.FRONT),
        )
        inventory.observeCandidate(equivalent)

        val completion = inventory.completeExplicitRescan(generation, coherent = true, finalSnapshot = equivalent)

        assertFalse(completion.structuralPublished)
        assertSame(before, inventory.topology.value)
        assertEquals(beforeLabels, labels(inventory))
        assertEquals(beforeReference, inventory.stableOneXReference.value)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)
        assertEquals(LensInventoryRefreshOutcome.NO_CHANGE, inventory.status.value.lastRefreshOutcome)
        assertEquals(equivalent, completion.topologyToPersist)
        assertEquals(1, projection(inventory).items.count { it.facing == LensFacing.FRONT })
    }

    @Test
    fun `one new canonical lens publishes one atomic replacement and records explicit metrics`() {
        var now = 1_000_000_000L
        val initial = topology(
            Spec("main", 5f),
            Spec("front", 3f, facing = LensFacing.FRONT),
        )
        val inventory = LensInventoryCoordinator(environment, 35) { now }
        inventory.acceptCompatibleCache(initial, CanonicalLensFingerprint("lens:main"))
        val generation = checkNotNull(inventory.beginExplicitRescan())
        now += 19_000_000L
        val material = topology(
            Spec("main", 5f),
            Spec("tele", 10f),
            Spec("front", 3f, facing = LensFacing.FRONT),
        )
        inventory.observeCandidate(topology(Spec("main", 5f)))
        assertEquals(2, inventory.topology.value!!.canonicalLenses.size)

        val completion = inventory.completeExplicitRescan(generation, coherent = true, finalSnapshot = material)

        assertTrue(completion.structuralPublished)
        assertEquals(3, inventory.topology.value!!.canonicalLenses.size)
        assertEquals(2L, inventory.status.value.structuralPublicationCount)
        assertEquals(LensInventorySource.EXPLICIT_RESCAN, inventory.status.value.source)
        assertEquals(LensInventoryRefreshOutcome.REPLACED, inventory.status.value.lastRefreshOutcome)
        assertEquals(19L, inventory.status.value.lastStructuralReplacementLatencyMs)
        assertEquals(19L, inventory.status.value.lastRefreshCompletionLatencyMs)
        assertEquals(CanonicalLensFingerprint("lens:main"), inventory.stableOneXReference.value)
    }

    @Test
    fun `new deep alias of existing canonical lens never creates a new button`() {
        val initial = topology(Spec("main", 5f), Spec("front", 3f, facing = LensFacing.FRONT))
        val inventory = ready(initial)
        val generation = checkNotNull(inventory.beginExplicitRescan())
        val alias = topology(
            Spec("main", 5f, profile = "main-public"),
            Spec("main", 5f, profile = "main-deep", source = CameraRouteSource.JAVA_DEEP_PROBED),
            Spec("front", 3f, facing = LensFacing.FRONT),
        )

        val completion = inventory.completeExplicitRescan(generation, coherent = true, finalSnapshot = alias)

        assertFalse(completion.structuralPublished)
        assertEquals(2, projection(inventory).items.size)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)
    }

    @Test
    fun `failed rescan preserves inventory selected identity and oneX`() {
        val inventory = ready(standard())
        val before = inventory.topology.value
        val beforeReference = inventory.stableOneXReference.value
        val generation = checkNotNull(inventory.beginExplicitRescan())
        inventory.observeCandidate(topology(Spec("main", 5f)))

        val completion = inventory.completeExplicitRescan(generation, coherent = false, finalSnapshot = null)

        assertFalse(completion.structuralPublished)
        assertSame(before, inventory.topology.value)
        assertEquals(beforeReference, inventory.stableOneXReference.value)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)
        assertEquals(LensInventoryReadiness.READY, inventory.status.value.readiness)
        assertEquals(LensInventoryRefreshOutcome.FAILED_OR_CANCELLED, inventory.status.value.lastRefreshOutcome)
    }

    @Test
    fun `cancelled stale generation cannot replace a newer inventory refresh`() {
        val inventory = ready(topology(Spec("main", 5f)))
        val first = checkNotNull(inventory.beginExplicitRescan())
        inventory.cancelExplicitRescan(first)
        val second = checkNotNull(inventory.beginExplicitRescan())
        val late = topology(Spec("main", 5f), Spec("tele", 10f))

        val stale = inventory.completeExplicitRescan(first, coherent = true, finalSnapshot = late)
        assertFalse(stale.structuralPublished)
        assertEquals(1, inventory.topology.value!!.canonicalLenses.size)
        assertEquals(LensInventoryReadiness.REFRESH_PENDING, inventory.status.value.readiness)

        val current = inventory.completeExplicitRescan(second, coherent = true, finalSnapshot = late)
        assertTrue(current.structuralPublished)
        assertEquals(2, inventory.topology.value!!.canonicalLenses.size)
        assertEquals(2L, inventory.status.value.structuralPublicationCount)
    }

    @Test
    fun `active canonical remains selected after atomic addition when its route survives`() {
        val initial = topology(Spec("main", 5f), Spec("front", 3f, facing = LensFacing.FRONT))
        val inventory = ready(initial)
        val generation = checkNotNull(inventory.beginExplicitRescan())
        val material = topology(
            Spec("main", 5f),
            Spec("macro", 8f),
            Spec("front", 3f, facing = LensFacing.FRONT),
        )
        inventory.completeExplicitRescan(generation, coherent = true, finalSnapshot = material)
        val main = CanonicalLensFingerprint("lens:main")
        val active = ActiveCameraSelection(
            canonicalLensFingerprint = main,
            profileFingerprint = CameraProfileFingerprint("profile:main"),
            routeId = CameraRouteId("route:main"),
            selectionGeneration = SelectionGeneration(1),
            sessionGeneration = SessionGeneration(1),
        )
        val projected = CameraLensUiProjector.project(
            CameraLensProjectionInput(
                topology = inventory.topology.value,
                runtimeApiLevel = 35,
                activeSelection = active,
                statusByLens = mapOf(main to LensTestStatus.VERIFIED),
                stableOneXReferenceFingerprint = inventory.stableOneXReference.value,
            ),
        )

        assertEquals(main, projected.items.single { it.selected }.canonicalFingerprint)
    }

    @Test
    fun `stable oneX and existing relative labels survive material addition`() {
        val initial = topology(Spec("main", 5f), Spec("tele", 10f))
        val inventory = ready(initial)
        val before = labelsByLens(inventory)
        assertEquals("1×", before.getValue("lens:main"))
        assertEquals("2×", before.getValue("lens:tele"))
        val generation = checkNotNull(inventory.beginExplicitRescan())
        val material = topology(Spec("ultra", 2.5f), Spec("main", 5f), Spec("tele", 10f))

        inventory.completeExplicitRescan(generation, coherent = true, finalSnapshot = material)

        val after = labelsByLens(inventory)
        assertEquals(CanonicalLensFingerprint("lens:main"), inventory.stableOneXReference.value)
        assertEquals(before.getValue("lens:main"), after.getValue("lens:main"))
        assertEquals(before.getValue("lens:tele"), after.getValue("lens:tele"))
        assertEquals("0.5×", after.getValue("lens:ultra"))
    }

    @Test
    fun `structural counter increments only for real selector replacement`() {
        val initial = standard()
        val inventory = ready(initial)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)

        val backgroundEquivalent = topology(
            Spec("front", 3f, facing = LensFacing.FRONT),
            Spec("main", 5f),
            Spec("ultra", 2.5f),
        )
        inventory.observeCandidate(backgroundEquivalent)
        inventory.completeAutomaticReconciliation(backgroundEquivalent)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)

        val noChangeGeneration = checkNotNull(inventory.beginExplicitRescan())
        inventory.completeExplicitRescan(noChangeGeneration, coherent = true, finalSnapshot = backgroundEquivalent)
        assertEquals(1L, inventory.status.value.structuralPublicationCount)

        val materialGeneration = checkNotNull(inventory.beginExplicitRescan())
        inventory.completeExplicitRescan(
            materialGeneration,
            coherent = true,
            finalSnapshot = topology(
                Spec("ultra", 2.5f),
                Spec("main", 5f),
                Spec("tele", 10f),
                Spec("front", 3f, facing = LensFacing.FRONT),
            ),
        )
        assertEquals(2L, inventory.status.value.structuralPublicationCount)
    }

    @Test
    fun `provider permutation and front alias enrichment remain structurally identical`() {
        val initial = standard()
        val first = ready(initial)
        val second = ready(initial)
        val variants = listOf(
            topology(
                Spec("ultra", 2.5f),
                Spec("main", 5f),
                Spec("front", 3f, facing = LensFacing.FRONT),
                Spec("front", 3f, profile = "front-deep", facing = LensFacing.FRONT, source = CameraRouteSource.JAVA_DEEP_PROBED),
            ),
            topology(
                Spec("front", 3f, profile = "front-deep", facing = LensFacing.FRONT, source = CameraRouteSource.JAVA_DEEP_PROBED),
                Spec("front", 3f, facing = LensFacing.FRONT),
                Spec("main", 5f),
                Spec("ultra", 2.5f),
            ),
        )
        listOf(first, second).zip(variants).forEach { (inventory, variant) ->
            val generation = checkNotNull(inventory.beginExplicitRescan())
            inventory.completeExplicitRescan(generation, coherent = true, finalSnapshot = variant)
        }

        assertEquals(labels(first), labels(second))
        assertEquals(first.stableOneXReference.value, second.stableOneXReference.value)
        assertEquals(1L, first.status.value.structuralPublicationCount)
        assertEquals(1L, second.status.value.structuralPublicationCount)
        assertEquals(1, projection(first).items.count { it.facing == LensFacing.FRONT })
    }

    private data class Spec(
        val canonical: String,
        val focal: Float,
        val profile: String = canonical,
        val facing: LensFacing = LensFacing.BACK,
        val source: CameraRouteSource = CameraRouteSource.JAVA_PUBLIC,
        val metadataTrust: CameraTrust = CameraTrust.ADVERTISED,
        val previewTrust: PreviewTrust = PreviewTrust.ADVERTISED,
    )

    private fun standard() = topology(
        Spec("ultra", 2.5f),
        Spec("main", 5f),
        Spec("front", 3f, facing = LensFacing.FRONT),
    )

    private fun ready(snapshot: CameraTopologySnapshot): LensInventoryCoordinator =
        LensInventoryCoordinator(environment, 35).also {
            it.acceptCompatibleCache(snapshot, CanonicalLensFingerprint("lens:main"))
        }

    private fun projection(inventory: LensInventoryCoordinator) = CameraLensUiProjector.project(
        CameraLensProjectionInput(
            topology = inventory.topology.value,
            runtimeApiLevel = 35,
            activeSelection = null,
            stableOneXReferenceFingerprint = inventory.stableOneXReference.value,
        ),
    )

    private fun labels(inventory: LensInventoryCoordinator) = projection(inventory).items.map {
        Triple(it.canonicalFingerprint.value, it.facing, it.primaryLabel)
    }

    private fun labelsByLens(inventory: LensInventoryCoordinator) = projection(inventory).items.associate {
        it.canonicalFingerprint.value to it.primaryLabel
    }

    private fun topology(vararg specs: Spec): CameraTopologySnapshot {
        val profiles = specs.map { spec ->
            val route = CameraRoute(
                id = CameraRouteId("route:${spec.profile}"),
                source = spec.source,
                openCameraId = CameraTransportId("transport:${spec.profile}"),
                capabilities = CameraCapabilities(
                    previewStreams = listOf(
                        CameraStreamCapability(
                            type = PreviewStreamType.CAMERA2_PRIVATE,
                            size = IntSize(1280, 720),
                            minimumFrameDurationNs = 33_333_333L,
                        ),
                    ),
                ),
                metadataTrust = spec.metadataTrust,
                previewTrust = spec.previewTrust,
            )
            CameraProfile(
                fingerprint = CameraProfileFingerprint("profile:${spec.profile}"),
                canonicalFingerprint = CanonicalLensFingerprint("lens:${spec.canonical}"),
                route = route,
            )
        }
        val specByProfile = specs.associateBy { it.profile }
        val lenses = profiles.groupBy { it.canonicalFingerprint }.map { (fingerprint, grouped) ->
            CanonicalLens(
                fingerprint = fingerprint,
                facing = specByProfile.getValue(grouped.first().fingerprint.value.removePrefix("profile:")).facing,
                profiles = grouped,
            )
        }
        val evidence = profiles.map { profile ->
            val spec = specByProfile.getValue(profile.fingerprint.value.removePrefix("profile:"))
            CameraMetadataEvidence(
                source = spec.source,
                transportId = profile.route.openCameraId,
                facing = spec.facing,
                focalLengthsMillimetres = listOf(spec.focal),
                sensorPhysicalWidthMillimetres = 6f,
                sensorPhysicalHeightMillimetres = 4.5f,
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4000, 3000),
                sensorOrientationDegrees = 90,
                capabilities = profile.route.capabilities,
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
