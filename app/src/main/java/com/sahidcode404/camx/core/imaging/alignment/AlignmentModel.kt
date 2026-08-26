package com.sahidcode404.camx.core.imaging.alignment

import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object M6AlignmentLimits {
    const val MAX_SEARCH_RADIUS_PIXELS = 32
    const val MAX_SAMPLE_STEP_PIXELS = 64
    const val MAX_SCORE_EVALUATIONS = 100_000_000L
    const val MAX_TRUTH_CASES = 100_000
    const val MAX_UNCERTAINTY_PIXELS = 128.0
    const val MIN_VARIANCE_DN2 = 1e-9
}

data class AlignmentRequest(
    val referenceOrdinal: Int,
    val searchRadiusPixels: Int,
    val sampleStepPixels: Int,
    val inlierSigmaThreshold: Double,
    val minimumVisibilityFraction: Double,
    val minimumUsableFraction: Double,
    val minimumInlierFraction: Double,
    val maximumMeanNormalizedSquaredResidual: Double,
    val minimumCostSeparation: Double,
    val minimumAcceptedFrames: Int,
) {
    init {
        require(referenceOrdinal >= 0) { "Reference ordinal cannot be negative" }
        require(searchRadiusPixels in 0..M6AlignmentLimits.MAX_SEARCH_RADIUS_PIXELS && searchRadiusPixels % 2 == 0) {
            "M6 reference search radius must be an even CFA-phase-preserving value"
        }
        require(sampleStepPixels in 2..M6AlignmentLimits.MAX_SAMPLE_STEP_PIXELS && sampleStepPixels % 2 == 0) {
            "M6 reference sample step must be positive, even, and bounded"
        }
        require(inlierSigmaThreshold.isFinite() && inlierSigmaThreshold > 0.0)
        listOf(minimumVisibilityFraction, minimumUsableFraction, minimumInlierFraction).forEach {
            require(it.isFinite() && it in 0.0..1.0) { "Alignment support thresholds must be finite and in [0, 1]" }
        }
        require(maximumMeanNormalizedSquaredResidual.isFinite() && maximumMeanNormalizedSquaredResidual >= 0.0)
        require(minimumCostSeparation.isFinite() && minimumCostSeparation >= 0.0)
        require(minimumAcceptedFrames > 0) { "Minimum accepted frame count must be positive" }
    }
}

class AlignmentReservation private constructor(
    val frameCount: Int,
    val rawWidth: Int,
    val rawHeight: Int,
    val request: AlignmentRequest,
    val candidateCount: Int,
    val sampledReferencePoints: Long,
    val requiredScoreEvaluations: Long,
    val admittedScoreEvaluations: Long,
) {
    companion object {
        fun forMeasurements(
            measurements: CalibratedMeasurementFrameSet,
            request: AlignmentRequest,
            maxScoreEvaluations: Long = M6AlignmentLimits.MAX_SCORE_EVALUATIONS,
        ): AlignmentReservation {
            require(request.referenceOrdinal in measurements.frames.indices) {
                "Alignment reference ordinal must exist in the calibrated FrameSet"
            }
            require(request.minimumAcceptedFrames <= measurements.frames.size) {
                "Minimum accepted frame count cannot exceed FrameSet membership"
            }
            require(maxScoreEvaluations in 1..M6AlignmentLimits.MAX_SCORE_EVALUATIONS) {
                "Alignment evaluation budget must be positive and globally bounded"
            }
            val active = measurements.profile.activeArea
            val sampledColumns = ceilDiv(active.width.toLong(), request.sampleStepPixels.toLong())
            val sampledRows = ceilDiv(active.height.toLong(), request.sampleStepPixels.toLong())
            val sampledPoints = checkedMultiply(sampledColumns, sampledRows, "M6 sample-grid proof overflow")
            val axisCandidates = request.searchRadiusPixels.toLong() + 1L
            val candidates = checkedMultiply(axisCandidates, axisCandidates, "M6 candidate-count proof overflow")
            require(candidates <= Int.MAX_VALUE.toLong()) { "M6 candidate count exceeds JVM indexing" }
            val nonReferenceFrames = (measurements.frames.size - 1).toLong()
            val globalEvaluations = checkedMultiply(
                checkedMultiply(sampledPoints, candidates, "M6 score-evaluation proof overflow"),
                nonReferenceFrames,
                "M6 score-evaluation proof overflow",
            )
            // Global field plus independent top and bottom rolling-shutter evidence searches.
            val required = checkedMultiply(globalEvaluations, 3L, "M6 rolling-shutter score proof overflow")
            require(required <= maxScoreEvaluations) {
                "M6 alignment search exceeds the admitted deterministic score-evaluation budget"
            }
            return AlignmentReservation(
                frameCount = measurements.frames.size,
                rawWidth = measurements.profile.rawSize.width,
                rawHeight = measurements.profile.rawSize.height,
                request = request,
                candidateCount = candidates.toInt(),
                sampledReferencePoints = sampledPoints,
                requiredScoreEvaluations = required,
                admittedScoreEvaluations = maxScoreEvaluations,
            )
        }
    }
}

enum class FrameAlignmentDecision {
    REFERENCE,
    ACCEPTED,
    EXCLUDED_LOW_VISIBILITY,
    EXCLUDED_LOW_USABLE_SUPPORT,
    EXCLUDED_LOW_INLIER_SUPPORT,
    EXCLUDED_HIGH_RESIDUAL,
    EXCLUDED_AMBIGUOUS,
}

enum class AlignmentFallbackKind {
    FULL_SET,
    SMALLER_SUBSET,
    REFERENCE_ONLY,
}

data class TranslationEstimate(
    val dxPixels: Int,
    val dyPixels: Int,
    val meanNormalizedSquaredResidual: Double,
    val secondBestMeanNormalizedSquaredResidual: Double?,
    val sampledPairs: Int,
) {
    init {
        require(dxPixels % 2 == 0 && dyPixels % 2 == 0) {
            "M6 reference translation must preserve the 2x2 CFA phase"
        }
        require(meanNormalizedSquaredResidual.isFinite() && meanNormalizedSquaredResidual >= 0.0)
        require(
            secondBestMeanNormalizedSquaredResidual == null ||
                secondBestMeanNormalizedSquaredResidual.isFinite() &&
                secondBestMeanNormalizedSquaredResidual >= meanNormalizedSquaredResidual
        ) { "Second-best alignment residual must not beat the selected residual" }
        require(sampledPairs >= 0)
    }

    val costSeparation: Double?
        get() = secondBestMeanNormalizedSquaredResidual?.minus(meanNormalizedSquaredResidual)
}

data class AlignmentUncertainty(
    val translationSigmaPixels: Double,
    val residualSigma: Double,
    val supportLossFraction: Double,
) {
    init {
        require(translationSigmaPixels.isFinite() && translationSigmaPixels in 0.0..M6AlignmentLimits.MAX_UNCERTAINTY_PIXELS)
        require(residualSigma.isFinite() && residualSigma >= 0.0)
        require(supportLossFraction.isFinite() && supportLossFraction in 0.0..1.0)
    }
}

data class FrameSupportSummary(
    val geometricVisibilityFraction: Double,
    val usableFraction: Double,
    val inlierFractionOfUsable: Double,
    val occludedFractionOfUsable: Double,
    val sampledReferencePoints: Int,
    val visiblePairs: Int,
    val usablePairs: Int,
    val inlierPairs: Int,
) {
    init {
        listOf(
            geometricVisibilityFraction,
            usableFraction,
            inlierFractionOfUsable,
            occludedFractionOfUsable,
        ).forEach { require(it.isFinite() && it in 0.0..1.0) }
        require(sampledReferencePoints >= 0 && visiblePairs >= 0 && usablePairs >= 0 && inlierPairs >= 0)
        require(visiblePairs <= sampledReferencePoints)
        require(usablePairs <= visiblePairs)
        require(inlierPairs <= usablePairs)
    }
}

data class RollingShutterMotionEvidence(
    val topBandTranslation: TranslationEstimate?,
    val bottomBandTranslation: TranslationEstimate?,
    val bandDisagreementPixels: Double?,
) {
    init {
        require((topBandTranslation == null) == (bottomBandTranslation == null)) {
            "Rolling-shutter band estimates must be jointly present or absent"
        }
        require((bandDisagreementPixels == null) == (topBandTranslation == null)) {
            "Rolling-shutter disagreement must exist exactly when band estimates exist"
        }
        require(bandDisagreementPixels == null || bandDisagreementPixels.isFinite() && bandDisagreementPixels >= 0.0)
    }
}

data class AlignedFrameEvidence(
    val ordinal: Int,
    val translation: TranslationEstimate,
    val support: FrameSupportSummary,
    val rollingShutter: RollingShutterMotionEvidence,
    val uncertainty: AlignmentUncertainty,
    val decision: FrameAlignmentDecision,
) {
    init { require(ordinal >= 0) }
}

data class PixelMeasurementSupport(
    val visible: Boolean,
    val censored: Boolean,
    val inlier: Boolean,
    val occluded: Boolean,
    val normalizedResidualSigma: Double?,
    val propagatedVarianceDn2: Double?,
    val alignmentTranslationSigmaPixels: Double,
) {
    init {
        require(!inlier || visible && !censored)
        require(!occluded || visible && !censored && !inlier)
        require(normalizedResidualSigma == null || normalizedResidualSigma.isFinite() && normalizedResidualSigma >= 0.0)
        require(propagatedVarianceDn2 == null || propagatedVarianceDn2.isFinite() && propagatedVarianceDn2 >= 0.0)
        require(alignmentTranslationSigmaPixels.isFinite() && alignmentTranslationSigmaPixels >= 0.0)
    }
}

class AlignmentEvidenceSet internal constructor(
    private val measurements: CalibratedMeasurementFrameSet,
    val request: AlignmentRequest,
    val reservation: AlignmentReservation,
    frames: List<AlignedFrameEvidence>,
    reconstructionOrdinals: List<Int>,
    val fallbackKind: AlignmentFallbackKind,
) {
    val frames: List<AlignedFrameEvidence> = Collections.unmodifiableList(ArrayList(frames.sortedBy { it.ordinal }))
    val reconstructionOrdinals: List<Int> = Collections.unmodifiableList(ArrayList(reconstructionOrdinals.sorted()))
    val referenceOrdinal: Int = request.referenceOrdinal

    init {
        require(this.frames.size == measurements.frames.size)
        require(this.frames.indices.all { this.frames[it].ordinal == it })
        require(this.frames[referenceOrdinal].decision == FrameAlignmentDecision.REFERENCE)
        require(this.reconstructionOrdinals.isNotEmpty() && referenceOrdinal in this.reconstructionOrdinals)
        require(this.reconstructionOrdinals.distinct().size == this.reconstructionOrdinals.size)
        require(this.reconstructionOrdinals.all { it in this.frames.indices })
        require(reservation.frameCount == measurements.frames.size)
        require(reservation.request == request)
    }

    fun supportAt(frameOrdinal: Int, x: Int, y: Int): PixelMeasurementSupport {
        require(frameOrdinal in frames.indices) { "Alignment support frame ordinal is outside the FrameSet" }
        val profile = measurements.profile
        require(x in 0 until profile.rawSize.width && y in 0 until profile.rawSize.height) {
            "Alignment support coordinate lies outside the RAW raster"
        }
        val evidence = frames[frameOrdinal]
        val mappedX = x + evidence.translation.dxPixels
        val mappedY = y + evidence.translation.dyPixels
        if (!insideActive(profile.activeArea.left, profile.activeArea.top, profile.activeArea.width, profile.activeArea.height, x, y) ||
            mappedX !in 0 until profile.rawSize.width || mappedY !in 0 until profile.rawSize.height ||
            !insideActive(
                profile.activeArea.left,
                profile.activeArea.top,
                profile.activeArea.width,
                profile.activeArea.height,
                mappedX,
                mappedY,
            )
        ) {
            return PixelMeasurementSupport(
                visible = false,
                censored = false,
                inlier = false,
                occluded = false,
                normalizedResidualSigma = null,
                propagatedVarianceDn2 = null,
                alignmentTranslationSigmaPixels = evidence.uncertainty.translationSigmaPixels,
            )
        }
        val reference = measurements.frames[referenceOrdinal].sampleAt(x, y)
        val candidate = measurements.frames[frameOrdinal].sampleAt(mappedX, mappedY)
        val censored = reference.lowCensored || reference.highCensored || candidate.lowCensored || candidate.highCensored
        val variance = max(M6AlignmentLimits.MIN_VARIANCE_DN2, reference.varianceDn2 + candidate.varianceDn2)
        val residualSigma = abs(reference.signalDn - candidate.signalDn) / sqrt(variance)
        val inlier = !censored && residualSigma <= request.inlierSigmaThreshold
        return PixelMeasurementSupport(
            visible = true,
            censored = censored,
            inlier = inlier,
            occluded = !censored && !inlier,
            normalizedResidualSigma = residualSigma,
            propagatedVarianceDn2 = variance,
            alignmentTranslationSigmaPixels = evidence.uncertainty.translationSigmaPixels,
        )
    }
}

private fun insideActive(left: Int, top: Int, width: Int, height: Int, x: Int, y: Int): Boolean =
    x >= left && y >= top &&
        x.toLong() < left.toLong() + width.toLong() &&
        y.toLong() < top.toLong() + height.toLong()

private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1L) / divisor

private fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
    Math.multiplyExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException(message, error)
}
