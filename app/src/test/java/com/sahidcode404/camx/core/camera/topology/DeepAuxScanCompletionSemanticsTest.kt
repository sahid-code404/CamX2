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
import com.sahidcode404.camx.core.camera.discovery.DeepAuxDiscoveryLimits
import com.sahidcode404.camx.core.camera.discovery.DeepAuxDiscoveryRequest
import com.sahidcode404.camx.core.camera.discovery.DeepAuxOutcomeKind
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanState
import com.sahidcode404.camx.core.camera.discovery.DeepAuxWave
import com.sahidcode404.camx.core.camera.discovery.DiscoveryMetadataBudget
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationKind
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationOutcome
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedRuntimeState
import com.sahidcode404.camx.core.camera.discovery.NdkDeepAuxDiscoveryBackend
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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepAuxScanCompletionSemanticsTest {
    private val environment = CameraEnvironmentFingerprint("deep-completion-test")

    @Test
    fun `mostly missing full scan completes persists found aux and next launch is hot only`() = runTest {
        val repository = repository(null)
        val outcomes = (0..31).map { value ->
            candidateOutcome(
                value.toString(),
                when (value) {
                    2 -> DeepAuxOutcomeKind.VALID_METADATA
                    4 -> DeepAuxOutcomeKind.ACCESS_DENIED
                    else -> DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE
                },
            )
        }
        var fullRequest: DeepAuxDiscoveryRequest? = null
        val first = orchestrator(
            repository = repository,
            deep = reportingDeepSource(outcomes) { fullRequest = it },
            cert = certifyingSource(),
        ).collectReport {}

        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, first.initialDecision.state)
        assertTrue(fullRequest!!.includeNearbyCandidates)
        assertTrue(fullRequest!!.includeLowNamespaceCandidates)
        assertTrue(first.fullReconciliationCompleted)
        assertEquals(listOf("2"), first.certifiedDeepIds)
        assertEquals(listOf("2"), repository.current()!!.successfulDeepIds)
        assertTrue(repository.current()!!.sessionVerifiedDeepIds.isEmpty())
        assertTrue(repository.current()!!.fullReconciliationComplete)

        var hotRequest: DeepAuxDiscoveryRequest? = null
        val second = orchestrator(
            repository = repository,
            deep = reportingDeepSource(listOf(candidateOutcome("2", DeepAuxOutcomeKind.VALID_METADATA))) {
                hotRequest = it
            },
            cert = certifyingSource(),
        ).collectReport {}

        assertEquals(DeepAuxScanState.HOT_ONLY, second.initialDecision.state)
        assertEquals(listOf("2"), hotRequest!!.previouslySuccessfulDeepIds)
        assertFalse(hotRequest!!.includeNearbyCandidates)
        assertFalse(hotRequest!!.includeLowNamespaceCandidates)
    }

    @Test
    fun `stable empty bounded scan completes and next launch skips deep`() = runTest {
        val repository = repository(null)
        val absent = (0..31).map { candidateOutcome(it.toString(), DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE) }
        val first = orchestrator(
            repository = repository,
            deep = reportingDeepSource(absent),
            cert = emptyCertSource(),
        ).collectReport {}

        assertTrue(first.fullReconciliationCompleted)
        assertTrue(repository.current()!!.fullReconciliationComplete)
        assertTrue(repository.current()!!.successfulDeepIds.isEmpty())
        assertTrue(repository.current()!!.sessionVerifiedDeepIds.isEmpty())

        var deepCalls = 0
        val second = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, _ ->
                deepCalls += 1
                deepReport(emptyList())
            },
            cert = emptyCertSource(),
        ).collectReport {}

        assertEquals(DeepAuxScanState.SKIP, second.initialDecision.state)
        assertEquals(0, deepCalls)
    }

    @Test
    fun `access denied is terminal diagnostic evidence and does not poison pass`() = runTest {
        val repository = repository(null)
        val outcomes = (0..7).map { value ->
            candidateOutcome(
                value.toString(),
                if (value == 4) DeepAuxOutcomeKind.ACCESS_DENIED else DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE,
            )
        }
        var certificationInputs = 0
        val cert = JavaDeepCertificationSource { ndkOutcomes, _, _ ->
            certificationInputs += ndkOutcomes.count { it.outcome == DeepAuxOutcomeKind.VALID_METADATA }
            emptyCertificationReport()
        }

        val report = orchestrator(
            repository = repository,
            deep = reportingDeepSource(outcomes),
            cert = cert,
        ).collectReport {}

        assertTrue(report.fullReconciliationCompleted)
        assertEquals(0, certificationInputs)
        assertTrue(repository.current()!!.successfulDeepIds.isEmpty())
        assertTrue(repository.current()!!.fullReconciliationComplete)
    }

    @Test
    fun `java access denied after valid ndk metadata is terminal but not successful`() = runTest {
        val repository = repository(null)
        val candidate = candidateOutcome("hidden", DeepAuxOutcomeKind.VALID_METADATA)
        val report = orchestrator(
            repository = repository,
            deep = reportingDeepSource(listOf(candidate)),
            cert = JavaDeepCertificationSource { outcomes, _, emit ->
                val certification = JavaDeepCertificationReport(
                    snapshot = snapshot(CameraRouteSource.JAVA_DEEP_PROBED, emptyList()),
                    outcomes = listOf(
                        JavaDeepCertificationOutcome(
                            outcomes.single().candidate,
                            JavaDeepCertificationKind.JAVA_ACCESS_DENIED,
                        ),
                    ),
                )
                emit(certification)
                certification
            },
        ).collectReport {}

        assertTrue(report.fullReconciliationCompleted)
        assertTrue(report.certifiedDeepIds.isEmpty())
        assertTrue(repository.current()!!.successfulDeepIds.isEmpty())
    }

    @Test
    fun `service runtime and temporary camera failures keep full reconciliation incomplete`() = runTest {
        val temporaryKinds = listOf(
            DeepAuxOutcomeKind.SERVICE_ERROR,
            DeepAuxOutcomeKind.RUNTIME_UNAVAILABLE,
            DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE,
        )
        temporaryKinds.forEach { kind ->
            val repository = repository(null)
            val report = orchestrator(
                repository = repository,
                deep = reportingDeepSource(listOf(candidateOutcome("candidate", kind))),
                cert = emptyCertSource(),
            ).collectReport {}

            assertFalse("$kind must keep the full pass incomplete", report.fullReconciliationCompleted)
            assertNull(repository.current())
        }
    }

    @Test
    fun `whole deep provider failure keeps full reconciliation incomplete`() = runTest {
        val repository = repository(null)
        val report = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, _ -> error("provider failed") },
            cert = emptyCertSource(),
        ).collectReport {}

        assertFalse(report.fullReconciliationCompleted)
        assertNull(repository.current())
    }

    @Test
    fun `temporary failures preserve cached successful and session verified knowledge`() = runTest {
        val signature = AdvertisedTopologySignature.compute(level2Snapshots())
        val successfulRepository = repository(knowledge(signature, successful = listOf("keep-success")))
        orchestrator(
            repository = successfulRepository,
            deep = reportingDeepSource(listOf(candidateOutcome("keep-success", DeepAuxOutcomeKind.SERVICE_ERROR))),
            cert = emptyCertSource(),
        ).collectReport {}
        assertEquals(listOf("keep-success"), successfulRepository.current()!!.successfulDeepIds)
        assertTrue(successfulRepository.current()!!.fullReconciliationComplete)

        val verifiedRepository = repository(knowledge(signature, verified = listOf("keep-verified")))
        orchestrator(
            repository = verifiedRepository,
            deep = reportingDeepSource(
                listOf(candidateOutcome("keep-verified", DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE)),
            ),
            cert = emptyCertSource(),
        ).collectReport {}
        assertEquals(listOf("keep-verified"), verifiedRepository.current()!!.sessionVerifiedDeepIds)
        assertTrue(verifiedRepository.current()!!.fullReconciliationComplete)
    }

    @Test
    fun `conclusive cached Java incompatibility still escalates and retires`() = runTest {
        val signature = AdvertisedTopologySignature.compute(level2Snapshots())
        val repository = repository(knowledge(signature, successful = listOf("stale")))
        var deepCalls = 0
        val report = orchestrator(
            repository = repository,
            deep = NdkDeepEvidenceSource { _, emit ->
                deepCalls += 1
                val current = deepReport(listOf(candidateOutcome("stale", DeepAuxOutcomeKind.VALID_METADATA)))
                emit(current)
                current
            },
            cert = JavaDeepCertificationSource { outcomes, _, emit ->
                val certification = JavaDeepCertificationReport(
                    snapshot = snapshot(CameraRouteSource.JAVA_DEEP_PROBED, emptyList()),
                    outcomes = listOf(
                        JavaDeepCertificationOutcome(
                            outcomes.single().candidate,
                            JavaDeepCertificationKind.JAVA_NOT_FOUND,
                        ),
                    ),
                )
                emit(certification)
                certification
            },
        ).collectReport {}

        assertEquals(2, deepCalls)
        assertEquals(DeepAuxScanState.FULL_RECONCILIATION, report.finalDecision.state)
        assertTrue(report.fullReconciliationCompleted)
        assertFalse("stale" in repository.current()!!.successfulDeepIds)
    }

    @Test
    fun `input outcome ordering does not affect final cache or next policy`() = runTest {
        val outcomes = listOf(
            candidateOutcome("7", DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE),
            candidateOutcome("2", DeepAuxOutcomeKind.VALID_METADATA),
            candidateOutcome("4", DeepAuxOutcomeKind.ACCESS_DENIED),
            candidateOutcome("9", DeepAuxOutcomeKind.INVALID_OPERATION),
        )
        val firstRepository = repository(null)
        val secondRepository = repository(null)

        val first = orchestrator(
            repository = firstRepository,
            deep = reportingDeepSource(outcomes),
            cert = certifyingSource(),
        ).collectReport {}
        val second = orchestrator(
            repository = secondRepository,
            deep = reportingDeepSource(outcomes.reversed()),
            cert = certifyingSource(),
        ).collectReport {}

        assertEquals(first.fullReconciliationCompleted, second.fullReconciliationCompleted)
        assertEquals(firstRepository.current(), secondRepository.current())

        val firstNext = orchestrator(
            repository = firstRepository,
            deep = reportingDeepSource(listOf(candidateOutcome("2", DeepAuxOutcomeKind.VALID_METADATA))),
            cert = certifyingSource(),
        ).collectReport {}
        val secondNext = orchestrator(
            repository = secondRepository,
            deep = reportingDeepSource(listOf(candidateOutcome("2", DeepAuxOutcomeKind.VALID_METADATA))),
            cert = certifyingSource(),
        ).collectReport {}
        assertEquals(DeepAuxScanState.HOT_ONLY, firstNext.initialDecision.state)
        assertEquals(firstNext.initialDecision, secondNext.initialDecision)
    }

    @Test
    fun `native camera unavailable and invalid operation remain distinct deep outcomes`() = runTest {
        suspend fun read(code: Int): DeepAuxOutcomeKind {
            val backend = NdkDeepAuxDiscoveryBackend(
                environment = environment,
                metadataBudget = DiscoveryMetadataBudget(),
                deviceApi = { 24 },
                clockNanos = { 1L },
                rawCollector = { _, _ -> failurePayload(code, "hidden") },
            )
            val report = backend.discover(
                DeepAuxDiscoveryRequest(
                    previouslySuccessfulDeepIds = listOf("hidden"),
                    includeNearbyCandidates = false,
                    includeLowNamespaceCandidates = false,
                    limits = DeepAuxDiscoveryLimits(maximumCandidateCount = 1),
                ),
            )
            return report.outcomes.single().outcome
        }

        assertEquals(DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE, read(9))
        assertEquals(DeepAuxOutcomeKind.INVALID_OPERATION, read(10))
        assertEquals(DeepAuxOutcomeKind.ACCESS_DENIED, read(7))
        assertEquals(DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE, read(4))
    }

    private fun orchestrator(
        repository: DeepDiscoveryKnowledgeRepository,
        deep: NdkDeepEvidenceSource,
        cert: JavaDeepCertificationSource,
    ) = PostFirstFrameAuxDiscoveryOrchestrator(
        environment = environment,
        javaLevel2 = JavaLevel2EvidenceSource { emit ->
            val report = javaLevel2Report()
            emit(report)
            report
        },
        ndkLevel2 = NdkLevel2EvidenceSource { ndkLevel2Report() },
        ndkDeep = deep,
        javaDeep = cert,
        deepKnowledge = repository,
        runtimeApiLevel = { 24 },
    )

    private fun reportingDeepSource(
        outcomes: List<DeepAuxCandidateOutcome>,
        captureRequest: (DeepAuxDiscoveryRequest) -> Unit = {},
    ) = NdkDeepEvidenceSource { request, emit ->
        captureRequest(request)
        val report = deepReport(outcomes)
        emit(report)
        report
    }

    private fun certifyingSource() = JavaDeepCertificationSource { outcomes, _, emit ->
        val certified = outcomes.filter { it.outcome == DeepAuxOutcomeKind.VALID_METADATA }
            .map { JavaDeepCertificationOutcome(it.candidate, JavaDeepCertificationKind.CERTIFIED) }
        val report = JavaDeepCertificationReport(
            snapshot = snapshot(
                CameraRouteSource.JAVA_DEEP_PROBED,
                certified.map { javaDeepEvidence(it.candidate.transportId) },
            ),
            outcomes = certified,
        )
        emit(report)
        report
    }

    private fun emptyCertSource() = JavaDeepCertificationSource { _, _, _ -> emptyCertificationReport() }

    private fun emptyCertificationReport() = JavaDeepCertificationReport(
        snapshot = snapshot(CameraRouteSource.JAVA_DEEP_PROBED, emptyList()),
        outcomes = emptyList(),
    )

    private fun deepReport(outcomes: List<DeepAuxCandidateOutcome>) = NdkDeepEvidenceReport(
        snapshot = snapshot(
            CameraRouteSource.NDK_DEEP,
            outcomes.filter { it.outcome == DeepAuxOutcomeKind.VALID_METADATA }
                .map { ndkDeepEvidence(it.candidate.transportId) },
        ),
        outcomes = outcomes,
        failures = emptyList(),
    )

    private fun candidateOutcome(id: String, kind: DeepAuxOutcomeKind) = DeepAuxCandidateOutcome(
        DeepAuxCandidate(id, DeepAuxWave.LOW_NAMESPACE),
        kind,
    )

    private fun javaLevel2Report() = JavaAdvertisedEvidenceReport(
        snapshots = listOf(snapshot(CameraRouteSource.JAVA_PUBLIC, listOf(baseEvidence("0", CameraRouteSource.JAVA_PUBLIC)))),
        failures = emptyList(),
    )

    private fun ndkLevel2Report() = NdkAdvertisedEvidenceReport(
        snapshot = snapshot(
            CameraRouteSource.NDK_ADVERTISED,
            listOf(baseEvidence("0", CameraRouteSource.NDK_ADVERTISED)),
        ),
        runtimeState = NdkAdvertisedRuntimeState.AVAILABLE,
        failures = emptyList(),
    )

    private fun level2Snapshots() = javaLevel2Report().snapshots + ndkLevel2Report().snapshot

    private fun snapshot(source: CameraRouteSource, evidence: List<CameraMetadataEvidence>) = CameraEvidenceSnapshot(
        source = source,
        environment = environment,
        evidence = evidence,
        completedAtElapsedRealtimeNs = 1L,
    )

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

    private fun failurePayload(code: Int, id: String): ByteArray {
        val writer = Writer()
        writer.raw(byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte()))
        writer.u16(1)
        writer.u8(0)
        writer.u8(0)
        writer.u16(0)
        writer.u16(1)
        writer.u8(code)
        writer.string(id)
        return writer.bytes()
    }

    private class Writer {
        private val output = ByteArrayOutputStream()
        fun bytes(): ByteArray = output.toByteArray()
        fun raw(value: ByteArray) { output.write(value) }
        fun u8(value: Int) { output.write(value and 0xff) }
        fun u16(value: Int) { u8(value); u8(value ushr 8) }
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u16(bytes.size)
            raw(bytes)
        }
    }
}
