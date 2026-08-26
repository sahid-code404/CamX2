package com.sahidcode404.camx.core.camera.diagnostics

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCandidate
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCandidateOutcome
import com.sahidcode404.camx.core.camera.discovery.DeepAuxOutcomeKind
import com.sahidcode404.camx.core.camera.discovery.DeepAuxWave
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceFailure
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceFailureKind
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationKind
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationOutcome
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedRuntimeState
import com.sahidcode404.camx.core.camera.discovery.NdkDeepEvidenceReport
import com.sahidcode404.camx.core.camera.lens.CameraLensProjectionInput
import com.sahidcode404.camx.core.camera.lens.CameraLensUiProjector
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
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

class AuxHardwareAuditTest {
    private val environment = CameraEnvironmentFingerprint("audit-test")

    @Test
    fun `synthetic discovery pipeline produces complete sanitized deterministic audit`() {
        var now = 1_000_000_000L
        val tracker = AuxDiscoveryAuditTracker { now }
        tracker.markFirstFrame(now)
        tracker.beginRun(selectableCount = 1, publicationCount = 10L)
        now += 10_000_000L
        tracker.onJavaAdvertised(
            JavaAdvertisedEvidenceReport(
                snapshots = listOf(
                    snapshot(
                        CameraRouteSource.JAVA_PUBLIC,
                        listOf(
                            evidence("main", CameraRouteSource.JAVA_PUBLIC, 4f),
                            evidence("logical", CameraRouteSource.JAVA_PUBLIC, 2.5f),
                        ),
                    ),
                    snapshot(
                        CameraRouteSource.JAVA_PHYSICAL,
                        listOf(
                            evidence(
                                "logical",
                                CameraRouteSource.JAVA_PHYSICAL,
                                2.5f,
                                physical = "member",
                                parent = "logical",
                            ),
                        ),
                    ),
                ),
                failures = listOf(
                    JavaAdvertisedEvidenceFailure(
                        JavaAdvertisedEvidenceFailureKind.PHYSICAL_CHARACTERISTICS_UNAVAILABLE,
                        transportId = "logical",
                        physicalId = "denied-member",
                    ),
                ),
            ),
        )
        tracker.onNdkAdvertised(
            NdkAdvertisedEvidenceReport(
                snapshot = snapshot(CameraRouteSource.NDK_ADVERTISED, listOf(evidence("main", CameraRouteSource.NDK_ADVERTISED, 4f))),
                runtimeState = NdkAdvertisedRuntimeState.AVAILABLE,
                failures = emptyList(),
            ),
        )
        now += 20_000_000L
        tracker.onNdkDeep(
            NdkDeepEvidenceReport(
                snapshot = snapshot(CameraRouteSource.NDK_DEEP, listOf(evidence("opaque-hidden", CameraRouteSource.NDK_DEEP, 2f))),
                outcomes = listOf(
                    outcome("opaque-hidden", DeepAuxOutcomeKind.VALID_METADATA),
                    outcome("other-valid", DeepAuxOutcomeKind.VALID_METADATA),
                    outcome("absent", DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE),
                    outcome("denied", DeepAuxOutcomeKind.ACCESS_DENIED),
                    outcome("busy", DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE),
                ),
                failures = emptyList(),
                plannedCandidates = listOf(
                    DeepAuxCandidate("opaque-hidden", DeepAuxWave.LOW_NAMESPACE),
                    DeepAuxCandidate("other-valid", DeepAuxWave.LOW_NAMESPACE),
                    DeepAuxCandidate("absent", DeepAuxWave.LOW_NAMESPACE),
                    DeepAuxCandidate("denied", DeepAuxWave.LOW_NAMESPACE),
                    DeepAuxCandidate("busy", DeepAuxWave.LOW_NAMESPACE),
                    DeepAuxCandidate("planned-only", DeepAuxWave.LOW_NAMESPACE),
                ),
            ),
        )
        now += 10_000_000L
        tracker.onJavaDeep(
            JavaDeepCertificationReport(
                snapshot = snapshot(
                    CameraRouteSource.JAVA_DEEP_PROBED,
                    listOf(evidence("opaque-hidden", CameraRouteSource.JAVA_DEEP_PROBED, 2f)),
                ),
                outcomes = listOf(
                    JavaDeepCertificationOutcome(
                        DeepAuxCandidate("opaque-hidden", DeepAuxWave.LOW_NAMESPACE),
                        JavaDeepCertificationKind.CERTIFIED,
                    ),
                    JavaDeepCertificationOutcome(
                        DeepAuxCandidate("other-valid", DeepAuxWave.LOW_NAMESPACE),
                        JavaDeepCertificationKind.MISSING_ORIENTATION,
                    ),
                ),
            ),
        )

        val topology = topology()
        val status = mapOf(HIDDEN to LensTestStatus.VERIFIED)
        val projection = CameraLensUiProjector.project(
            CameraLensProjectionInput(topology, 35, null, status),
        )
        now += 10_000_000L
        tracker.onTopologyState(selectableCount = 2, publicationCount = 13L)
        now += 10_000_000L
        tracker.finishRun()

        val audit = AuxHardwareAudit.build(topology, projection, tracker.snapshot())
        assertEquals(2, audit.counters.javaAdvertisedIds)
        assertEquals(2, audit.counters.javaPublicEvidence)
        assertEquals(1, audit.counters.logicalCameraCount)
        assertEquals(1, audit.counters.physicalMemberRelationships)
        assertEquals(1, audit.counters.physicalMetadataSuccesses)
        assertEquals(1, audit.counters.physicalMetadataFailures)
        assertEquals(1, audit.counters.ndkAdvertisedEvidence)
        assertEquals(5, audit.counters.deepCandidateAddressesAttempted)
        assertEquals(2, audit.counters.deepValidMetadata)
        assertEquals(1, audit.counters.deepTerminalNegative)
        assertEquals(1, audit.counters.deepAccessDenied)
        assertEquals(1, audit.counters.deepTemporaryOrServiceFailure)
        assertEquals(2, audit.counters.javaDeepCertificationAttempts)
        assertEquals(1, audit.counters.javaDeepCertified)
        assertEquals(1, audit.counters.javaDeepCertificationFailuresByType["MISSING_ORIENTATION"])
        assertEquals(2, audit.resolvedRoutes)
        assertEquals(2, audit.resolvedProfiles)
        assertEquals(2, audit.canonicalLenses)
        assertEquals(2, audit.selectableCanonicalLenses)
        assertEquals(1, audit.sessionVerifiedLenses)
        assertEquals(3L, audit.counters.incrementalTopologyPublications)

        val stages = audit.deepCandidates.associateBy { it.pipelineStage }
        assertEquals(6, audit.deepCandidates.size)
        assertTrue(stages.getValue("LENS_PREVIEW_VERIFIED").routeResolved)
        assertTrue(stages.getValue("LENS_PREVIEW_VERIFIED").profileSelectable)
        assertTrue(stages.getValue("LENS_PREVIEW_VERIFIED").previewVerified)
        assertEquals(
            JavaDeepCertificationKind.MISSING_ORIENTATION.name,
            stages.getValue("NDK_VALID_JAVA_CERTIFICATION_FAILED").javaCertification,
        )
        assertEquals(
            DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE.name,
            stages.getValue("NDK_NOT_FOUND_OR_UNAVAILABLE").ndkOutcome,
        )
        assertEquals(
            DeepAuxOutcomeKind.ACCESS_DENIED.name,
            stages.getValue("NDK_ACCESS_DENIED").ndkOutcome,
        )
        assertEquals(
            DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE.name,
            stages.getValue("NDK_TEMPORARILY_UNAVAILABLE").ndkOutcome,
        )
        assertEquals(DeepAuxWave.LOW_NAMESPACE.name, stages.getValue("PLANNED_NOT_ATTEMPTED").plannedWave)
        audit.deepCandidates.forEach { candidate ->
            assertTrue(candidate.fingerprint.matches(Regex("[0-9a-f]{16}")))
        }
        audit.lenses.forEach { lens ->
            assertTrue(lens.fingerprint.matches(Regex("[0-9a-f]{16}")))
            lens.profiles.forEach { profile ->
                assertTrue(profile.fingerprint.matches(Regex("[0-9a-f]{16}")))
            }
        }
        projection.items.forEach { item ->
            assertFalse(item.primaryLabel.contains("opaque-hidden"))
            assertFalse(item.secondaryOpticalLabel.orEmpty().contains("opaque-hidden"))
        }

        val reversedTopology = topology.copy(
            routes = topology.routes.reversed(),
            canonicalLenses = topology.canonicalLenses.reversed(),
            evidence = topology.evidence.reversed(),
        )
        val reversedProjection = CameraLensUiProjector.project(
            CameraLensProjectionInput(reversedTopology, 35, null, status),
        )
        val reversedAudit = AuxHardwareAudit.build(reversedTopology, reversedProjection, tracker.snapshot())
        assertEquals(audit.lenses, reversedAudit.lenses)
        assertEquals(audit.deepCandidates, reversedAudit.deepCandidates)
    }

    private fun topology(): CameraTopologySnapshot {
        val mainRoute = route("main", CameraRouteSource.JAVA_PUBLIC, 4f)
        val hiddenRoute = route(
            "opaque-hidden",
            CameraRouteSource.JAVA_DEEP_PROBED,
            2f,
            sources = setOf(CameraRouteSource.JAVA_DEEP_PROBED, CameraRouteSource.NDK_DEEP),
        )
        val mainProfile = profile(mainRoute, MAIN)
        val hiddenProfile = profile(hiddenRoute, HIDDEN)
        return CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = environment,
            routes = listOf(mainRoute, hiddenRoute),
            canonicalLenses = listOf(
                CanonicalLens(MAIN, LensFacing.BACK, listOf(mainProfile)),
                CanonicalLens(HIDDEN, LensFacing.BACK, listOf(hiddenProfile)),
            ),
            generatedAtElapsedRealtimeNs = 1L,
            evidence = listOf(
                evidence("main", CameraRouteSource.JAVA_PUBLIC, 4f),
                evidence("opaque-hidden", CameraRouteSource.JAVA_DEEP_PROBED, 2f),
                evidence("opaque-hidden", CameraRouteSource.NDK_DEEP, 2f),
            ),
        )
    }

    private fun route(
        id: String,
        source: CameraRouteSource,
        focal: Float,
        sources: Set<CameraRouteSource> = setOf(source),
    ) = CameraRoute(
        id = CameraRouteId("route:$id"),
        source = source,
        openCameraId = CameraTransportId(id),
        capabilities = capabilities(),
        metadataTrust = CameraTrust.ADVERTISED,
        sources = sources,
    )

    private fun profile(route: CameraRoute, lens: CanonicalLensFingerprint) = CameraProfile(
        CameraProfileFingerprint("profile:${route.id.value}"),
        lens,
        route,
    )

    private fun evidence(
        id: String,
        source: CameraRouteSource,
        focal: Float,
        physical: String? = null,
        parent: String? = null,
    ) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        physicalId = physical?.let(::PhysicalCameraId),
        logicalParentId = parent?.let(::CameraTransportId),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(focal),
        sensorPhysicalWidthMillimetres = 6f,
        sensorPhysicalHeightMillimetres = 4.5f,
        sensorOrientationDegrees = 90,
        capabilities = capabilities(),
    )

    private fun snapshot(source: CameraRouteSource, evidence: List<CameraMetadataEvidence>) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence,
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun outcome(id: String, kind: DeepAuxOutcomeKind) = DeepAuxCandidateOutcome(
        DeepAuxCandidate(id, DeepAuxWave.LOW_NAMESPACE),
        kind,
    )

    private fun capabilities() = CameraCapabilities(
        previewStreams = listOf(
            CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(1280, 720), 33_333_333L),
        ),
    )

    private companion object {
        val MAIN = CanonicalLensFingerprint("lens:main")
        val HIDDEN = CanonicalLensFingerprint("lens:hidden")
    }
}
