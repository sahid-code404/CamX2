package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.topology.CanonicalLensOptics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Pure CameX-style rear 1x election. The identity is always a canonical lens fingerprint and never
 * a Camera2 transport/profile identity. A valid preferred reference wins; otherwise a conventional
 * wide-FOV candidate is selected from the currently usable canonical rear lenses.
 */
internal object StableOneXReferenceResolver {
    private const val TARGET_WIDE_DIAGONAL_FOV_DEGREES = 70.0

    fun resolve(
        topology: CameraTopologySnapshot,
        candidates: List<CanonicalLens>,
        preferred: CanonicalLensFingerprint? = null,
        runtimeApiLevel: Int? = null,
    ): CanonicalLensFingerprint? {
        val eligible = candidates.filter { lens ->
            lens.facing == LensFacing.BACK &&
                !CanonicalLensTrustAggregator.aggregate(lens).structurallyUnavailable &&
                isSelectableOnApi(topology, lens, runtimeApiLevel) &&
                opticalScore(topology, lens) != null
        }
        preferred?.let { fingerprint ->
            eligible.firstOrNull { it.fingerprint == fingerprint }?.let { return fingerprint }
        }
        return eligible.mapNotNull { lens ->
            opticalScore(topology, lens)?.let { score -> Candidate(lens.fingerprint, score) }
        }.maxWithOrNull(
            compareBy<Candidate> { it.score.fovSuitability }
                .thenBy { it.score.trust }
                .thenBy { it.score.capabilityConfidence }
                .thenBy { it.score.sensorEvidence }
                .thenBy { it.fingerprint.value },
        )?.fingerprint
    }

    private fun isSelectableOnApi(
        topology: CameraTopologySnapshot,
        lens: CanonicalLens,
        runtimeApiLevel: Int?,
    ): Boolean {
        val api = runtimeApiLevel ?: return true
        return lens.profiles.any { profile ->
            LensProfileEligibilityResolver.resolve(topology, lens, profile, api) is LensProfileEligibility.Eligible
        }
    }

    private data class Candidate(
        val fingerprint: CanonicalLensFingerprint,
        val score: Score,
    )

    private data class Score(
        val fovSuitability: Int,
        val trust: Int,
        val capabilityConfidence: Int,
        val sensorEvidence: Int,
    )

    private fun opticalScore(topology: CameraTopologySnapshot, lens: CanonicalLens): Score? {
        val metadata = CanonicalLensOptics.resolve(topology, lens)
        val focal = metadata.focalLengthMillimetres?.toDouble()?.takeIf(::positiveFinite) ?: return null
        val width = metadata.sensorPhysicalWidthMillimetres?.toDouble()?.takeIf(::positiveFinite) ?: return null
        val height = metadata.sensorPhysicalHeightMillimetres?.toDouble()?.takeIf(::positiveFinite) ?: return null
        val diagonal = hypot(width, height)
        val diagonalFov = 2.0 * atan(diagonal / (2.0 * focal)) * 180.0 / PI
        if (!diagonalFov.isFinite() || diagonalFov <= 0.0 || diagonalFov >= 180.0) return null

        val summary = CanonicalLensTrustAggregator.aggregate(lens)
        val fovSuitability = (100.0 - abs(diagonalFov - TARGET_WIDE_DIAGONAL_FOV_DEGREES) * 2.0)
            .toInt()
            .coerceIn(0, 100)
        val trust = when {
            summary.previewTrust == PreviewTrust.VERIFIED || summary.metadataTrust == CameraTrust.VERIFIED -> 30
            summary.previewTrust == PreviewTrust.ADVERTISED || summary.metadataTrust == CameraTrust.ADVERTISED -> 20
            summary.previewTrust == PreviewTrust.TEMPORARILY_UNAVAILABLE ||
                summary.metadataTrust == CameraTrust.TEMPORARILY_UNAVAILABLE -> 10
            else -> 0
        }
        val capabilityConfidence = lens.profiles.maxOfOrNull { profile ->
            val capabilities = profile.route.capabilities
            (if (capabilities.previewStreams.isNotEmpty()) 20 else 0) +
                (if (capabilities.fpsRanges.isNotEmpty()) 5 else 0) +
                capabilities.previewStreams.size.coerceAtMost(5)
        } ?: 0
        val pixelArea = metadata.activeArray?.area ?: metadata.pixelArray?.area
        val sensorEvidence = pixelArea?.let {
            (ln(it.coerceAtLeast(1L).toDouble()) * 2.0).toInt().coerceAtMost(40)
        } ?: 0
        return Score(fovSuitability, trust, capabilityConfidence, sensorEvidence)
    }

    private fun positiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0
}
