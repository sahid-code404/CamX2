package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.lens.CameraLensProjectionInput
import com.sahidcode404.camx.core.camera.lens.CameraLensUiProjector
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity4AuxGroupingDiagnosticsTest {
    private val environment = CameraEnvironmentFingerprint("parity4-audit")

    @Test
    fun canonicalAliasesRemainOneSelectorItemAndAuditExplainsGroupingAndSeparationWithoutRawIds() {
        val ultra = directProfile("ultra-public", CameraRouteSource.JAVA_PUBLIC, ULTRA)
        val mainPublic = directProfile("main-public-secret", CameraRouteSource.JAVA_PUBLIC, MAIN)
        val mainDeep = directProfile("main-deep-secret", CameraRouteSource.JAVA_DEEP_PROBED, MAIN)
        val mainPhysical = physicalProfile("main-logical-secret", "main-member-secret", MAIN)
        val tele = directProfile("tele-public", CameraRouteSource.JAVA_PUBLIC, TELE)
        val frontPublic = directProfile("front-public-secret", CameraRouteSource.JAVA_PUBLIC, FRONT, LensFacing.FRONT)
        val frontDeep = directProfile("front-deep-secret", CameraRouteSource.JAVA_DEEP_PROBED, FRONT, LensFacing.FRONT)
        val profiles = listOf(ultra, mainPublic, mainDeep, mainPhysical, tele, frontPublic, frontDeep)
        val topology = CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = environment,
            routes = profiles.map { it.route },
            canonicalLenses = listOf(
                CanonicalLens(ULTRA, LensFacing.BACK, listOf(ultra)),
                CanonicalLens(MAIN, LensFacing.BACK, listOf(mainPublic, mainDeep, mainPhysical)),
                CanonicalLens(TELE, LensFacing.BACK, listOf(tele)),
                CanonicalLens(FRONT, LensFacing.FRONT, listOf(frontPublic, frontDeep)),
            ),
            generatedAtElapsedRealtimeNs = 1L,
            evidence = listOf(
                evidence(ultra, 2.5f, LensFacing.BACK),
                evidence(mainPublic, 5f, LensFacing.BACK),
                evidence(mainDeep, 5.01f, LensFacing.BACK),
                evidence(mainPhysical, 5f, LensFacing.BACK, parent = "main-logical-secret", member = "main-member-secret"),
                evidence(tele, 10f, LensFacing.BACK),
                evidence(frontPublic, 3f, LensFacing.FRONT),
                evidence(frontDeep, 3.01f, LensFacing.FRONT),
            ),
        )
        val projection = CameraLensUiProjector.project(
            CameraLensProjectionInput(
                topology = topology,
                runtimeApiLevel = 35,
                activeSelection = null,
                stableOneXReferenceFingerprint = MAIN,
            ),
        )

        assertEquals(4, projection.items.size)
        assertEquals(MAIN, projection.stableOneXReferenceFingerprint)
        val tracker = AuxDiscoveryAuditTracker { 1L }
        val audit = AuxHardwareAudit.build(
            topology = topology,
            projection = projection,
            tracker = tracker.snapshot(),
            cache = AuxCacheAudit(
                currentTopologySchema = CameraSchemaVersions.TOPOLOGY,
                storedTopologySchema = 1,
                status = "MIGRATED",
                environmentCompatible = true,
                migrated = true,
            ),
        )

        assertEquals(4, audit.canonicalLenses)
        assertEquals(7, audit.resolvedProfiles)
        assertEquals(4, audit.selectableCanonicalLenses)
        assertEquals("MIGRATED", audit.cache.status)
        assertTrue(audit.cache.migrated)

        val mainAudit = audit.lenses.single { it.profileCount == 3 }
        assertEquals("REFERENCE_1X", mainAudit.stableOneXRelationship)
        assertEquals(3, mainAudit.profiles.size)
        assertTrue(mainAudit.groupingReasons.isNotEmpty())
        assertTrue(mainAudit.groupingReasons.any { it.contains("match=STRONG_MATCH") })
        assertTrue(mainAudit.groupingReasons.all { it.contains("profiles=") && it.contains("families=") })
        assertTrue(mainAudit.profiles.all { it.routeIdentity.matches(Regex("[0-9a-f]{16}")) })
        assertTrue(mainAudit.profiles.any { it.logicalPhysicalRelationship != null })
        assertTrue(mainAudit.profiles.all { it.previewSupported })

        val frontAudit = audit.lenses.single { it.facing == LensFacing.FRONT.name }
        assertEquals(2, frontAudit.profileCount)
        assertTrue(audit.separationReasons.isNotEmpty())
        assertTrue(audit.separationReasons.any { it.contains("CONFLICT") || it.contains("INSUFFICIENT_EVIDENCE") })

        val rendered = buildString {
            audit.lenses.forEach { lens ->
                append(lens)
            }
            audit.separationReasons.forEach(::append)
        }
        listOf(
            "main-public-secret",
            "main-deep-secret",
            "main-logical-secret",
            "main-member-secret",
            "front-public-secret",
            "front-deep-secret",
        ).forEach { raw -> assertFalse(rendered.contains(raw)) }
    }

    private fun directProfile(
        id: String,
        source: CameraRouteSource,
        lens: CanonicalLensFingerprint,
        facing: LensFacing = LensFacing.BACK,
    ): CameraProfile {
        val route = CameraRoute(
            id = CameraRouteId("route:$id"),
            source = source,
            openCameraId = CameraTransportId(id),
            capabilities = capabilities(),
            metadataTrust = CameraTrust.ADVERTISED,
            sources = setOf(source),
        )
        return CameraProfile(CameraProfileFingerprint("profile:$id"), lens, route)
    }

    private fun physicalProfile(
        parent: String,
        member: String,
        lens: CanonicalLensFingerprint,
    ): CameraProfile {
        val route = CameraRoute(
            id = CameraRouteId("route:$parent:$member"),
            source = CameraRouteSource.JAVA_PHYSICAL,
            openCameraId = CameraTransportId(parent),
            physicalCameraId = PhysicalCameraId(member),
            capabilities = capabilities(),
            metadataTrust = CameraTrust.ADVERTISED,
            sources = setOf(CameraRouteSource.JAVA_PHYSICAL),
        )
        return CameraProfile(CameraProfileFingerprint("profile:$parent:$member"), lens, route)
    }

    private fun evidence(
        profile: CameraProfile,
        focal: Float,
        facing: LensFacing,
        parent: String? = null,
        member: String? = null,
    ) = CameraMetadataEvidence(
        source = profile.route.source,
        transportId = profile.route.openCameraId,
        physicalId = member?.let(::PhysicalCameraId) ?: profile.route.physicalCameraId,
        logicalParentId = parent?.let(::CameraTransportId),
        facing = facing,
        focalLengthsMillimetres = listOf(focal),
        sensorPhysicalWidthMillimetres = 6f,
        sensorPhysicalHeightMillimetres = 4.5f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4000, 3000),
        sensorOrientationDegrees = 90,
        colorFilterArrangement = 1,
        capabilities = capabilities(),
    )

    private fun capabilities() = CameraCapabilities(
        previewStreams = listOf(
            CameraStreamCapability(
                PreviewStreamType.CAMERA2_PRIVATE,
                IntSize(1280, 720),
                33_333_333L,
            ),
        ),
    )

    private companion object {
        val ULTRA = CanonicalLensFingerprint("lens:ultra")
        val MAIN = CanonicalLensFingerprint("lens:main")
        val TELE = CanonicalLensFingerprint("lens:tele")
        val FRONT = CanonicalLensFingerprint("lens:front")
    }
}
