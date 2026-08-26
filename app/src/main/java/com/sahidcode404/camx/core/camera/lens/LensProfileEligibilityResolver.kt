package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust

enum class LensProfileRejectionReason {
    NO_JAVA_CONTROL_AUTHORITY,
    API_TOO_LOW_FOR_PHYSICAL_TARGET,
    MISSING_PRIVATE_PREVIEW,
    MISSING_ORIENTATION,
    CONFLICTING_AUTHORITATIVE_ORIENTATION,
    UNKNOWN_OR_INCOHERENT_FACING,
    PHYSICAL_PARENT_UNUSABLE,
    NDK_ONLY,
    MALFORMED_ROUTE_METADATA,
    NO_COMPATIBLE_PREVIEW_CONFIGURATION,
    STRUCTURALLY_FAILED_PROFILE,
}

internal sealed interface LensProfileEligibility {
    val profileFingerprint: CameraProfileFingerprint

    data class Eligible(
        override val profileFingerprint: CameraProfileFingerprint,
        val target: LensSelectionTarget,
    ) : LensProfileEligibility

    data class Rejected(
        override val profileFingerprint: CameraProfileFingerprint,
        val reason: LensProfileRejectionReason,
    ) : LensProfileEligibility
}

/**
 * Resolves only route-authoritative Java preview metadata. NDK evidence may enrich a coherent route,
 * but it cannot veto Java control authority or manufacture an independently controllable route.
 */
internal object LensProfileEligibilityResolver {
    fun resolve(
        topology: CameraTopologySnapshot,
        lens: CanonicalLens,
        profile: CameraProfile,
        runtimeApiLevel: Int,
    ): LensProfileEligibility {
        val route = profile.route
        if (route.metadataTrust == CameraTrust.STRUCTURALLY_REJECTED ||
            route.previewTrust == PreviewTrust.STRUCTURALLY_REJECTED
        ) {
            return rejected(profile, LensProfileRejectionReason.STRUCTURALLY_FAILED_PROFILE)
        }
        if (route.capabilities.previewStreams.none { it.type == PreviewStreamType.CAMERA2_PRIVATE }) {
            return rejected(profile, LensProfileRejectionReason.MISSING_PRIVATE_PREVIEW)
        }

        val physical = route.physicalCameraId != null
        if (physical && runtimeApiLevel < 28) {
            return rejected(profile, LensProfileRejectionReason.API_TOO_LOW_FOR_PHYSICAL_TARGET)
        }
        if (!hasJavaControlAuthority(route)) {
            val reason = if (route.sources.any { it == CameraRouteSource.NDK_ADVERTISED || it == CameraRouteSource.NDK_DEEP }) {
                LensProfileRejectionReason.NDK_ONLY
            } else {
                LensProfileRejectionReason.NO_JAVA_CONTROL_AUTHORITY
            }
            return rejected(profile, reason)
        }
        if (physical && !physicalParentUsable(topology, route)) {
            return rejected(profile, LensProfileRejectionReason.PHYSICAL_PARENT_UNUSABLE)
        }

        val authoritative = authoritativeEvidence(topology, route)
        if (authoritative.isEmpty()) {
            return rejected(profile, LensProfileRejectionReason.MISSING_ORIENTATION)
        }
        if (physical && authoritative.any { evidence ->
                evidence.logicalParentId != null && evidence.logicalParentId != route.openCameraId
            }
        ) {
            return rejected(profile, LensProfileRejectionReason.MALFORMED_ROUTE_METADATA)
        }

        val orientations = authoritative.mapNotNull { it.sensorOrientationDegrees }.distinct().sorted()
        if (orientations.size > 1) {
            return rejected(profile, LensProfileRejectionReason.CONFLICTING_AUTHORITATIVE_ORIENTATION)
        }
        val orientation = orientations.singleOrNull()
            ?: return rejected(profile, LensProfileRejectionReason.MISSING_ORIENTATION)

        val facings = authoritative.asSequence()
            .map { it.facing }
            .filterNot { it == LensFacing.UNKNOWN }
            .distinct()
            .toList()
        if (facings.size > 1) {
            return rejected(profile, LensProfileRejectionReason.UNKNOWN_OR_INCOHERENT_FACING)
        }
        val facing = when {
            lens.facing != LensFacing.UNKNOWN && facings.isEmpty() -> lens.facing
            lens.facing != LensFacing.UNKNOWN && facings.singleOrNull() == lens.facing -> lens.facing
            lens.facing == LensFacing.UNKNOWN && facings.size == 1 -> facings.single()
            else -> LensFacing.UNKNOWN
        }
        if (facing == LensFacing.UNKNOWN) {
            return rejected(profile, LensProfileRejectionReason.UNKNOWN_OR_INCOHERENT_FACING)
        }

        return LensProfileEligibility.Eligible(
            profileFingerprint = profile.fingerprint,
            target = LensSelectionTarget(
                canonicalFingerprint = lens.fingerprint,
                profileFingerprint = profile.fingerprint,
                routeId = route.id,
                route = route,
                previewMetadata = LensPreviewMetadata(
                    sensorOrientationDegrees = orientation,
                    lensFacing = facing,
                ),
            ),
        )
    }

    fun authoritativeEvidence(
        topology: CameraTopologySnapshot,
        route: CameraRoute,
    ): List<CameraMetadataEvidence> {
        val allowed = if (route.physicalCameraId == null) {
            setOf(CameraRouteSource.JAVA_PUBLIC, CameraRouteSource.JAVA_DEEP_PROBED)
        } else {
            setOf(CameraRouteSource.JAVA_PHYSICAL)
        }
        return topology.evidence.asSequence()
            .filter { it.source in allowed }
            .filter { it.matches(route) }
            .sortedWith(compareBy({ it.source.ordinal }, { it.transportId.value }, { it.physicalId?.value.orEmpty() }))
            .toList()
    }

    fun compatiblePreviewEvidence(
        topology: CameraTopologySnapshot,
        route: CameraRoute,
        metadata: LensPreviewMetadata,
    ): List<CameraMetadataEvidence> {
        val authoritative = authoritativeEvidence(topology, route)
        val enrichment = topology.evidence.asSequence()
            .filter { it.source == CameraRouteSource.NDK_ADVERTISED || it.source == CameraRouteSource.NDK_DEEP }
            .filter { it.matches(route) }
            .filter { it.sensorOrientationDegrees == null || it.sensorOrientationDegrees == metadata.sensorOrientationDegrees }
            .filter { it.facing == LensFacing.UNKNOWN || it.facing == metadata.lensFacing }
            .sortedWith(compareBy({ it.source.ordinal }, { it.transportId.value }, { it.physicalId?.value.orEmpty() }))
            .toList()
        return authoritative + enrichment
    }

    private fun hasJavaControlAuthority(route: CameraRoute): Boolean =
        if (route.physicalCameraId == null) {
            CameraRouteSource.JAVA_PUBLIC in route.sources ||
                CameraRouteSource.JAVA_DEEP_PROBED in route.sources
        } else {
            CameraRouteSource.JAVA_PHYSICAL in route.sources
        }

    private fun physicalParentUsable(topology: CameraTopologySnapshot, route: CameraRoute): Boolean {
        if (route.physicalCameraId == null) return true
        val explicitParent = topology.routes.firstOrNull { candidate ->
            candidate.physicalCameraId == null && candidate.openCameraId == route.openCameraId
        } ?: return true
        return (CameraRouteSource.JAVA_PUBLIC in explicitParent.sources ||
            CameraRouteSource.JAVA_DEEP_PROBED in explicitParent.sources) &&
            explicitParent.metadataTrust != CameraTrust.STRUCTURALLY_REJECTED &&
            explicitParent.previewTrust != PreviewTrust.STRUCTURALLY_REJECTED
    }

    private fun CameraMetadataEvidence.matches(route: CameraRoute): Boolean =
        transportId == route.openCameraId && physicalId == route.physicalCameraId

    private fun rejected(
        profile: CameraProfile,
        reason: LensProfileRejectionReason,
    ) = LensProfileEligibility.Rejected(profile.fingerprint, reason)
}
