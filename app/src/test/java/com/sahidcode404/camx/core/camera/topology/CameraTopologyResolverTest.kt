package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraTopologyResolverTest {
    private val environment = CameraEnvironmentFingerprint("test-environment")

    @Test
    fun resolverIsDeterministicAcrossBackendAndEvidenceOrder() {
        val first = snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("opaque-a")))
        val second = snapshot(CameraRouteSource.NDK_ADVERTISED, listOf(complete("opaque-b")))
        val forward = CameraTopologyResolver.resolve(environment, listOf(first, second), 10L)
        val reverse = CameraTopologyResolver.resolve(
            environment,
            listOf(second.copy(evidence = second.evidence.reversed()), first),
            10L,
        )
        assertEquals(forward, reverse)
    }

    @Test
    fun strongEquivalentOpticsWithPhysicalRelationshipCanShareCanonicalLens() {
        val topology = CameraTopologyResolver.resolve(
            environment,
            listOf(
                snapshot(
                    CameraRouteSource.JAVA_PUBLIC,
                    listOf(
                        complete("opaque-logical").copy(
                            physicalId = PhysicalCameraId("opaque-physical"),
                        ),
                        complete("opaque-physical"),
                    ),
                ),
            ),
            10L,
        )
        assertEquals(2, topology.routes.size)
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(2, topology.canonicalLenses.single().profiles.size)
    }

    @Test
    fun incompleteEvidenceIsNeverGuessedIntoOneLens() {
        val topology = CameraTopologyResolver.resolve(
            environment,
            listOf(
                snapshot(
                    CameraRouteSource.JAVA_PUBLIC,
                    listOf(
                        CameraMetadataEvidence(
                            source = CameraRouteSource.JAVA_PUBLIC,
                            transportId = CameraTransportId("opaque-a"),
                            focalLengthsMillimetres = listOf(4.0f),
                        ),
                        CameraMetadataEvidence(
                            source = CameraRouteSource.JAVA_PUBLIC,
                            transportId = CameraTransportId("opaque-b"),
                            focalLengthsMillimetres = listOf(4.0f),
                        ),
                    ),
                ),
            ),
            10L,
        )
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun identicalStrongMetadataWithoutRelationshipGroupsAsVendorAliases() {
        val topology = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("opaque-a"), complete("opaque-b")))),
            10L,
        )
        assertEquals(1, topology.canonicalLenses.size)
        assertEquals(2, topology.canonicalLenses.single().profiles.size)
    }

    @Test
    fun conflictingBackendEvidencePreventsOpticalMerge() {
        val related = complete("opaque-logical").copy(
            physicalId = PhysicalCameraId("opaque-physical"),
        )
        val conflicting = complete("opaque-logical").copy(
            physicalId = PhysicalCameraId("opaque-physical"),
            focalLengthsMillimetres = listOf(9.0f),
        )
        val direct = complete("opaque-physical")
        val topology = CameraTopologyResolver.resolve(
            environment,
            listOf(
                snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(related, direct)),
                snapshot(CameraRouteSource.NDK_ADVERTISED, listOf(conflicting)),
            ),
            10L,
        )
        assertEquals(2, topology.canonicalLenses.size)
    }

    @Test
    fun enrichmentPromotesFallbackCanonicalIdentityButPreservesExactProfile() {
        val minimal = CameraTopologyResolver.resolve(
            environment,
            listOf(
                snapshot(
                    CameraRouteSource.JAVA_PUBLIC,
                    listOf(
                        CameraMetadataEvidence(
                            source = CameraRouteSource.JAVA_PUBLIC,
                            transportId = CameraTransportId("opaque-a"),
                            facing = LensFacing.BACK,
                        ),
                    ),
                ),
            ),
            5L,
        )
        val enriched = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("opaque-a")))),
            10L,
            previousTrustedTopology = minimal,
        )
        org.junit.Assert.assertTrue(minimal.canonicalLenses.single().fingerprint.value.startsWith("lens:fallback:"))
        org.junit.Assert.assertTrue(enriched.canonicalLenses.single().fingerprint.value.startsWith("lens:optical:"))
        org.junit.Assert.assertNotEquals(
            minimal.canonicalLenses.single().fingerprint,
            enriched.canonicalLenses.single().fingerprint,
        )
        assertEquals(
            minimal.canonicalLenses.single().profiles.single().fingerprint,
            enriched.canonicalLenses.single().profiles.single().fingerprint,
        )
    }

    @Test
    fun reconciliationNeverDowngradesEstablishedRouteTrust() {
        val initial = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("opaque-a")))),
            5L,
        )
        val trustedRoutes = initial.routes.map { route ->
            route.copy(
                metadataTrust = CameraTrust.VERIFIED,
                previewTrust = PreviewTrust.STRUCTURALLY_REJECTED,
                rawTrust = RawTrust.VERIFIED,
            )
        }
        val routesById = trustedRoutes.associateBy { it.id }
        val trusted = initial.copy(
            routes = trustedRoutes,
            canonicalLenses = initial.canonicalLenses.map { lens ->
                lens.copy(
                    profiles = lens.profiles.map { profile ->
                        profile.copy(route = routesById.getValue(profile.route.id))
                    },
                )
            },
        )
        val reconciled = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.NDK_ADVERTISED, listOf(complete("opaque-a")))),
            10L,
            previousTrustedTopology = trusted,
        )
        assertEquals(CameraTrust.VERIFIED, reconciled.routes.single().metadataTrust)
        assertEquals(PreviewTrust.STRUCTURALLY_REJECTED, reconciled.routes.single().previewTrust)
        assertEquals(RawTrust.VERIFIED, reconciled.routes.single().rawTrust)
    }

    @Test
    fun conflictingDuplicateEvidenceOrderIsDeterministic() {
        val first = complete("opaque-a").copy(focalLengthsMillimetres = listOf(4.0f))
        val second = complete("opaque-a").copy(focalLengthsMillimetres = listOf(9.0f))
        val forward = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(first, second))),
            10L,
        )
        val reverse = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(second, first))),
            10L,
        )
        assertEquals(forward, reverse)
    }

    @Test
    fun resolvedTopologyDoesNotAliasMutableEvidenceCollections() {
        val mutableFocals = mutableListOf(4.2f)
        val mutableEvidence = mutableListOf(
            complete("opaque-a").copy(focalLengthsMillimetres = mutableFocals),
        )
        val topology = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, mutableEvidence)),
            10L,
        )
        mutableFocals += 9.0f
        mutableEvidence.clear()
        assertEquals(listOf(4.2f), topology.evidence.single().focalLengthsMillimetres)
        assertThrows(UnsupportedOperationException::class.java) {
            (topology.evidence.single().focalLengthsMillimetres as MutableList).add(8.0f)
        }
    }

    @Test
    fun incompatiblePreviousSchemaCannotPreserveIdentityOrTrust() {
        val initial = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("opaque-a")))),
            5L,
        )
        val customFingerprint = CanonicalLensFingerprint("lens:old-schema")
        val oldRoute = initial.routes.single().copy(metadataTrust = CameraTrust.VERIFIED)
        val oldSchema = initial.copy(
            schema = 99,
            routes = listOf(oldRoute),
            canonicalLenses = listOf(
                CanonicalLens(
                    fingerprint = customFingerprint,
                    facing = LensFacing.BACK,
                    profiles = listOf(
                        initial.canonicalLenses.single().profiles.single().copy(
                            canonicalFingerprint = customFingerprint,
                            route = oldRoute,
                        ),
                    ),
                ),
            ),
        )
        val reconciled = CameraTopologyResolver.resolve(
            environment,
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(complete("opaque-a")))),
            10L,
            previousTrustedTopology = oldSchema,
        )
        assertEquals(CameraTrust.ADVERTISED, reconciled.routes.single().metadataTrust)
        org.junit.Assert.assertNotEquals(
            customFingerprint,
            reconciled.canonicalLenses.single().fingerprint,
        )
    }

    @Test
    fun snapshotRejectsForgedProvenanceAndNegativeCompletionTime() {
        val javaEvidence = complete("opaque-a")
        assertThrows(IllegalArgumentException::class.java) {
            CameraEvidenceSnapshot(
                source = CameraRouteSource.NDK_DEEP,
                environment = environment,
                evidence = listOf(javaEvidence),
                completedAtElapsedRealtimeNs = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraEvidenceSnapshot(
                source = CameraRouteSource.JAVA_PUBLIC,
                environment = environment,
                evidence = listOf(javaEvidence),
                completedAtElapsedRealtimeNs = -1L,
            )
        }
    }

    private fun complete(id: String) = CameraMetadataEvidence(
        source = CameraRouteSource.JAVA_PUBLIC,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        apertureValues = listOf(1.8f),
        colorFilterArrangement = 0,
        capabilities = CameraCapabilities(),
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
