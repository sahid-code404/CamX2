package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.topology.CanonicalLensOptics
import java.math.BigDecimal
import java.math.RoundingMode

/** Session-local hardware-test state. It deliberately does not mutate persisted CAMX-107 trust. */
enum class LensTestStatus {
    ADVERTISED,
    AVAILABLE,
    OPENING,
    VERIFIED,
    FAILED,
}

/** Safe immutable presentation model. It contains no raw camera transport identifiers. */
data class CameraLensUiItem(
    val canonicalFingerprint: CanonicalLensFingerprint,
    val facing: LensFacing,
    val primaryLabel: String,
    val secondaryOpticalLabel: String?,
    val enabled: Boolean,
    val selected: Boolean,
    val status: LensTestStatus,
)

internal data class LensPreviewMetadata(
    val sensorOrientationDegrees: Int,
    val lensFacing: LensFacing,
)

/** Internal orchestration target. Feature UI never receives this object. */
internal data class LensSelectionTarget(
    val canonicalFingerprint: CanonicalLensFingerprint,
    val profileFingerprint: CameraProfileFingerprint,
    val routeId: com.sahidcode404.camx.core.camera.model.CameraRouteId,
    val route: com.sahidcode404.camx.core.camera.model.CameraRoute,
    val previewMetadata: LensPreviewMetadata,
)

internal data class CameraLensProjection(
    val items: List<CameraLensUiItem>,
    val targets: Map<CanonicalLensFingerprint, LensSelectionTarget>,
    val eligibilityByProfile: Map<CameraProfileFingerprint, LensProfileEligibility> = emptyMap(),
    val rankedTargetsByLens: Map<CanonicalLensFingerprint, List<LensSelectionTarget>> = emptyMap(),
    val stableOneXReferenceFingerprint: CanonicalLensFingerprint? = null,
)

internal data class CameraLensProjectionInput(
    val topology: CameraTopologySnapshot?,
    val runtimeApiLevel: Int,
    val activeSelection: ActiveCameraSelection?,
    val statusByLens: Map<CanonicalLensFingerprint, LensTestStatus> = emptyMap(),
    val structurallyFailedProfiles: Set<CameraProfileFingerprint> = emptySet(),
    val stableOneXReferenceFingerprint: CanonicalLensFingerprint? = null,
)

/** Pure, deterministic CAMX-107 topology -> one-button-per-canonical-lens projection. */
internal object CameraLensUiProjector {
    fun project(input: CameraLensProjectionInput): CameraLensProjection {
        val topology = input.topology
            ?: return CameraLensProjection(emptyList(), emptyMap(), emptyMap(), emptyMap())
        val eligibilityByProfile = LinkedHashMap<CameraProfileFingerprint, LensProfileEligibility>()
        val rankedTargetsByLens = LinkedHashMap<CanonicalLensFingerprint, List<LensSelectionTarget>>()

        val works = topology.canonicalLenses.mapNotNull { lens ->
            if (CanonicalLensTrustAggregator.aggregate(lens).structurallyUnavailable) return@mapNotNull null
            val candidates = lens.profiles.mapNotNull { profile ->
                val eligibility = if (profile.fingerprint in input.structurallyFailedProfiles) {
                    LensProfileEligibility.Rejected(
                        profileFingerprint = profile.fingerprint,
                        reason = LensProfileRejectionReason.STRUCTURALLY_FAILED_PROFILE,
                    )
                } else {
                    LensProfileEligibilityResolver.resolve(
                        topology = topology,
                        lens = lens,
                        profile = profile,
                        runtimeApiLevel = input.runtimeApiLevel,
                    )
                }
                eligibilityByProfile[profile.fingerprint] = eligibility
                (eligibility as? LensProfileEligibility.Eligible)?.target
            }
            val ranked = LensProfileRanker.rank(
                candidates = candidates,
                activeSelection = input.activeSelection,
                activeFirstFrameVerified = input.statusByLens[lens.fingerprint] == LensTestStatus.VERIFIED,
            )
            rankedTargetsByLens[lens.fingerprint] = ranked
            val target = ranked.firstOrNull() ?: return@mapNotNull null
            LensWork(
                lens = lens,
                target = target,
                optical = opticalEvidence(topology, lens),
                status = presentationStatus(
                    input.statusByLens[lens.fingerprint] ?: LensTestStatus.AVAILABLE,
                ),
            )
        }
        val ordered = works.sortedWith(lensOrder())
        val shouldResolveReference = input.stableOneXReferenceFingerprint != null ||
            ordered.any { work ->
                work.status == LensTestStatus.VERIFIED || work.status == LensTestStatus.OPENING
            }
        val stableReference = if (shouldResolveReference) {
            StableOneXReferenceResolver.resolve(
                topology = topology,
                candidates = ordered.map { it.lens },
                preferred = input.stableOneXReferenceFingerprint,
            )
        } else {
            null
        }
        val referenceMetric = stableReference?.let { reference ->
            ordered.firstOrNull { it.lens.fingerprint == reference }?.optical?.metric
        }

        val targets = LinkedHashMap<CanonicalLensFingerprint, LensSelectionTarget>(ordered.size)
        val items = ordered.map { work ->
            targets[work.lens.fingerprint] = work.target
            val labels = labelsFor(work, referenceMetric)
            CameraLensUiItem(
                canonicalFingerprint = work.lens.fingerprint,
                facing = work.lens.facing,
                primaryLabel = labels.first,
                secondaryOpticalLabel = labels.second,
                enabled = true,
                selected = work.status == LensTestStatus.VERIFIED &&
                    input.activeSelection?.routeId == work.target.routeId,
                status = work.status,
            )
        }
        return CameraLensProjection(
            items = items,
            targets = targets,
            eligibilityByProfile = eligibilityByProfile,
            rankedTargetsByLens = rankedTargetsByLens,
            stableOneXReferenceFingerprint = stableReference,
        )
    }

    private data class OpticalEvidence(
        val focalMillimetres: Float?,
        val metric: Double?,
    )

    private data class LensWork(
        val lens: CanonicalLens,
        val target: LensSelectionTarget,
        val optical: OpticalEvidence,
        val status: LensTestStatus,
    )

    private fun presentationStatus(status: LensTestStatus): LensTestStatus = when (status) {
        LensTestStatus.ADVERTISED -> LensTestStatus.AVAILABLE
        else -> status
    }

    private fun opticalEvidence(topology: CameraTopologySnapshot, lens: CanonicalLens): OpticalEvidence {
        val metadata = CanonicalLensOptics.resolve(topology, lens)
        val focal = metadata.focalLengthMillimetres
        val width = metadata.sensorPhysicalWidthMillimetres
        val metric = if (focal != null && width != null && width > 0f) {
            focal.toDouble() / width.toDouble()
        } else {
            null
        }
        return OpticalEvidence(focal, metric?.takeIf { it.isFinite() && it > 0.0 })
    }

    private fun lensOrder(): Comparator<LensWork> = Comparator { left, right ->
        val facing = facingPriority(left.lens.facing).compareTo(facingPriority(right.lens.facing))
        if (facing != 0) return@Comparator facing
        if (left.lens.facing == LensFacing.BACK) {
            val leftMetric = left.optical.metric
            val rightMetric = right.optical.metric
            when {
                leftMetric != null && rightMetric != null -> {
                    val metric = leftMetric.compareTo(rightMetric)
                    if (metric != 0) return@Comparator metric
                }
                leftMetric != null -> return@Comparator -1
                rightMetric != null -> return@Comparator 1
            }
        }
        left.lens.fingerprint.value.compareTo(right.lens.fingerprint.value)
    }

    private fun facingPriority(facing: LensFacing): Int = when (facing) {
        LensFacing.BACK -> 0
        LensFacing.FRONT -> 1
        LensFacing.EXTERNAL -> 2
        LensFacing.UNKNOWN -> 3
    }

    private fun labelsFor(work: LensWork, referenceMetric: Double?): Pair<String, String?> = when (work.lens.facing) {
        LensFacing.FRONT -> "Front" to work.optical.focalMillimetres?.let(::formatFocal)
        LensFacing.EXTERNAL -> "External" to work.optical.focalMillimetres?.let(::formatFocal)
        LensFacing.BACK -> {
            val focal = work.optical.focalMillimetres?.let(::formatFocal)
            val ratio = if (referenceMetric != null && work.optical.metric != null) {
                work.optical.metric / referenceMetric
            } else {
                null
            }
            if (ratio != null && ratio.isFinite() && ratio > 0.0) {
                formatRatio(ratio) to focal
            } else {
                (focal ?: "Rear") to null
            }
        }
        LensFacing.UNKNOWN -> (work.optical.focalMillimetres?.let(::formatFocal) ?: "Lens") to null
    }

    private fun formatRatio(value: Double): String {
        val decimal = BigDecimal.valueOf(value)
            .setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return "$decimal×"
    }

    private fun formatFocal(value: Float): String {
        val decimal = BigDecimal(value.toString())
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return "$decimal mm"
    }
}
