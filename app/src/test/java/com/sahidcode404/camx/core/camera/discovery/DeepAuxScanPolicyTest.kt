package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepAuxScanPolicyTest {
    private val environment = CameraEnvironmentFingerprint("policy-test")

    @Test
    fun `warm compatible cache with credible deep route uses hot only`() {
        val decision = DeepAuxScanPolicy.decide(
            compatible(successful = listOf("opaque-hidden")),
        )

        assertEquals(DeepAuxScanState.HOT_ONLY, decision.state)
        assertEquals(DeepAuxScanReason.WARM_CREDIBLE_DEEP, decision.reason)
    }

    @Test
    fun `warm compatible stable empty knowledge skips deep scanning`() {
        val decision = DeepAuxScanPolicy.decide(compatible())

        assertEquals(DeepAuxScanState.SKIP, decision.state)
    }

    @Test
    fun `no compatible knowledge triggers full bounded reconciliation`() {
        val decision = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(cacheState = DeepAuxCacheState.MISSING),
        )

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
        assertEquals(DeepAuxScanReason.NO_COMPATIBLE_KNOWLEDGE, decision.reason)
    }

    @Test
    fun `environment invalidation represented as cache miss triggers full reconciliation`() {
        val decision = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(cacheState = DeepAuxCacheState.MISSING),
        )

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
    }

    @Test
    fun `advertised topology signature change triggers full reconciliation`() {
        val decision = DeepAuxScanPolicy.decide(
            compatible(successful = listOf("hidden")).copy(
                cachedAdvertisedTopologySignature = "old",
                currentAdvertisedTopologySignature = "new",
            ),
        )

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
        assertEquals(DeepAuxScanReason.ADVERTISED_TOPOLOGY_CHANGED, decision.reason)
    }

    @Test
    fun `temporary advertised backend uncertainty does not invalidate warm deep knowledge`() {
        val decision = DeepAuxScanPolicy.decide(
            compatible(successful = listOf("hidden")).copy(
                cachedAdvertisedTopologySignature = "old",
                currentAdvertisedTopologySignature = "partial-new",
                advertisedTopologyReliable = false,
            ),
        )

        assertEquals(DeepAuxScanState.HOT_ONLY, decision.state)
    }

    @Test
    fun `corrupt cache fails closed into full reconciliation`() {
        val decision = DeepAuxScanPolicy.decide(
            DeepAuxScanPolicyInput(cacheState = DeepAuxCacheState.CORRUPT_OR_INCOMPATIBLE),
        )

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
        assertEquals(DeepAuxScanReason.CACHE_INVALID, decision.reason)
    }

    @Test
    fun `incomplete prior reconciliation is never treated as warm stable`() {
        val decision = DeepAuxScanPolicy.decide(
            compatible(successful = listOf("hidden")).copy(previousFullReconciliationComplete = false),
        )

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
        assertEquals(DeepAuxScanReason.INCOMPLETE_RECONCILIATION, decision.reason)
    }

    @Test
    fun `explicit rescan request forces full reconciliation`() {
        val decision = DeepAuxScanPolicy.decide(
            compatible(successful = listOf("hidden")).copy(explicitDeepRescan = true),
        )

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, decision.state)
        assertEquals(DeepAuxScanReason.EXPLICIT_RESCAN, decision.reason)
    }

    @Test
    fun `hot only candidate plan contains no nearby or low namespace probes`() {
        val plan = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySessionVerifiedDeepIds = listOf("verified-opaque"),
                previouslySuccessfulDeepIds = listOf("successful-opaque"),
                advertisedIds = listOf("7"),
                includeNearbyCandidates = false,
                includeLowNamespaceCandidates = false,
            ),
        )

        assertEquals(listOf(DeepAuxWave.HOT), plan.candidates.map { it.wave }.distinct())
        assertEquals(setOf("verified-opaque", "successful-opaque"), plan.candidates.map { it.transportId }.toSet())
        assertFalse(plan.candidates.any { it.transportId in (0..31).map(Int::toString) })
    }

    @Test
    fun `session verified ids remain ahead of successful ids in hot planning`() {
        val plan = DeepAuxCandidatePlanner.plan(
            DeepAuxDiscoveryRequest(
                previouslySessionVerifiedDeepIds = listOf("verified"),
                previouslySuccessfulDeepIds = listOf("successful"),
                includeNearbyCandidates = false,
                includeLowNamespaceCandidates = false,
            ),
        )

        assertEquals(listOf("verified", "successful"), plan.candidates.map { it.transportId })
    }

    @Test
    fun `advertised topology signature is permutation invariant`() {
        val java = snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("opaque-A", CameraRouteSource.JAVA_PUBLIC))
        val ndk = snapshot(CameraRouteSource.NDK_ADVERTISED, evidence("17", CameraRouteSource.NDK_ADVERTISED))

        val first = AdvertisedTopologySignature.compute(listOf(java, ndk))
        val second = AdvertisedTopologySignature.compute(listOf(ndk, java))

        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun `deep evidence does not perturb advertised topology signature`() {
        val java = snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("opaque-A", CameraRouteSource.JAVA_PUBLIC))
        val deep = snapshot(CameraRouteSource.NDK_DEEP, evidence("99", CameraRouteSource.NDK_DEEP))

        assertEquals(
            AdvertisedTopologySignature.compute(listOf(java)),
            AdvertisedTopologySignature.compute(listOf(java, deep)),
        )
    }

    @Test
    fun `numeric transport ids remain opaque evidence rather than lens roles`() {
        val zero = AdvertisedTopologySignature.compute(
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("0", CameraRouteSource.JAVA_PUBLIC))),
        )
        val word = AdvertisedTopologySignature.compute(
            listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, evidence("opaque-main-looking", CameraRouteSource.JAVA_PUBLIC))),
        )

        assertTrue(zero.isNotBlank())
        assertTrue(word.isNotBlank())
        assertFalse(zero == word)
    }

    private fun compatible(successful: List<String> = emptyList()) = DeepAuxScanPolicyInput(
        cacheState = DeepAuxCacheState.COMPATIBLE,
        cachedAdvertisedTopologySignature = "stable",
        currentAdvertisedTopologySignature = "stable",
        cachedSuccessfulDeepIds = successful,
        previousFullReconciliationComplete = true,
    )

    private fun snapshot(source: CameraRouteSource, evidence: CameraMetadataEvidence) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = listOf(evidence),
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun evidence(id: String, source: CameraRouteSource) = CameraMetadataEvidence(
        source = source,
        transportId = CameraTransportId(id),
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(4.2f),
        sensorPhysicalWidthMillimetres = 5.6f,
        sensorPhysicalHeightMillimetres = 4.2f,
        activeArray = IntSize(4000, 3000),
        pixelArray = IntSize(4032, 3024),
        sensorOrientationDegrees = 90,
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(
                    PreviewStreamType.CAMERA2_PRIVATE,
                    IntSize(1280, 720),
                    33_333_333L,
                ),
            ),
            fpsRanges = listOf(CameraFpsCapability(30, 30)),
        ),
    )
}
