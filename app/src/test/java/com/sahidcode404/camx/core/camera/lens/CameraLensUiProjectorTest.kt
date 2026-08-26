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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensUiProjectorTest {
    @Test
    fun nullTopologyProducesNoLensControls() {
        val result = project(null)
        assertTrue(result.items.isEmpty())
        assertTrue(result.targets.isEmpty())
    }

    @Test
    fun oneRearCanonicalLensProducesOneOpticalControl() {
        val topology = topology(ProfileSpec("main", "main", focalLengths = listOf(4.7f), sensorWidth = 6.4f))
        val item = project(topology).items.single()
        assertEquals(LensFacing.BACK, item.facing)
        assertEquals("4.7 mm", item.primaryLabel)
        assertTrue(item.enabled)
    }

    @Test
    fun rearAndFrontAreProjectedWithoutRawIds() {
        val topology = topology(
            ProfileSpec("rear", "0", focalLengths = listOf(4.7f), sensorWidth = 6.4f),
            ProfileSpec("front", "1", facing = LensFacing.FRONT, focalLengths = listOf(3.2f), sensorWidth = 4.8f),
        )
        val items = project(topology).items
        assertEquals(listOf(LensFacing.BACK, LensFacing.FRONT), items.map { it.facing })
        assertEquals("Front", items.last().primaryLabel)
        assertTrue(items.none { it.primaryLabel == "0" || it.primaryLabel == "1" })
    }

    @Test
    fun rearOpticalOrderingIsWidestToNarrowest() {
        val topology = topology(
            ProfileSpec("tele", "tele", focalLengths = listOf(8f), sensorWidth = 6f),
            ProfileSpec("ultra", "ultra", focalLengths = listOf(2f), sensorWidth = 6f),
            ProfileSpec("main", "main", focalLengths = listOf(4f), sensorWidth = 6f),
        )
        assertEquals(
            listOf("lens:ultra", "lens:main", "lens:tele"),
            project(topology).items.map { it.canonicalFingerprint.value },
        )
    }

    @Test
    fun macroLikeRearLensRemainsASeparateCanonicalControl() {
        val topology = topology(
            ProfileSpec("main", "main", focalLengths = listOf(4f), sensorWidth = 6f),
            ProfileSpec("macro", "macro", focalLengths = listOf(3f), sensorWidth = 2f),
        )
        assertEquals(2, project(topology).items.size)
    }

    @Test
    fun duplicateProfilesCollapseToOneCanonicalButtonAndDirectProfileWins() {
        val topology = topology(
            ProfileSpec("main", "physical", source = CameraRouteSource.JAVA_PHYSICAL, physicalId = "p", focalLengths = listOf(4f), sensorWidth = 6f),
            ProfileSpec("main", "direct", source = CameraRouteSource.JAVA_PUBLIC, focalLengths = listOf(4f), sensorWidth = 6f),
        )
        val projection = project(topology, api = 35)
        assertEquals(1, projection.items.size)
        assertEquals(CameraRouteId("route:direct"), projection.targets.getValue(CanonicalLensFingerprint("lens:main")).routeId)
    }

    @Test
    fun activeVerifiedSelectableProfileWinsWithinCanonicalLens() {
        val topology = topology(
            ProfileSpec("main", "direct", source = CameraRouteSource.JAVA_PUBLIC, focalLengths = listOf(4f), sensorWidth = 6f),
            ProfileSpec("main", "physical", source = CameraRouteSource.JAVA_PHYSICAL, physicalId = "p", focalLengths = listOf(4f), sensorWidth = 6f),
        )
        val active = selection("main", "physical")
        val projection = project(
            topology,
            api = 35,
            active = active,
            statuses = mapOf(CanonicalLensFingerprint("lens:main") to LensTestStatus.VERIFIED),
        )
        assertEquals(CameraRouteId("route:physical"), projection.targets.getValue(CanonicalLensFingerprint("lens:main")).routeId)
        assertTrue(projection.items.single().selected)
    }

    @Test
    fun javaDirectProfileIsSelectableOnApi23() {
        val topology = topology(ProfileSpec("main", "main"))
        assertEquals(1, project(topology, api = 23).targets.size)
    }

    @Test
    fun javaPhysicalProfileIsSelectableOnlyOnApi28Plus() {
        val topology = topology(
            ProfileSpec("ultra", "ultra", source = CameraRouteSource.JAVA_PHYSICAL, physicalId = "physical-u"),
        )
        assertTrue(project(topology, api = 27).items.isEmpty())
        assertEquals(1, project(topology, api = 28).items.size)
    }

    @Test
    fun ndkOnlyLensIsNotExposedAsJavaControlTarget() {
        val topology = topology(ProfileSpec("hidden", "hidden", source = CameraRouteSource.NDK_ADVERTISED))
        assertTrue(project(topology, api = 35).items.isEmpty())
    }

    @Test
    fun canonicalLensWithJavaAndNdkAliasesProducesOneEnabledButton() {
        val topology = topology(
            ProfileSpec("main", "ndk", source = CameraRouteSource.NDK_ADVERTISED),
            ProfileSpec("main", "java", source = CameraRouteSource.JAVA_PUBLIC),
        )
        val projection = project(topology)
        assertEquals(1, projection.items.size)
        assertEquals(CameraRouteId("route:java"), projection.targets.values.single().routeId)
    }

    @Test
    fun missingPreviewStreamsOrOrientationMakesProfileUnselectable() {
        assertTrue(project(topology(ProfileSpec("a", "a", previewAvailable = false))).items.isEmpty())
        assertTrue(project(topology(ProfileSpec("b", "b", orientation = null))).items.isEmpty())
    }

    @Test
    fun permutationOfTopologyAndEvidenceDoesNotChangeProjection() {
        val specs = listOf(
            ProfileSpec("tele", "tele", focalLengths = listOf(8f), sensorWidth = 6f),
            ProfileSpec("front", "front", facing = LensFacing.FRONT, focalLengths = listOf(3f), sensorWidth = 5f),
            ProfileSpec("ultra", "ultra", focalLengths = listOf(2f), sensorWidth = 6f),
            ProfileSpec("main", "main", focalLengths = listOf(4f), sensorWidth = 6f),
        )
        val forward = project(topology(*specs.toTypedArray())).items
        val reverse = project(topology(*specs.reversed().toTypedArray(), reverseEvidence = true)).items
        assertEquals(forward, reverse)
    }

    @Test
    fun verifiedMainCanProvideEvidenceBackedRelativeZoomLabels() {
        val topology = topology(
            ProfileSpec("ultra", "ultra", focalLengths = listOf(2f), sensorWidth = 6f),
            ProfileSpec("main", "main", focalLengths = listOf(4f), sensorWidth = 6f),
            ProfileSpec("tele", "tele", focalLengths = listOf(8f), sensorWidth = 6f),
        )
        val main = CanonicalLensFingerprint("lens:main")
        val projection = project(
            topology,
            active = selection("main", "main"),
            statuses = mapOf(main to LensTestStatus.VERIFIED),
        )
        assertEquals(listOf("0.5×", "1×", "2×"), projection.items.map { it.primaryLabel })
        assertEquals("4 mm", projection.items[1].secondaryOpticalLabel)
    }

    @Test
    fun noTrustworthyReferenceFallsBackToRealFocalLabels() {
        val topology = topology(
            ProfileSpec("ultra", "ultra", focalLengths = listOf(1.74f), sensorWidth = 4f),
            ProfileSpec("main", "main", focalLengths = listOf(4.74f), sensorWidth = 6f),
        )
        assertEquals(listOf("1.74 mm", "4.74 mm"), project(topology).items.map { it.primaryLabel })
    }

    @Test
    fun multipleFocalLogicalRouteIsNeverInventedAsSingleZoomLens() {
        val topology = topology(
            ProfileSpec("aggregate", "aggregate", focalLengths = listOf(2f, 4f, 8f), sensorWidth = 6f),
        )
        val item = project(topology).items.single()
        assertEquals("Rear", item.primaryLabel)
        assertNull(item.secondaryOpticalLabel)
        assertFalse(item.primaryLabel.contains('×'))
    }

    @Test
    fun frontAndExternalUseSemanticLabels() {
        val topology = topology(
            ProfileSpec("external", "external", facing = LensFacing.EXTERNAL),
            ProfileSpec("front", "front", facing = LensFacing.FRONT),
        )
        val labels = project(topology).items.associate { it.facing to it.primaryLabel }
        assertEquals("Front", labels[LensFacing.FRONT])
        assertEquals("External", labels[LensFacing.EXTERNAL])
    }

    @Test
    fun rawTransportAndPhysicalIdsNeverAppearInPresentationLabels() {
        val topology = topology(
            ProfileSpec("safe", "profile", transportId = "0", physicalId = "1", source = CameraRouteSource.JAVA_PHYSICAL, focalLengths = listOf(5f)),
        )
        val item = project(topology, api = 35).items.single()
        val combined = listOfNotNull(item.primaryLabel, item.secondaryOpticalLabel).joinToString(" ")
        assertFalse(combined == "0" || combined == "1" || combined.contains("camera 0", ignoreCase = true))
    }

    @Test
    fun failedLensStaysEnabledForExplicitRetry() {
        val lens = CanonicalLensFingerprint("lens:tele")
        val projection = project(
            topology(ProfileSpec("tele", "tele")),
            statuses = mapOf(lens to LensTestStatus.FAILED),
        )
        assertEquals(LensTestStatus.FAILED, projection.items.single().status)
        assertTrue(projection.items.single().enabled)
    }

    private data class ProfileSpec(
        val canonical: String,
        val profile: String,
        val facing: LensFacing = LensFacing.BACK,
        val source: CameraRouteSource = CameraRouteSource.JAVA_PUBLIC,
        val physicalId: String? = null,
        val transportId: String = "transport:$profile",
        val focalLengths: List<Float> = listOf(4f),
        val sensorWidth: Float? = 6f,
        val orientation: Int? = 90,
        val previewAvailable: Boolean = true,
    )

    private fun topology(
        vararg specs: ProfileSpec,
        reverseEvidence: Boolean = false,
    ): CameraTopologySnapshot {
        val profiles = specs.map { spec ->
            val physical = spec.physicalId?.let(::PhysicalCameraId)
            val route = CameraRoute(
                id = CameraRouteId("route:${spec.profile}"),
                source = spec.source,
                openCameraId = CameraTransportId(spec.transportId),
                physicalCameraId = physical,
                capabilities = CameraCapabilities(
                    previewStreams = if (spec.previewAvailable) listOf(
                        CameraStreamCapability(
                            PreviewStreamType.CAMERA2_PRIVATE,
                            IntSize(1280, 720),
                            33_333_333L,
                        ),
                    ) else emptyList(),
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
                transportId = CameraTransportId(spec.transportId),
                physicalId = spec.physicalId?.let(::PhysicalCameraId),
                facing = spec.facing,
                focalLengthsMillimetres = spec.focalLengths,
                sensorPhysicalWidthMillimetres = spec.sensorWidth,
                sensorPhysicalHeightMillimetres = spec.sensorWidth?.let { it * 0.75f },
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4000, 3000),
                sensorOrientationDegrees = spec.orientation,
                capabilities = profiles.first { it.fingerprint == CameraProfileFingerprint("profile:${spec.profile}") }.route.capabilities,
            )
        }.let { if (reverseEvidence) it.reversed() else it }
        return CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = CameraEnvironmentFingerprint("lens-ui-test"),
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
        topology: CameraTopologySnapshot?,
        api: Int = 35,
        active: ActiveCameraSelection? = null,
        statuses: Map<CanonicalLensFingerprint, LensTestStatus> = emptyMap(),
    ) = CameraLensUiProjector.project(
        CameraLensProjectionInput(
            topology = topology,
            runtimeApiLevel = api,
            activeSelection = active,
            statusByLens = statuses,
        ),
    )
}
