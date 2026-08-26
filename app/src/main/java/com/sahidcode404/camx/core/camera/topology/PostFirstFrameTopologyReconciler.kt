package com.sahidcode404.camx.core.camera.topology

import android.os.SystemClock
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface AdvertisedTopologyEvidenceProvider {
    /** Emits zero or more bounded current-evidence batches. A provider failure must not cancel peers. */
    suspend fun collect(emit: suspend (List<CameraEvidenceSnapshot>) -> Unit)
}

internal enum class EvidenceMergeResult {
    CHANGED,
    UNCHANGED,
    REJECTED,
}

internal enum class ReconciliationRequestResult {
    STARTED,
    NOT_ARMED,
    ALREADY_RUNNING,
    CLOSED,
}

internal enum class ReconciliationCompletion {
    COMPLETE,
    INCOMPLETE,
    CANCELLED,
}

/** Holds only the current evidence record for each semantic evidence address. */
internal class CurrentTopologyEvidenceAccumulator(
    private val environment: CameraEnvironmentFingerprint,
) {
    private data class EvidenceAddress(
        val source: CameraRouteSource,
        val transportId: String,
        val physicalId: String?,
        val logicalParentId: String?,
    )

    private val current = LinkedHashMap<EvidenceAddress, CameraMetadataEvidence>()
    private val completedAtBySource = LinkedHashMap<CameraRouteSource, Long>()

    val size: Int
        get() = current.size

    fun merge(batch: List<CameraEvidenceSnapshot>): EvidenceMergeResult {
        if (batch.isEmpty()) return EvidenceMergeResult.UNCHANGED
        if (batch.any { it.environment != environment }) return EvidenceMergeResult.REJECTED

        val proposed = LinkedHashMap(current)
        val proposedCompleted = LinkedHashMap(completedAtBySource)
        var changed = false
        for (snapshot in batch) {
            proposedCompleted[snapshot.source] = maxOf(
                proposedCompleted[snapshot.source] ?: 0L,
                snapshot.completedAtElapsedRealtimeNs,
            )
            for (candidate in snapshot.evidence) {
                if (candidate.source != snapshot.source) return EvidenceMergeResult.REJECTED
                val address = candidate.address()
                val existing = proposed[address]
                val selected = if (existing == null) candidate else preferred(existing, candidate)
                if (existing != selected) {
                    proposed[address] = selected
                    changed = true
                }
            }
            if (proposed.size > CameraTopologyResolver.MAX_TOTAL_EVIDENCE) {
                return EvidenceMergeResult.REJECTED
            }
        }

        completedAtBySource.clear()
        completedAtBySource.putAll(proposedCompleted)
        if (!changed) return EvidenceMergeResult.UNCHANGED
        current.clear()
        current.putAll(proposed)
        return EvidenceMergeResult.CHANGED
    }

    fun snapshots(): List<CameraEvidenceSnapshot> = CameraRouteSource.entries.mapNotNull { source ->
        val evidence = current.values.asSequence()
            .filter { it.source == source }
            .sortedBy(::stableContentKey)
            .toList()
        if (evidence.isEmpty()) return@mapNotNull null
        CameraEvidenceSnapshot(
            source = source,
            environment = environment,
            evidence = Collections.unmodifiableList(ArrayList(evidence)),
            completedAtElapsedRealtimeNs = completedAtBySource[source] ?: 0L,
        )
    }

    private fun CameraMetadataEvidence.address() = EvidenceAddress(
        source = source,
        transportId = transportId.value,
        physicalId = physicalId?.value,
        logicalParentId = logicalParentId?.value,
    )

    private fun preferred(existing: CameraMetadataEvidence, candidate: CameraMetadataEvidence): CameraMetadataEvidence {
        val existingRichness = richness(existing)
        val candidateRichness = richness(candidate)
        return when {
            candidateRichness > existingRichness -> candidate
            candidateRichness < existingRichness -> existing
            stableContentKey(candidate) < stableContentKey(existing) -> candidate
            else -> existing
        }
    }

    private fun richness(value: CameraMetadataEvidence): Int {
        var score = 0
        if (value.facing != LensFacing.UNKNOWN) score += 1
        score += value.focalLengthsMillimetres.size * 2
        if (value.sensorPhysicalWidthMillimetres != null) score += 2
        if (value.sensorPhysicalHeightMillimetres != null) score += 2
        if (value.activeArray != null) score += 2
        if (value.pixelArray != null) score += 2
        if (value.sensorOrientationDegrees != null) score += 2
        score += value.apertureValues.size * 2
        if (value.colorFilterArrangement != null) score += 2
        score += value.capabilities.previewStreams.size
        score += value.capabilities.fpsRanges.size * 2
        score += value.capabilities.rawSizes.size * 2
        return score
    }

    private fun stableContentKey(value: CameraMetadataEvidence): String = buildString {
        append(value.source.ordinal).append('|')
        append(value.transportId.value).append('|')
        append(value.physicalId?.value.orEmpty()).append('|')
        append(value.logicalParentId?.value.orEmpty()).append('|')
        append(value.facing.ordinal).append('|')
        append(value.focalLengthsMillimetres.sorted().joinToString(",") { it.toRawBits().toUInt().toString(16) })
        append('|').append(value.sensorPhysicalWidthMillimetres?.toRawBits()?.toUInt()?.toString(16).orEmpty())
        append('|').append(value.sensorPhysicalHeightMillimetres?.toRawBits()?.toUInt()?.toString(16).orEmpty())
        append('|').append(value.activeArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|').append(value.pixelArray?.let { "${it.width}x${it.height}" }.orEmpty())
        append('|').append(value.sensorOrientationDegrees?.toString().orEmpty())
        append('|').append(value.apertureValues.sorted().joinToString(",") { it.toRawBits().toUInt().toString(16) })
        append('|').append(value.colorFilterArrangement?.toString().orEmpty())
        append('|').append(value.capabilities.previewStreams.sortedWith(compareBy(
            { it.type.ordinal },
            { it.size.width },
            { it.size.height },
            { it.minimumFrameDurationNs ?: Long.MAX_VALUE },
        )).joinToString(",") { "${it.type.ordinal}:${it.size.width}x${it.size.height}:${it.minimumFrameDurationNs}" })
        append('|').append(value.capabilities.fpsRanges.sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .joinToString(",") { "${it.minimum}-${it.maximum}" })
        append('|').append(value.capabilities.rawSizes.sortedWith(compareBy({ it.width }, { it.height }))
            .joinToString(",") { "${it.width}x${it.height}" })
    }
}

/**
 * Post-first-frame incremental reconciliation. The first automatic pass is one-shot, while explicit
 * diagnostic reconciliations may be requested later. At most one pass runs at a time; concurrent
 * requests are rejected rather than queued without bound.
 */
internal class PostFirstFrameTopologyReconciler(
    private val environment: CameraEnvironmentFingerprint,
    private val repository: CameraTopologyRepository,
    private val providers: List<AdvertisedTopologyEvidenceProvider>,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val armed = AtomicBoolean(false)
    private val initialRequested = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        require(providers.isNotEmpty()) { "At least one advertised topology provider is required" }
        require(providers.size <= CameraTopologyResolver.MAX_PROVENANCE_SOURCES) {
            "Advertised topology provider count exceeds the provenance bound"
        }
    }

    fun startAfterFirstFrame() {
        if (closed.get()) return
        armed.set(true)
        if (initialRequested.compareAndSet(false, true)) requestReconciliation()
    }

    fun requestReconciliation(
        preserveCurrentTopology: Boolean = false,
        onFinished: () -> Unit = {},
    ): ReconciliationRequestResult = requestReconciliationWithCompletion(
        preserveCurrentTopology = preserveCurrentTopology,
        onFinished = { onFinished() },
    )

    /**
     * Completion-aware form used by explicit Deep Rescan. It does not alter scan semantics; it only
     * reports whether all bounded providers reached their existing coherent completion contract.
     */
    fun requestReconciliationWithCompletion(
        preserveCurrentTopology: Boolean = false,
        onFinished: (ReconciliationCompletion) -> Unit = {},
    ): ReconciliationRequestResult {
        if (closed.get()) return ReconciliationRequestResult.CLOSED
        if (!armed.get()) return ReconciliationRequestResult.NOT_ARMED
        if (!running.compareAndSet(false, true)) return ReconciliationRequestResult.ALREADY_RUNNING
        scope.launch {
            var completion = ReconciliationCompletion.INCOMPLETE
            try {
                completion = reconcileOnce(preserveCurrentTopology)
            } catch (cancelled: CancellationException) {
                completion = ReconciliationCompletion.CANCELLED
                throw cancelled
            } finally {
                running.set(false)
                onFinished(completion)
            }
        }
        return ReconciliationRequestResult.STARTED
    }

    fun isRunning(): Boolean = running.get()

    private suspend fun reconcileOnce(
        preserveCurrentTopology: Boolean,
    ): ReconciliationCompletion {
        val previous = repository.topology.value
        val permit = repository.beginReconciliation(environment)
        val evidence = CurrentTopologyEvidenceAccumulator(environment)
        if (preserveCurrentTopology && previous != null) {
            check(evidence.merge(previousEvidenceSnapshots(previous)) != EvidenceMergeResult.REJECTED) {
                "Current topology evidence exceeds explicit rescan bounds"
            }
        }
        val publicationMutex = Mutex()
        val providerOutcomeMutex = Mutex()
        var publishedAnyBatch = false
        var completedProviders = 0
        var failedProviders = 0

        suspend fun publishBatch(batch: List<CameraEvidenceSnapshot>) {
            if (closed.get()) return
            publicationMutex.withLock {
                if (closed.get()) return@withLock
                when (evidence.merge(batch)) {
                    EvidenceMergeResult.REJECTED -> {
                        throw IllegalArgumentException("Advertised evidence batch exceeds bounds or environment")
                    }
                    EvidenceMergeResult.UNCHANGED -> return@withLock
                    EvidenceMergeResult.CHANGED -> Unit
                }
                val resolved = try {
                    CameraTopologyResolver.resolve(
                        environment = environment,
                        snapshots = evidence.snapshots(),
                        generatedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
                        previousTrustedTopology = previous,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Current advertised evidence cannot be reconciled")
                }
                if (!closed.get() && repository.publish(resolved, permit)) publishedAnyBatch = true
            }
        }

        coroutineScope {
            providers.forEach { provider ->
                launch {
                    try {
                        provider.collect(::publishBatch)
                        providerOutcomeMutex.withLock { completedProviders += 1 }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        providerOutcomeMutex.withLock { failedProviders += 1 }
                    }
                }
            }
        }

        if (closed.get()) return ReconciliationCompletion.CANCELLED
        val allProvidersCompletedSuccessfully = providerOutcomeMutex.withLock {
            completedProviders == providers.size && failedProviders == 0
        }
        if (!allProvidersCompletedSuccessfully) return ReconciliationCompletion.INCOMPLETE

        if (!publishedAnyBatch && !preserveCurrentTopology) {
            val empty = CameraTopologyResolver.resolve(
                environment = environment,
                snapshots = emptyList(),
                generatedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
                previousTrustedTopology = previous,
            )
            if (!closed.get()) repository.publish(empty, permit)
        }
        return if (closed.get()) {
            ReconciliationCompletion.CANCELLED
        } else {
            ReconciliationCompletion.COMPLETE
        }
    }

    private fun previousEvidenceSnapshots(previous: CameraTopologySnapshot): List<CameraEvidenceSnapshot> =
        CameraRouteSource.entries.mapNotNull { source ->
            val values = previous.evidence.filter { it.source == source }
            if (values.isEmpty()) return@mapNotNull null
            CameraEvidenceSnapshot(
                source = source,
                environment = environment,
                evidence = values,
                completedAtElapsedRealtimeNs = previous.generatedAtElapsedRealtimeNs,
            )
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
    }
}
