package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource

enum class DiscoveryDepth {
    STARTUP_SEED,
    ADVERTISED,
    DEEP,
}

data class CameraEvidenceSnapshot(
    val source: CameraRouteSource,
    val environment: CameraEnvironmentFingerprint,
    val evidence: List<CameraMetadataEvidence>,
    val completedAtElapsedRealtimeNs: Long,
) {
    init {
        require(completedAtElapsedRealtimeNs >= 0L) {
            "Discovery completion timestamp cannot be negative"
        }
        require(evidence.all { it.source == source }) {
            "Every evidence item must match its snapshot source"
        }
    }
}

/** Metadata evidence only. Implementations must never open a camera device. */
fun interface CameraEvidenceBackend {
    suspend fun discover(depth: DiscoveryDepth): CameraEvidenceSnapshot
}
