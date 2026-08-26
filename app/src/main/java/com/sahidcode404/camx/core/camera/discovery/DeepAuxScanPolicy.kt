package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import java.security.MessageDigest

enum class DeepAuxScanState {
    SKIP,
    HOT_ONLY,
    FULL_RECONCILIATION,
}

enum class DeepAuxCacheState {
    COMPATIBLE,
    MISSING,
    CORRUPT_OR_INCOMPATIBLE,
    IO_FAILURE,
}

enum class DeepAuxScanReason {
    WARM_STABLE_EMPTY,
    WARM_CREDIBLE_DEEP,
    NO_COMPATIBLE_KNOWLEDGE,
    CACHE_INVALID,
    CACHE_IO_FAILURE,
    INCOMPLETE_RECONCILIATION,
    ADVERTISED_TOPOLOGY_CHANGED,
    CREDIBLE_DEEP_BECAME_INCOMPATIBLE,
    EXPLICIT_RESCAN,
}

data class DeepAuxScanPolicyInput(
    val cacheState: DeepAuxCacheState,
    val cachedAdvertisedTopologySignature: String? = null,
    val currentAdvertisedTopologySignature: String? = null,
    val advertisedTopologyReliable: Boolean = true,
    val cachedSuccessfulDeepIds: Collection<String> = emptyList(),
    val cachedSessionVerifiedDeepIds: Collection<String> = emptyList(),
    val previousFullReconciliationComplete: Boolean = true,
    val credibleDeepBecameIncompatible: Boolean = false,
    val explicitDeepRescan: Boolean = false,
)

data class DeepAuxScanDecision(
    val state: DeepAuxScanState,
    val reason: DeepAuxScanReason,
)

/** One pure decision point for warm HOT probing versus a bounded full reconciliation. */
internal object DeepAuxScanPolicy {
    fun decide(input: DeepAuxScanPolicyInput): DeepAuxScanDecision {
        if (input.explicitDeepRescan) {
            return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.EXPLICIT_RESCAN,
            )
        }
        if (input.credibleDeepBecameIncompatible) {
            return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.CREDIBLE_DEEP_BECAME_INCOMPATIBLE,
            )
        }
        when (input.cacheState) {
            DeepAuxCacheState.MISSING -> return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.NO_COMPATIBLE_KNOWLEDGE,
            )
            DeepAuxCacheState.CORRUPT_OR_INCOMPATIBLE -> return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.CACHE_INVALID,
            )
            DeepAuxCacheState.IO_FAILURE -> return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.CACHE_IO_FAILURE,
            )
            DeepAuxCacheState.COMPATIBLE -> Unit
        }
        if (!input.previousFullReconciliationComplete) {
            return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.INCOMPLETE_RECONCILIATION,
            )
        }
        if (input.advertisedTopologyReliable &&
            input.cachedAdvertisedTopologySignature != null &&
            input.currentAdvertisedTopologySignature != null &&
            input.cachedAdvertisedTopologySignature != input.currentAdvertisedTopologySignature
        ) {
            return DeepAuxScanDecision(
                DeepAuxScanState.FULL_RECONCILIATION,
                DeepAuxScanReason.ADVERTISED_TOPOLOGY_CHANGED,
            )
        }
        val hasCredibleDeep = input.cachedSessionVerifiedDeepIds.any(DeepAuxCandidatePlanner::isSafeExactId) ||
            input.cachedSuccessfulDeepIds.any(DeepAuxCandidatePlanner::isSafeExactId)
        return if (hasCredibleDeep) {
            DeepAuxScanDecision(DeepAuxScanState.HOT_ONLY, DeepAuxScanReason.WARM_CREDIBLE_DEEP)
        } else {
            DeepAuxScanDecision(DeepAuxScanState.SKIP, DeepAuxScanReason.WARM_STABLE_EMPTY)
        }
    }
}

/**
 * Deterministic Level-2 signature. Camera IDs are treated as opaque values and hashed; numeric ID
 * meaning, device identity, manufacturer identity, and photographic role are intentionally absent.
 */
internal object AdvertisedTopologySignature {
    fun compute(snapshots: Collection<CameraEvidenceSnapshot>): String {
        val records = snapshots.asSequence()
            .filter { it.source.isLevel2AdvertisedSource() }
            .flatMap { it.evidence.asSequence() }
            .filter { it.source.isLevel2AdvertisedSource() }
            .map(::recordKey)
            .distinct()
            .sorted()
            .toList()
        return sha256(records.joinToString("\n"))
    }

    private fun CameraRouteSource.isLevel2AdvertisedSource(): Boolean = when (this) {
        CameraRouteSource.JAVA_PUBLIC,
        CameraRouteSource.JAVA_PHYSICAL,
        CameraRouteSource.NDK_ADVERTISED,
        -> true
        CameraRouteSource.JAVA_DEEP_PROBED,
        CameraRouteSource.NDK_DEEP,
        -> false
    }

    private fun recordKey(value: CameraMetadataEvidence): String = buildString {
        append(value.source.name).append('|')
        append(sha256("transport|${value.transportId.value}")).append('|')
        append(value.physicalId?.value?.let { sha256("physical|$it") }.orEmpty()).append('|')
        append(value.logicalParentId?.value?.let { sha256("parent|$it") }.orEmpty()).append('|')
        append(value.facing.name).append('|')
        append(value.focalLengthsMillimetres.sorted().joinToString(",") { floatKey(it) }).append('|')
        append(value.sensorPhysicalWidthMillimetres?.let(::floatKey).orEmpty()).append('|')
        append(value.sensorPhysicalHeightMillimetres?.let(::floatKey).orEmpty()).append('|')
        append(value.activeArray?.let { "${it.width}x${it.height}" }.orEmpty()).append('|')
        append(value.pixelArray?.let { "${it.width}x${it.height}" }.orEmpty()).append('|')
        append(value.sensorOrientationDegrees?.toString().orEmpty()).append('|')
        append(value.capabilities.previewStreams.sortedWith(compareBy(
            { it.type.ordinal },
            { it.size.width },
            { it.size.height },
            { it.minimumFrameDurationNs ?: Long.MAX_VALUE },
        )).joinToString(",") {
            "${it.type.name}:${it.size.width}x${it.size.height}:${it.minimumFrameDurationNs ?: -1L}"
        }).append('|')
        append(value.capabilities.fpsRanges.sortedWith(compareBy({ it.minimum }, { it.maximum }))
            .joinToString(",") { "${it.minimum}-${it.maximum}" })
    }

    private fun floatKey(value: Float): String = value.toRawBits().toUInt().toString(16).padStart(8, '0')

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
