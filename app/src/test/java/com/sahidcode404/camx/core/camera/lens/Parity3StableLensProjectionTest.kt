package com.sahidcode404.camx.core.camera.lens

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
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Parity3StableLensProjectionTest {
    @Test
    fun activeRearSwitchNeverRebasesStableReference() {
        val topology = topology(
            Spec("ultra", "ultra", 2f),
            Spec("main", "main", 4f),
            Spec("tele", "tele", 8f),
        )
        val reference = CanonicalLensFingerprint("lens:main")
        val expected = listOf("0.5×", "1×", "2×")

        listOf("main", "ultra", "tele").forEach { active ->
            val activeLens = CanonicalLensFingerprint("lens:$active")
            val projection = project(
                topology = topology,
                active = selection(active, active),
                statuses = mapOf(activeLens to LensTestStatus.VERIFIED),
                reference = reference,
            )
            assertEquals(reference, projection.stableOneXReferenceFingerprint)
            assertEquals(expected, projection.items.map { it.primaryLabel })
        }
    }

    @Test
    fun openingSwitchKeepsDerivedReferenceIndependentOfActiveRoute() {
        val topology = topology(
            Spec("ultra", "ultra", 2f),
            Spec("main", "main", 4f),
            Spec("tele", "tele", 8f),
        )
        val main = CanonicalLensFingerprint("lens:main")
        val tele = CanonicalLensFingerprint("lens:tele")
        val verified = project(
            topology = topology,
            active = selection("main", "main"),
            statuses = mapOf(main to LensTestStatus.VERIFIED),
        )
        val opening = project(
            topology = topology,
            active = null,
            statuses = mapOf(tele to LensTestStatus.OPENING),
            reference = verified.stableOneXReferenceFingerprint,
        )
        assertNotNull(verified.stableOneXReferenceFingerprint)
        assertEquals(verified.stableOneXReferenceFingerprint, opening.stableOneXReferenceFingerprint)
        assertEquals(verified.items.map { it.primaryLabel }, opening.items.map { it.primaryLabel })
    }

    @Test
    fun profileFailoverWithinMainLensDoesNotChangeOpticalPresentation() {
        val topology = topology(
            Spec("main", "public", 4f, CameraRouteSource.JAVA_PUBLIC),
            Spec("main", "physical", 4f, CameraRouteSource.JAVA_PHYSICAL, physicalId = "p"),
            Spec("main", "deep", 4f, CameraRouteSource.JAVA_DEEP_PROBED),
        )
        val reference = CanonicalLensFingerprint("lens:main")
        val labels = listOf("public", "physical", "deep").map { profile ->
            project(
                topology = topology,
                api = 35,
                active = selection("main", profile),
                statuses = mapOf(reference to LensTestStatus.VERIFIED),
                reference = reference,
            ).items.single().let { it.primaryLabel to it.secondaryOpticalLabel }
        }
        assertEquals(listOf("1×" to "4 mm", "1×" to "4 mm", "1×" to "4 mm"), labels)
    }

    @Test
    fun frontActivationDoesNotBecomeRearReference() {
        val topology = topology(
            Spec("ultra", "ultra", 2f),
            Spec("main", "main", 4f),
            Spec("tele", "tele", 8f),
            Spec("front", "front", 3f, facing = LensFacing.FRONT),
        )
        val reference = CanonicalLensFingerprint("lens:main")
        val projection = project(
            topology = topology,
            active = selection("front", "front"),
            statuses = mapOf(CanonicalLensFingerprint("lens:front") to LensTestStatus.VERIFIED),
            reference = reference,
        )
        assertEquals(reference, projection.stableOneXReferenceFingerprint)
        assertEquals(listOf("0.5×", "1×", "2×", "Front"), projection.items.map { it.primaryLabel })
    }

    @Test
    fun evidencePermutationElectsSameDefaultReferenceAndOrdering() {
        val specs = listOf(
            Spec("tele", "tele", 8f),
            Spec("ultra", "ultra", 2f),
            Spec("main", "main", 4f),
        )
        val forward = project(
            topology(*specs.toTypedArray()),
            statuses = mapOf(CanonicalLensFingerprint("lens:main") to LensTestStatus.VERIFIED),
        )
        val reverse = project(
            topology(*specs.reversed().toTypedArray(), reverseEvidence = true),
            statuses = mapOf(CanonicalLensFingerprint("lens:tele") to LensTestStatus.OPENING),
            reference = forward.stableOneXReferenceFingerprint,
        )
        assertEquals(CanonicalLensFingerprint("lens:main"), forward.stableOneXReferenceFingerprint)
        assertEquals(forward.stableOneXReferenceFingerprint, reverse.stableOneXReferenceFingerprint)
        assertEquals(forward.items.map { it.canonicalFingerprint }, reverse.items.map { it.canonicalFingerprint })
        assertEquals(forward.items.map { it.primaryLabel }, reverse.items.map { it.primaryLabel })
    }

    private data class Spec(
        val canonical: String,
        val profile: String,
        val focal: Float,
        val source: CameraRouteSource = CameraRouteSource.JAVA_PUBLIC,
        val physicalId: String? = null,
        val facing: LensFacing = LensFacing.BACK,
    )

    private fun topology(vararg specs: Spec, reverseEvidence: Boolean = false): CameraTopologySnapshot {
        val profiles = specs.map { spec ->
            val physical = spec.physicalId?.let(::PhysicalCameraId)
            val route = CameraRoute(
                id = CameraRouteId("route:${spec.profile}"),
                source = spec.source,
                openCameraId = CameraTransportId("transport:${spec.profile}"),
                physicalCameraId = physical,
                capabilities = CameraCapabilities(
                    previewStreams = listOf(
                        CameraStreamCapability(
                            PreviewStreamType.CAMERA2_PRIVATE,
                            IntSize(1280, 720),
                            33_333_333L,
                        ),
                    ),
                ),
                metadataTrust = CameraTrust.ADVERTISED,
            )
            CameraProfile(
                fingerprint = CameraProfileFingerprint("profile:${spec.profile}"),
                canonicalFingerprint = CanonicalLensFingerprint("lens:${spec.canonical}"),
                route = route,
            )
        }
        val lenses = profiles.groupBy { it.canonicalFingerprint }.map { (fingerprint, grouped) ->
            CanonicalLens(
                fingerprint = fingerprint,
                facing = specs.first { "lens:${it.canonical}" == fingerprint.value }.facing,
                profiles = grouped,
            )
        }
        val evidence = specs.map { spec ->
            CameraMetadataEvidence(
                source = spec.source,
                transportId = CameraTransportId("transport:${spec.profile}"),
                physicalId = spec.physicalId?.let(::PhysicalCameraId),
                facing = spec.facing,
                focalLengthsMillimetres = listOf(spec.focal),
                sensorPhysicalWidthMillimetres = 6f,
                sensorPhysicalHeightMillimetres = 4.5f,
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4000, 3000),
                sensorOrientationDegrees = 90,
                capabilities = profiles.first { it.fingerprint == CameraProfileFingerprint("profile:${spec.profile}") }
                    .route.capabilities,
            )
        }.let { if (reverseEvidence) it.reversed() else it }
        return CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = CameraEnvironmentFingerprint("parity3-stable-lens"),
            routes = profiles.map { it.route },
            canonicalLenses = lenses,
            generatedAtElapsedRealtimeNs = 1L,
            evidence = evidence,
        )
    }

    private fun selection(canonical: String, profile: String) = ActiveCameraSelection(
        canonicalLensFingerprint = CanonicalLensFingerprint("lens:$canonical"),
        profileFingerprint = CameraProfileFingerprint("profile:$profile"),
        routeId = CameraRouteId("route:$profile"),
        selectionGeneration = SelectionGeneration(0L),
        sessionGeneration = SessionGeneration(0L),
    )

    private fun project(
        topology: CameraTopologySnapshot,
        api: Int = 35,
        active: ActiveCameraSelection? = null,
        statuses: Map<CanonicalLensFingerprint, LensTestStatus> = emptyMap(),
        reference: CanonicalLensFingerprint? = null,
    ) = CameraLensUiProjector.project(
        CameraLensProjectionInput(
            topology = topology,
            runtimeApiLevel = api,
            activeSelection = active,
            statusByLens = statuses,
            stableOneXReferenceFingerprint = reference,
        ),
    )
}
