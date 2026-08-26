package com.sahidcode404.camx.core.camera.topology

import android.os.Build
import com.sahidcode404.camx.core.camera.cache.CacheRead
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledge
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledgeRepository
import com.sahidcode404.camx.core.camera.concurrency.boundedCameraMap
import com.sahidcode404.camx.core.camera.discovery.AdvertisedTopologySignature
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.discovery.DeepAuxCacheState
import com.sahidcode404.camx.core.camera.discovery.DeepAuxDiscoveryLimits
import com.sahidcode404.camx.core.camera.discovery.DeepAuxDiscoveryRequest
import com.sahidcode404.camx.core.camera.discovery.DeepAuxOutcomeKind
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanDecision
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanPolicy
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanPolicyInput
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanReason
import com.sahidcode404.camx.core.camera.discovery.DeepAuxScanState
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceFailureKind
import com.sahidcode404.camx.core.camera.discovery.JavaAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationKind
import com.sahidcode404.camx.core.camera.discovery.JavaDeepCertificationReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedEvidenceReport
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedRuntimeState
import com.sahidcode404.camx.core.camera.discovery.NdkDeepEvidenceReport
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import java.util.Collections
import kotlinx.coroutines.CancellationException

private const val CAMERA_NDK_MIN_API_FOR_ORCHESTRATION = 24

internal fun interface JavaLevel2EvidenceSource {
    suspend fun collect(emit: suspend (JavaAdvertisedEvidenceReport) -> Unit): JavaAdvertisedEvidenceReport
}

internal fun interface NdkLevel2EvidenceSource {
    suspend fun collect(): NdkAdvertisedEvidenceReport
}

internal fun interface NdkDeepEvidenceSource {
    suspend fun collect(
        request: DeepAuxDiscoveryRequest,
        emit: suspend (NdkDeepEvidenceReport) -> Unit,
    ): NdkDeepEvidenceReport
}

internal fun interface JavaDeepCertificationSource {
    suspend fun certify(
        ndkOutcomes: List<com.sahidcode404.camx.core.camera.discovery.DeepAuxCandidateOutcome>,
        existingJavaEvidence: Collection<CameraMetadataEvidence>,
        emit: suspend (JavaDeepCertificationReport) -> Unit,
    ): JavaDeepCertificationReport
}

data class AuxDiscoveryOrchestrationReport(
    val initialDecision: DeepAuxScanDecision,
    val finalDecision: DeepAuxScanDecision,
    val level2Reliable: Boolean,
    val deepPassCount: Int,
    val certifiedDeepIds: List<String>,
    val conclusivelyRetiredDeepIds: List<String>,
    val fullReconciliationCompleted: Boolean,
)

/**
 * Formal post-first-frame Level-2 -> Level-4 orchestration. It owns no Camera2 resources and never
 * touches CameraSessionController. Every publication is current metadata evidence only.
 */
internal class PostFirstFrameAuxDiscoveryOrchestrator(
    private val environment: CameraEnvironmentFingerprint,
    private val javaLevel2: JavaLevel2EvidenceSource,
    private val ndkLevel2: NdkLevel2EvidenceSource,
    private val ndkDeep: NdkDeepEvidenceSource,
    private val javaDeep: JavaDeepCertificationSource,
    private val deepKnowledge: DeepDiscoveryKnowledgeRepository,
    private val runtimeApiLevel: () -> Int = { Build.VERSION.SDK_INT },
    private val explicitDeepRescan: () -> Boolean = { false },
) : AdvertisedTopologyEvidenceProvider {
    override suspend fun collect(emit: suspend (List<CameraEvidenceSnapshot>) -> Unit) {
        collectReport(emit)
    }

    suspend fun collectReport(
        emit: suspend (List<CameraEvidenceSnapshot>) -> Unit,
    ): AuxDiscoveryOrchestrationReport {
        val level2 = boundedCameraMap(Level2Lane.values().toList(), 2) { lane ->
            when (lane) {
                Level2Lane.JAVA -> collectJavaLevel2(emit)
                Level2Lane.NDK -> collectNdkLevel2(emit)
            }
        }
        val javaResult = level2.filterIsInstance<Level2Result.Java>().single()
        val ndkResult = level2.filterIsInstance<Level2Result.Ndk>().single()
        val level2Snapshots = ArrayList<CameraEvidenceSnapshot>()
        javaResult.report?.snapshots?.let(level2Snapshots::addAll)
        ndkResult.report?.snapshot?.let(level2Snapshots::add)
        val signature = AdvertisedTopologySignature.compute(level2Snapshots)
        val level2Reliable = isJavaLevel2Reliable(javaResult) && isNdkLevel2Reliable(ndkResult)

        val cacheRead = deepKnowledge.load(environment)
        val cached = when (cacheRead) {
            is CacheRead.Hit -> cacheRead.value
            else -> null
        }
        val initialDecision = DeepAuxScanPolicy.decide(
            policyInput(
                cacheRead = cacheRead,
                cached = cached,
                signature = signature,
                level2Reliable = level2Reliable,
            ),
        )
        if (initialDecision.state == DeepAuxScanState.SKIP) {
            return AuxDiscoveryOrchestrationReport(
                initialDecision = initialDecision,
                finalDecision = initialDecision,
                level2Reliable = level2Reliable,
                deepPassCount = 0,
                certifiedDeepIds = emptyList(),
                conclusivelyRetiredDeepIds = emptyList(),
                fullReconciliationCompleted = false,
            )
        }

        val javaEvidence = level2Snapshots.asSequence()
            .flatMap { it.evidence.asSequence() }
            .filter {
                it.source == CameraRouteSource.JAVA_PUBLIC ||
                    it.source == CameraRouteSource.JAVA_PHYSICAL ||
                    it.source == CameraRouteSource.JAVA_DEEP_PROBED
            }
            .toMutableList()
        val advertisedIds = advertisedOpaqueIds(level2Snapshots)
        val allCertified = LinkedHashSet<String>()
        val allRetired = LinkedHashSet<String>()
        var deepPassCount = 0
        var fullCompleted = false
        var finalDecision = initialDecision

        if (initialDecision.state == DeepAuxScanState.HOT_ONLY) {
            deepPassCount += 1
            val hot = runDeepPass(
                state = DeepAuxScanState.HOT_ONLY,
                cached = cached,
                signature = signature,
                level2Reliable = level2Reliable,
                advertisedIds = emptyList(),
                existingJavaEvidence = javaEvidence,
                emit = emit,
            )
            allCertified += hot.certifiedIds
            allRetired += hot.retiredIds
            if (hot.credibleCachedIncompatibility) {
                finalDecision = DeepAuxScanDecision(
                    state = DeepAuxScanState.FULL_RECONCILIATION,
                    reason = DeepAuxScanReason.CREDIBLE_DEEP_BECAME_INCOMPATIBLE,
                )
            } else {
                return AuxDiscoveryOrchestrationReport(
                    initialDecision = initialDecision,
                    finalDecision = finalDecision,
                    level2Reliable = level2Reliable,
                    deepPassCount = deepPassCount,
                    certifiedDeepIds = immutableList(allCertified),
                    conclusivelyRetiredDeepIds = immutableList(allRetired),
                    fullReconciliationCompleted = false,
                )
            }
        }

        if (finalDecision.state == DeepAuxScanState.FULL_RECONCILIATION) {
            deepPassCount += 1
            val full = runDeepPass(
                state = DeepAuxScanState.FULL_RECONCILIATION,
                cached = deepKnowledge.current() ?: cached,
                signature = signature,
                level2Reliable = level2Reliable,
                advertisedIds = advertisedIds,
                existingJavaEvidence = javaEvidence,
                emit = emit,
            )
            allCertified += full.certifiedIds
            allRetired += full.retiredIds
            fullCompleted = full.passComplete && level2Reliable
            if (fullCompleted) {
                deepKnowledge.completeReconciliation(
                    environment = environment,
                    advertisedTopologySignature = signature,
                    successfulThisPass = allCertified,
                    conclusivelyRetiredIds = allRetired,
                )
            }
        }

        return AuxDiscoveryOrchestrationReport(
            initialDecision = initialDecision,
            finalDecision = finalDecision,
            level2Reliable = level2Reliable,
            deepPassCount = deepPassCount,
            certifiedDeepIds = immutableList(allCertified),
            conclusivelyRetiredDeepIds = immutableList(allRetired),
            fullReconciliationCompleted = fullCompleted,
        )
    }

    private suspend fun collectJavaLevel2(
        emit: suspend (List<CameraEvidenceSnapshot>) -> Unit,
    ): Level2Result.Java {
        return try {
            val report = javaLevel2.collect { batch ->
                if (batch.snapshots.isNotEmpty()) emit(batch.snapshots)
            }
            Level2Result.Java(report = report, failed = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Level2Result.Java(report = null, failed = true)
        }
    }

    private suspend fun collectNdkLevel2(
        emit: suspend (List<CameraEvidenceSnapshot>) -> Unit,
    ): Level2Result.Ndk {
        return try {
            val report = ndkLevel2.collect()
            emit(listOf(report.snapshot))
            Level2Result.Ndk(report = report, failed = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Level2Result.Ndk(report = null, failed = true)
        }
    }

    private suspend fun runDeepPass(
        state: DeepAuxScanState,
        cached: DeepDiscoveryKnowledge?,
        signature: String,
        level2Reliable: Boolean,
        advertisedIds: Collection<String>,
        existingJavaEvidence: MutableList<CameraMetadataEvidence>,
        emit: suspend (List<CameraEvidenceSnapshot>) -> Unit,
    ): DeepPassResult {
        val hotOnly = state == DeepAuxScanState.HOT_ONLY
        val request = DeepAuxDiscoveryRequest(
            previouslySessionVerifiedDeepIds = cached?.sessionVerifiedDeepIds.orEmpty(),
            previouslySuccessfulDeepIds = cached?.successfulDeepIds.orEmpty(),
            advertisedIds = advertisedIds,
            includeNearbyCandidates = !hotOnly,
            includeLowNamespaceCandidates = !hotOnly,
            limits = DeepAuxDiscoveryLimits(),
        )
        val cachedIds = (cached?.sessionVerifiedDeepIds.orEmpty() + cached?.successfulDeepIds.orEmpty()).toSet()
        val certifiedIds = LinkedHashSet<String>()
        val retiredIds = LinkedHashSet<String>()
        var temporaryInfrastructureFailure = false
        var credibleCachedIncompatibility = false
        val persistenceSignature = if (level2Reliable) signature else cached?.advertisedTopologySignature

        try {
            ndkDeep.collect(request) { ndkReport ->
                if (ndkReport.snapshot.evidence.isNotEmpty()) emit(listOf(ndkReport.snapshot))
                ndkReport.outcomes.forEach { outcome ->
                    when {
                        outcome.outcome.isTemporaryInfrastructureFailure() -> temporaryInfrastructureFailure = true
                        outcome.outcome.isConclusiveCachedIncompatibility() &&
                            outcome.candidate.transportId in cachedIds -> {
                            credibleCachedIncompatibility = true
                            retiredIds += outcome.candidate.transportId
                        }
                    }
                }

                javaDeep.certify(
                    ndkOutcomes = ndkReport.outcomes,
                    existingJavaEvidence = existingJavaEvidence,
                ) { certification ->
                    if (certification.snapshot.evidence.isNotEmpty()) {
                        emit(listOf(certification.snapshot))
                        certification.snapshot.evidence.forEach(existingJavaEvidence::add)
                    }
                    certification.outcomes.forEach { outcome ->
                        when {
                            outcome.kind == JavaDeepCertificationKind.CERTIFIED -> {
                                certifiedIds += outcome.candidate.transportId
                            }
                            outcome.kind.isTemporaryInfrastructureFailure() -> {
                                temporaryInfrastructureFailure = true
                            }
                            outcome.kind.isConclusiveCachedIncompatibility() &&
                                outcome.candidate.transportId in cachedIds -> {
                                credibleCachedIncompatibility = true
                                retiredIds += outcome.candidate.transportId
                            }
                        }
                    }
                    val newlyCertified = certification.outcomes
                        .filter { it.kind == JavaDeepCertificationKind.CERTIFIED }
                        .map { it.candidate.transportId }
                    if (newlyCertified.isNotEmpty() && persistenceSignature != null) {
                        deepKnowledge.recordSuccessful(
                            environment = environment,
                            advertisedTopologySignature = persistenceSignature,
                            ids = newlyCertified,
                            reconciliationComplete = hotOnly && cached?.fullReconciliationComplete == true,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            temporaryInfrastructureFailure = true
        }

        val passComplete = runtimeApiLevel() < CAMERA_NDK_MIN_API_FOR_ORCHESTRATION ||
            !temporaryInfrastructureFailure
        return DeepPassResult(
            certifiedIds = immutableList(certifiedIds),
            retiredIds = immutableList(retiredIds),
            credibleCachedIncompatibility = credibleCachedIncompatibility,
            passComplete = passComplete,
        )
    }

    private fun policyInput(
        cacheRead: CacheRead<DeepDiscoveryKnowledge>,
        cached: DeepDiscoveryKnowledge?,
        signature: String,
        level2Reliable: Boolean,
    ) = DeepAuxScanPolicyInput(
        cacheState = when (cacheRead) {
            is CacheRead.Hit -> DeepAuxCacheState.COMPATIBLE
            CacheRead.Miss -> DeepAuxCacheState.MISSING
            CacheRead.Stale -> DeepAuxCacheState.IO_FAILURE
            is CacheRead.Corrupt -> DeepAuxCacheState.CORRUPT_OR_INCOMPATIBLE
            is CacheRead.IoFailure -> DeepAuxCacheState.IO_FAILURE
        },
        cachedAdvertisedTopologySignature = cached?.advertisedTopologySignature,
        currentAdvertisedTopologySignature = signature,
        advertisedTopologyReliable = level2Reliable,
        cachedSuccessfulDeepIds = cached?.successfulDeepIds.orEmpty(),
        cachedSessionVerifiedDeepIds = cached?.sessionVerifiedDeepIds.orEmpty(),
        previousFullReconciliationComplete = cached?.fullReconciliationComplete ?: false,
        explicitDeepRescan = explicitDeepRescan(),
    )

    private fun isJavaLevel2Reliable(result: Level2Result.Java): Boolean {
        if (result.failed) return false
        val report = result.report ?: return false
        return report.failures.none { it.kind == JavaAdvertisedEvidenceFailureKind.ID_ENUMERATION_UNAVAILABLE }
    }

    private fun isNdkLevel2Reliable(result: Level2Result.Ndk): Boolean {
        if (result.failed) return false
        val report = result.report ?: return false
        if (runtimeApiLevel() < CAMERA_NDK_MIN_API_FOR_ORCHESTRATION) {
            return report.runtimeState == NdkAdvertisedRuntimeState.UNAVAILABLE ||
                report.runtimeState == NdkAdvertisedRuntimeState.NOT_RUN
        }
        return report.runtimeState == NdkAdvertisedRuntimeState.AVAILABLE && report.failures.isEmpty()
    }

    private fun DeepAuxOutcomeKind.isTemporaryInfrastructureFailure(): Boolean = when (this) {
        DeepAuxOutcomeKind.SERVICE_ERROR,
        DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE,
        DeepAuxOutcomeKind.RUNTIME_UNAVAILABLE,
        -> true
        DeepAuxOutcomeKind.VALID_METADATA,
        DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE,
        DeepAuxOutcomeKind.ACCESS_DENIED,
        DeepAuxOutcomeKind.INVALID_OPERATION,
        DeepAuxOutcomeKind.MALFORMED_METADATA,
        DeepAuxOutcomeKind.BOUND_EXCEEDED,
        -> false
    }

    private fun DeepAuxOutcomeKind.isConclusiveCachedIncompatibility(): Boolean = when (this) {
        DeepAuxOutcomeKind.MALFORMED_METADATA,
        DeepAuxOutcomeKind.BOUND_EXCEEDED,
        -> true
        DeepAuxOutcomeKind.VALID_METADATA,
        DeepAuxOutcomeKind.NOT_FOUND_OR_UNAVAILABLE,
        DeepAuxOutcomeKind.ACCESS_DENIED,
        DeepAuxOutcomeKind.SERVICE_ERROR,
        DeepAuxOutcomeKind.TEMPORARILY_UNAVAILABLE,
        DeepAuxOutcomeKind.INVALID_OPERATION,
        DeepAuxOutcomeKind.RUNTIME_UNAVAILABLE,
        -> false
    }

    private fun JavaDeepCertificationKind.isTemporaryInfrastructureFailure(): Boolean = when (this) {
        JavaDeepCertificationKind.JAVA_METADATA_ERROR,
        JavaDeepCertificationKind.CANCELLED,
        -> true
        JavaDeepCertificationKind.CERTIFIED,
        JavaDeepCertificationKind.JAVA_NOT_FOUND,
        JavaDeepCertificationKind.JAVA_ACCESS_DENIED,
        JavaDeepCertificationKind.NO_PRIVATE_PREVIEW,
        JavaDeepCertificationKind.NO_FPS_EVIDENCE,
        JavaDeepCertificationKind.MISSING_ORIENTATION,
        JavaDeepCertificationKind.NON_PHOTOGRAPHIC,
        JavaDeepCertificationKind.BOUND_EXCEEDED,
        JavaDeepCertificationKind.ALREADY_REPRESENTED,
        -> false
    }

    private fun JavaDeepCertificationKind.isConclusiveCachedIncompatibility(): Boolean = when (this) {
        JavaDeepCertificationKind.JAVA_NOT_FOUND,
        JavaDeepCertificationKind.NO_PRIVATE_PREVIEW,
        JavaDeepCertificationKind.NO_FPS_EVIDENCE,
        JavaDeepCertificationKind.MISSING_ORIENTATION,
        JavaDeepCertificationKind.NON_PHOTOGRAPHIC,
        JavaDeepCertificationKind.BOUND_EXCEEDED,
        -> true
        JavaDeepCertificationKind.CERTIFIED,
        JavaDeepCertificationKind.JAVA_ACCESS_DENIED,
        JavaDeepCertificationKind.JAVA_METADATA_ERROR,
        JavaDeepCertificationKind.CANCELLED,
        JavaDeepCertificationKind.ALREADY_REPRESENTED,
        -> false
    }

    private fun advertisedOpaqueIds(snapshots: Collection<CameraEvidenceSnapshot>): List<String> =
        snapshots.asSequence()
            .filter {
                it.source == CameraRouteSource.JAVA_PUBLIC ||
                    it.source == CameraRouteSource.JAVA_PHYSICAL ||
                    it.source == CameraRouteSource.NDK_ADVERTISED
            }
            .flatMap { it.evidence.asSequence() }
            .flatMap { evidence -> sequenceOf(evidence.transportId.value, evidence.physicalId?.value) }
            .filterNotNull()
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()

    private enum class Level2Lane { JAVA, NDK }

    private sealed interface Level2Result {
        data class Java(
            val report: JavaAdvertisedEvidenceReport?,
            val failed: Boolean,
        ) : Level2Result

        data class Ndk(
            val report: NdkAdvertisedEvidenceReport?,
            val failed: Boolean,
        ) : Level2Result
    }

    private data class DeepPassResult(
        val certifiedIds: List<String>,
        val retiredIds: List<String>,
        val credibleCachedIncompatibility: Boolean,
        val passComplete: Boolean,
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
