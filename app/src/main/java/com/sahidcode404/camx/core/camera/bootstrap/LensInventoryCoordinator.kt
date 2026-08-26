package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.lens.StableOneXReferenceResolver
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.StableLensReferenceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LensInventoryReadiness {
    DISCOVERING_INITIAL,
    READY,
    REFRESH_PENDING,
}

enum class LensInventorySource {
    CACHE,
    INITIAL_RECONCILIATION,
    EXPLICIT_RESCAN,
}

enum class LensInventoryRefreshOutcome {
    NO_CHANGE,
    REPLACED,
    FAILED_OR_CANCELLED,
}

data class LensInventoryStatus(
    val readiness: LensInventoryReadiness,
    val source: LensInventorySource?,
    val structuralPublicationCount: Long,
    val inventoryReadyLatencyMs: Long? = null,
    val lastStructuralReplacementLatencyMs: Long? = null,
    val lastRefreshCompletionLatencyMs: Long? = null,
    val lastRefreshOutcome: LensInventoryRefreshOutcome? = null,
)

internal data class LensInventoryCompletion(
    val topologyToPersist: CameraTopologySnapshot?,
    val referenceToPersist: StableLensReferenceSnapshot?,
    val structuralPublished: Boolean,
)

/**
 * Normal-UI inventory gate. Discovery/topology diagnostics may evolve incrementally, while this
 * coordinator publishes only coherent canonical inventory snapshots to the lens strip.
 */
internal class LensInventoryCoordinator(
    private val environment: CameraEnvironmentFingerprint,
    private val runtimeApiLevel: Int,
    private val clockNanos: () -> Long = { System.nanoTime() },
) {
    private data class PendingRescan(
        val generation: Long,
        val startedAtNanos: Long,
    )

    private val createdAtNanos = now()
    private val mutableTopology = MutableStateFlow<CameraTopologySnapshot?>(null)
    private val mutableStableReference = MutableStateFlow<CanonicalLensFingerprint?>(null)
    private val mutableStatus = MutableStateFlow(
        LensInventoryStatus(
            readiness = LensInventoryReadiness.DISCOVERING_INITIAL,
            source = null,
            structuralPublicationCount = 0L,
        ),
    )
    private var latestCandidate: CameraTopologySnapshot? = null
    private var explicitCandidate: CameraTopologySnapshot? = null
    private var pendingRescan: PendingRescan? = null
    private var rescanGeneration = 0L

    val topology: StateFlow<CameraTopologySnapshot?> = mutableTopology.asStateFlow()
    val stableOneXReference: StateFlow<CanonicalLensFingerprint?> = mutableStableReference.asStateFlow()
    val status: StateFlow<LensInventoryStatus> = mutableStatus.asStateFlow()

    @Synchronized
    fun isReadyForExplicitRescan(): Boolean =
        mutableTopology.value != null &&
            mutableStatus.value.readiness == LensInventoryReadiness.READY &&
            pendingRescan == null

    @Synchronized
    fun acceptCompatibleCache(
        snapshot: CameraTopologySnapshot,
        persistedReference: CanonicalLensFingerprint?,
    ): LensInventoryCompletion {
        if (!compatible(snapshot) || mutableTopology.value != null) {
            return noCompletion()
        }
        latestCandidate = snapshot
        val reference = resolveReference(snapshot, persistedReference)
        mutableStableReference.value = reference
        mutableTopology.value = snapshot
        mutableStatus.value = mutableStatus.value.copy(
            readiness = LensInventoryReadiness.READY,
            source = LensInventorySource.CACHE,
            structuralPublicationCount = incrementPublicationCount(),
            inventoryReadyLatencyMs = elapsedMs(createdAtNanos, now()),
        )
        return LensInventoryCompletion(
            topologyToPersist = null,
            referenceToPersist = referenceSnapshot(reference),
            structuralPublished = true,
        )
    }

    /** Receives every internal reconciliation candidate without exposing it to normal UI. */
    @Synchronized
    fun observeCandidate(snapshot: CameraTopologySnapshot?) {
        if (snapshot == null || !compatible(snapshot)) return
        latestCandidate = snapshot
        if (pendingRescan != null) explicitCandidate = snapshot
    }

    /**
     * Completes the one automatic reconciliation. First install publishes exactly once here. A warm
     * cache remains structurally frozen while the newer coherent snapshot is persisted for next launch.
     */
    @Synchronized
    fun completeAutomaticReconciliation(
        finalSnapshot: CameraTopologySnapshot? = latestCandidate,
    ): LensInventoryCompletion {
        if (pendingRescan != null) return noCompletion()
        val candidate = finalSnapshot?.takeIf(::compatible) ?: latestCandidate?.takeIf(::compatible)
            ?: return noCompletion()
        latestCandidate = candidate
        val current = mutableTopology.value
        return if (current == null) {
            val reference = resolveReference(candidate, mutableStableReference.value)
            mutableStableReference.value = reference
            mutableTopology.value = candidate
            mutableStatus.value = mutableStatus.value.copy(
                readiness = LensInventoryReadiness.READY,
                source = LensInventorySource.INITIAL_RECONCILIATION,
                structuralPublicationCount = incrementPublicationCount(),
                inventoryReadyLatencyMs = elapsedMs(createdAtNanos, now()),
            )
            LensInventoryCompletion(
                topologyToPersist = candidate,
                referenceToPersist = referenceSnapshot(reference),
                structuralPublished = true,
            )
        } else {
            val nextLaunchReference = resolveReference(candidate, mutableStableReference.value)
            LensInventoryCompletion(
                topologyToPersist = candidate,
                referenceToPersist = referenceSnapshot(nextLaunchReference),
                structuralPublished = false,
            )
        }
    }

    /**
     * Starts one explicit metadata refresh without changing the published topology/reference. The
     * returned generation must be supplied at completion so stale callbacks cannot replace UI state.
     */
    @Synchronized
    fun beginExplicitRescan(): Long? {
        if (!isReadyForExplicitRescan()) return null
        check(rescanGeneration < Long.MAX_VALUE) { "Lens inventory rescan generation exhausted" }
        rescanGeneration += 1L
        pendingRescan = PendingRescan(
            generation = rescanGeneration,
            startedAtNanos = now(),
        )
        explicitCandidate = null
        mutableStatus.value = mutableStatus.value.copy(
            readiness = LensInventoryReadiness.REFRESH_PENDING,
            lastRefreshCompletionLatencyMs = null,
            lastRefreshOutcome = null,
        )
        return rescanGeneration
    }

    /**
     * Accepts at most one coherent explicit-rescan result. Intermediate candidates never reach
     * normal UI. Equivalent structure only updates persistence; material structure swaps atomically.
     */
    @Synchronized
    fun completeExplicitRescan(
        generation: Long,
        coherent: Boolean,
        finalSnapshot: CameraTopologySnapshot?,
    ): LensInventoryCompletion {
        val pending = pendingRescan
        if (pending == null || pending.generation != generation) return noCompletion()

        val completedAt = now()
        pendingRescan = null
        val refreshLatency = elapsedMs(pending.startedAtNanos, completedAt)

        if (!coherent) {
            explicitCandidate = null
            mutableStatus.value = mutableStatus.value.copy(
                readiness = LensInventoryReadiness.READY,
                lastRefreshCompletionLatencyMs = refreshLatency,
                lastRefreshOutcome = LensInventoryRefreshOutcome.FAILED_OR_CANCELLED,
            )
            return noCompletion()
        }

        val candidate = finalSnapshot?.takeIf(::compatible)
            ?: explicitCandidate?.takeIf(::compatible)
        explicitCandidate = null
        if (candidate == null) {
            mutableStatus.value = mutableStatus.value.copy(
                readiness = LensInventoryReadiness.READY,
                lastRefreshCompletionLatencyMs = refreshLatency,
                lastRefreshOutcome = LensInventoryRefreshOutcome.FAILED_OR_CANCELLED,
            )
            return noCompletion()
        }
        latestCandidate = candidate

        val current = mutableTopology.value
        if (current == null) {
            mutableStatus.value = mutableStatus.value.copy(
                readiness = LensInventoryReadiness.DISCOVERING_INITIAL,
                lastRefreshCompletionLatencyMs = refreshLatency,
                lastRefreshOutcome = LensInventoryRefreshOutcome.FAILED_OR_CANCELLED,
            )
            return noCompletion()
        }

        val currentReference = mutableStableReference.value
        val candidateReference = resolveReferenceForExplicitRescan(candidate, currentReference)
        val currentSignature = structuralSignature(current, currentReference)
        val candidateSignature = structuralSignature(candidate, candidateReference)

        if (candidateSignature == currentSignature) {
            mutableStatus.value = mutableStatus.value.copy(
                readiness = LensInventoryReadiness.READY,
                lastRefreshCompletionLatencyMs = refreshLatency,
                lastRefreshOutcome = LensInventoryRefreshOutcome.NO_CHANGE,
            )
            return LensInventoryCompletion(
                topologyToPersist = candidate,
                referenceToPersist = referenceSnapshot(candidateReference),
                structuralPublished = false,
            )
        }

        mutableStableReference.value = candidateReference
        mutableTopology.value = candidate
        mutableStatus.value = mutableStatus.value.copy(
            readiness = LensInventoryReadiness.READY,
            source = LensInventorySource.EXPLICIT_RESCAN,
            structuralPublicationCount = incrementPublicationCount(),
            lastStructuralReplacementLatencyMs = refreshLatency,
            lastRefreshCompletionLatencyMs = refreshLatency,
            lastRefreshOutcome = LensInventoryRefreshOutcome.REPLACED,
        )
        return LensInventoryCompletion(
            topologyToPersist = candidate,
            referenceToPersist = referenceSnapshot(candidateReference),
            structuralPublished = true,
        )
    }

    @Synchronized
    fun cancelExplicitRescan(generation: Long): LensInventoryCompletion =
        completeExplicitRescan(
            generation = generation,
            coherent = false,
            finalSnapshot = null,
        )

    private fun structuralSignature(
        snapshot: CameraTopologySnapshot,
        reference: CanonicalLensFingerprint?,
    ): LensInventoryStructuralSignature = LensInventoryStructuralSignatureResolver.resolve(
        topology = snapshot,
        runtimeApiLevel = runtimeApiLevel,
        stableOneXReference = reference,
    )

    private fun resolveReferenceForExplicitRescan(
        snapshot: CameraTopologySnapshot,
        preferred: CanonicalLensFingerprint?,
    ): CanonicalLensFingerprint? {
        if (preferred != null && snapshot.canonicalLenses.any { it.fingerprint == preferred }) {
            val neutral = LensInventoryStructuralSignatureResolver.trustNeutralTopology(snapshot)
            val retained = StableOneXReferenceResolver.resolve(
                topology = neutral,
                candidates = neutral.canonicalLenses,
                preferred = preferred,
                runtimeApiLevel = runtimeApiLevel,
            )
            if (retained == preferred) return preferred
        }
        return resolveReference(snapshot, preferred)
    }

    private fun resolveReference(
        snapshot: CameraTopologySnapshot,
        preferred: CanonicalLensFingerprint?,
    ): CanonicalLensFingerprint? = StableOneXReferenceResolver.resolve(
        topology = snapshot,
        candidates = snapshot.canonicalLenses,
        preferred = preferred,
        runtimeApiLevel = runtimeApiLevel,
    )

    private fun referenceSnapshot(reference: CanonicalLensFingerprint?): StableLensReferenceSnapshot? =
        reference?.let {
            StableLensReferenceSnapshot(
                schema = CameraSchemaVersions.LENS_REFERENCE,
                environment = environment,
                canonicalFingerprint = it,
            )
        }

    private fun compatible(snapshot: CameraTopologySnapshot): Boolean =
        snapshot.environment == environment && snapshot.schema == CameraSchemaVersions.TOPOLOGY

    private fun incrementPublicationCount(): Long {
        val current = mutableStatus.value.structuralPublicationCount
        check(current < Long.MAX_VALUE) { "Lens inventory publication count exhausted" }
        return current + 1L
    }

    private fun now(): Long = clockNanos().coerceAtLeast(0L)

    private fun elapsedMs(startNanos: Long, endNanos: Long): Long? {
        if (endNanos < startNanos) return null
        return (endNanos - startNanos) / 1_000_000L
    }

    private fun noCompletion() = LensInventoryCompletion(
        topologyToPersist = null,
        referenceToPersist = null,
        structuralPublished = false,
    )
}
