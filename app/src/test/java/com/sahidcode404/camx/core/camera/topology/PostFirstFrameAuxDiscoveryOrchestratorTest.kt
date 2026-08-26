package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.cache.CacheRead
import com.sahidcode404.camx.core.camera.cache.CacheWrite
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledge
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledgePersistence
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledgeRepository
import com.sahidcode404.camx.core.camera.discovery.AdvertisedTopologySignature
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCandidate
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCandidateOutcome
import com.sahidcode404.camx.core.camera.discovery.DeepAuxDiscoveryRequest
import com.sahidcode404.camx.core.camera.discovery.DeepAuxOutcomeKind
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanState
import com.sahidcode404.camx.core.camera.discovery.DeepAuxWave
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationKind
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationOutcome
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedRuntimeState
import com.sahidcode404.camx.core.camera.discovery.NdkDeepEvidenceReport
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFirstFrameAuxDiscoveryOrchestratorTest {
    private val environment = CameraEnvironmentFingerprint("orchestrator-test")

    @Test
    fun `Level 2 Java and NDK collection uses explicit bounded concurrency`() = runTest {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        suspend fun enter() {
            val now = active.incrementAndGet()
            while (true) {
                val previous = maximum.get()
                if (now <= previous || maximum.compareAndSet(previous, now)) break
            }
            yield()
            active.decrementAndGet()
        }
        val javaReport = javaLevel2Report()
        val ndkReport = ndkLevel2Report()
        val repository = repository(null)
        val orchestrator = orchestrator(
            repository = repository,
            java = JavaLevel2EvidenceSource { emit ->
                enter()
                emit(javaReport)
                javaReport
            },
            ndk = NdkLevel2EvidenceSource {
                enter()
                ndkReport
            },
            deep = emptyDeepSource(),
            cert = emptyCertSource(),
        )

        orchestrator.collectReport {}

        assertEquals(2, maximum.get())
    }

    @Test
    fun `no cache performs full reconciliation and persists stable empty knowledge`() = runTest {
        val repository = repository(null)
        var request: DeepAuxDiscoveryRequest? = null
        val orchestrator = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { current, _ ->
                request = current
                emptyDeepReport()
            },
            cert = emptyCertSource(),
        )

        val report = orchestrator.collectReport {}

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, report.initialDecision.state)
        assertTrue(request!!.includeNearbyCandidates)
        assertTrue(request!!.includeLowNamespaceCandidates)
        assertTrue(report.fullReconciliationCompleted)
        assertTrue(repository.current()!!.fullReconciliationComplete)
    }

    @Test
    fun `warm compatible cache probes only hot ids`() = runTest {
        val level2 = level2Snapshots()
        val signature = AdvertisedTopologySignature.compute(level2)
        val repository = repository(knowledge(signature, successful = listOf("opaque-hidden")))
        var request: DeepAuxDiscoveryRequest? = null
        val orchestrator = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { current, emit ->
                request = current
                val report = deepValidReport("opaque-hidden")
                emit(report)
                report
            },
            cert = certifyingSource(),
        )

        val report = orchestrator.collectReport {}

        assertEquals(DeepAuxScanState.HOT_ONLY, report.initialDecision.state)
        assertFalse(request!!.includeNearbyCandidates)
        assertFalse(request!!.includeLowNamespaceCandidates)
        assertEquals(listOf("opaque-hidden"), request!!.previouslySuccessfulDeepIds)
        assertEquals(listOf("opaque-hidden"), report.certifiedDeepIds)
    }

    @Test
    fun `NDK deep publication precedes Java deep certification publication`() = runTest {
        val signature = AdvertisedTopologySignature.compute(level2Snapshots())
        val repository = repository(knowledge(signature, successful = listOf("hidden")))
        val events = ArrayList<CameraRouteSource>()
        val orchestrator = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, emit ->
                val first = deepValidReport("hidden")
                emit(first)
                val later = deepValidReport("later")
                emit(later)
                NdkDeepEvidenceReport(
                    snapshot = snapshot(CameraRouteSource.NDK_DEEP, listOf(ndkDeepEvidence("hidden"), ndkDeepEvidence("later"))),
                    outcomes = first.outcomes + later.outcomes,
                    failures = emptyList(),
                )
            },
            cert = certifyingSource(),
        )

        orchestrator.collectReport { batch ->
            batch.filter { it.evidence.isNotEmpty() }.forEach { events += it.source }
        }

        val deepIndex = events.indexOf(CameraRouteSource.NDK_DEEP)
        val javaIndex = events.indexOf(CameraRouteSource.JAVA_DEEP_PROBED)
        assertTrue(deepIndex >= 0)
        assertTrue(javaIndex > deepIndex)
    }

    @Test
    fun `temporary deep backend failure preserves compatible cached knowledge`() = runTest {
        val signature = AdvertisedTopologySignature.compute(level2Snapshots())
        val repository = repository(knowledge(signature, successful = listOf("keep-hidden")))
        val orchestrator = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, emit ->
                val candidate = DeepAuxCandidate("keep-hidden", DeepAuxWave.HOT)
                val report = NdkDeepEvidenceReport(
                    snapshot = snapshot(CameraRouteSource.NDK_DEEP, emptyList()),
                    outcomes = listOf(DeepAuxCandidateOutcome(candidate, DeepAuxOutcomeKind.SERVICE_ERROR)),
                    failures = emptyList(),
                )
                emit(report)
                report
            },
            cert = emptyCertSource(),
        )

        val report = orchestrator.collectReport {}

        assertEquals(DeepAuxScanState.HOT_ONLY, report.finalDecision.state)
        assertTrue("keep-hidden" in repository.current()!!.successfulDeepIds)
        assertTrue(repository.current()!!.fullReconciliationComplete)
    }

    @Test
    fun `conclusive hot incompatibility escalates to full and may retire stale knowledge`() = runTest {
        val signature = AdvertisedTopologySignature.compute(level2Snapshots())
        val repository = repository(knowledge(signature, successful = listOf("stale-hidden")))
        var deepCalls = 0
        val orchestrator = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, emit ->
                deepCalls += 1
                val report = deepValidReport("stale-hidden")
                emit(report)
                report
            },
            cert = JavaDeepCertificationSource { outcomes, _, emit ->
                val candidate = outcomes.single().candidate
                val report = JavaDeepCertificationReport(
                    snapshot = snapshot(CameraRouteSource.JAVA_DEEP_PROBED, emptyList()),
                    outcomes = listOf(
                        JavaDeepCertificationOutcome(candidate, JavaDeepCertificationKind.JAVA_NOT_FOUND),
                    ),
                )
                emit(report)
                report
            },
        )

        val report = orchestrator.collectReport {}

        assertEquals(2, deepCalls)
        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, report.finalDecision.state)
        assertTrue(report.fullReconciliationCompleted)
        assertFalse("stale-hidden" in repository.current()!!.successfulDeepIds)
    }

    @Test
    fun `warm stable empty cache avoids any deep call`() = runTest {
        val signature = AdvertisedTopologySignature.compute(level2Snapshots())
        val repository = repository(knowledge(signature))
        var deepCalls = 0
        val orchestrator = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, _ ->
                deepCalls += 1
                emptyDeepReport()
            },
            cert = emptyCertSource(),
        )

        val report = orchestrator.collectReport {}

        assertEquals(DeepAuxScanState.SKIP, report.initialDecision.state)
        assertEquals(0, deepCalls)
    }

    private fun orchestrator(
        repository: DeepDiscoveryKnowledgeRepository,
        java: JavaLevel2EvidenceSource = JavaLevel2EvidenceSource { emit ->
            val report = javaLevel2Report()
            emit(report)
            report
        },
        ndk: NdkLevel2EvidenceSource = NdkLevel2EvidenceSource { ndkLevel2Report() },
        deep: NdkDeepEvidenceSource,
        cert: JavaDeepCertificationSource,
    ) = PostFirstFrameAuxDiscoveryOrchestrator(
        environment = environment,
        javaLevel2 = java,
        ndkLevel2 = ndk,
        ndkDeep = deep,
        javaDeep = cert,
        deepKnowledge = repository,
        runtimeApiLevel = { 24 },
    )

    private fun javaLevel2Report() = JavaAdvertisedEvidenceReport(
        snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(javaPublicEvidence("0")))),
        failures = emptyList(),
    )

    private fun ndkLevel2Report() = NdkAdvertisedEvidenceReport(
        snapshot = snapshot(CameraRouteSource.NDK_ADVERTISED, listOf(ndkAdvertisedEvidence("0"))),
        runtimeState = NdkAdvertisedRuntimeState.AVAILABLE,
        failures = emptyList(),
    )

    private fun level2Snapshots() = javaLevel2Report().snapshots + ndkLevel2Report().snapshot

    private fun emptyDeepSource() = NdkDeepEvidenceSource { _, _ -> emptyDeepReport() }

    private fun emptyDeepReport() = NdkDeepEvidenceReport(
        snapshot = snapshot(CameraRouteSource.NDK_DEEP, emptyList()),
        outcomes = emptyList(),
        failures = emptyList(),
    )

    private fun emptyCertSource() = JavaDeepCertificationSource { _, _, _ ->
        JavaDeepCertificationReport(
            snapshot = snapshot(CameraRouteSource.JAVA_DEEP_PROBED, emptyList()),
            outcomes = emptyList(),
        )
    }

    private fun certifyingSource() = JavaDeepCertificationSource { outcomes, _, emit ->
        val certified = outcomes.filter { it.outcome == DeepAuxOutcomeKind.VALID_METADATA }.map { outcome ->
            JavaDeepCertificationOutcome(outcome.candidate, JavaDeepCertificationKind.CERTIFIED)
        }
        val evidence = certified.map { javaDeepEvidence(it.candidate.transportId) }
        val report = JavaDeepCertificationReport(
            snapshot = snapshot(CameraRouteSource.JAVA_DEEP_PROBED, evidence),
            outcomes = certified,
        )
        emit(report)
        report
    }

    private fun deepValidReport(id: String): NdkDeepEvidenceReport {
        val candidate = DeepAuxCandidate(id, DeepAuxWave.HOT)
        return NdkDeepEvidenceReport(
            snapshot = snapshot(CameraRouteSource.NDK_DEEP, listOf(ndkDeepEvidence(id))),
            outcomes = listOf(DeepAuxCandidateOutcome(candidate, DeepAuxOutcomeKind.VALID_METADATA)),
            failures = emptyList(),
        )
    }

    private fun snapshot(source: CameraRouteSource, evidence: List<CameraMetadataEvidence>) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence,
        completedAtElapsedRealtimeNs = 1L,
    )

    private fun javaPublicEvidence(id: String) = baseEvidence(id, CameraRouteSource.JAVA_PUBLIC)
    private fun ndkAdvertisedEvidence(id: String) = baseEvidence(id, CameraRouteSource.NDK_ADVERTISED)
    private fun ndkDeepEvidence(id: String) = baseEvidence(id, CameraRouteSource.NDK_DEEP)
    private fun javaDeepEvidence(id: String) = baseEvidence(id, CameraRouteSource.JAVA_DEEP_PROBED)

    private fun baseEvidence(id: String, source: CameraRouteSource) = CameraMetadataEvidence(
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

    private fun knowledge(
        signature: String,
        successful: List<String> = emptyList(),
        verified: List<String> = emptyList(),
    ) = DeepDiscoveryKnowledge(
        schema = CameraSchemaVersions.DEEP_DISCOVERY,
        environment = environment,
        advertisedTopologySignature = signature,
        successfulDeepIds = successful,
        sessionVerifiedDeepIds = verified,
        fullReconciliationComplete = true,
    ).frozenCopy()

    private fun repository(initial: DeepDiscoveryKnowledge?): DeepDiscoveryKnowledgeRepository =
        DeepDiscoveryKnowledgeRepository(MemoryPersistence(initial))

    private class MemoryPersistence(initial: DeepDiscoveryKnowledge?) : DeepDiscoveryKnowledgePersistence {
        private var value = initial

        override suspend fun readDeepKnowledge(
            environment: CameraEnvironmentFingerprint,
        ): CacheRead<DeepDiscoveryKnowledge> {
            val current = value ?: return CacheRead.Miss
            return if (current.environment == environment) CacheRead.Hit(current) else CacheRead.Miss
        }

        override suspend fun writeDeepKnowledge(knowledge: DeepDiscoveryKnowledge): CacheWrite {
            value = knowledge
            return CacheWrite.Success
        }
    }
}
