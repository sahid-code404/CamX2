package com.sahidcode404.camx.core.imaging.alignment

import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrame
import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ReferenceAlignmentEngine {
    fun align(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reservation: AlignmentReservation,
    ): AlignmentEvidenceSet {
        require(reservation.frameCount == measurements.frames.size &&
            reservation.rawWidth == measurements.profile.rawSize.width &&
            reservation.rawHeight == measurements.profile.rawSize.height &&
            reservation.request == request
        ) { "M6 reservation is not bound to this calibrated FrameSet and alignment request" }
        require(request.referenceOrdinal in measurements.frames.indices)

        val reference = measurements.frames[request.referenceOrdinal]
        val candidates = candidateTranslations(request.searchRadiusPixels)
        require(candidates.size == reservation.candidateCount)
        val evidence = ArrayList<AlignedFrameEvidence>(measurements.frames.size)
        measurements.frames.forEach { frame ->
            evidence += if (frame.ordinal == request.referenceOrdinal) {
                referenceEvidence(measurements, request, reference)
            } else {
                alignFrame(measurements, request, reference, frame, candidates)
            }
        }

        val individuallyUsable = evidence
            .filter { it.decision == FrameAlignmentDecision.REFERENCE || it.decision == FrameAlignmentDecision.ACCEPTED }
            .map { it.ordinal }
            .sorted()
        val reconstruction = if (individuallyUsable.size >= request.minimumAcceptedFrames) {
            individuallyUsable
        } else {
            listOf(request.referenceOrdinal)
        }
        val fallback = when {
            reconstruction.size == measurements.frames.size -> AlignmentFallbackKind.FULL_SET
            reconstruction.size == 1 -> AlignmentFallbackKind.REFERENCE_ONLY
            else -> AlignmentFallbackKind.SMALLER_SUBSET
        }
        return AlignmentEvidenceSet(measurements, request, reservation, evidence, reconstruction, fallback)
    }

    private fun referenceEvidence(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reference: CalibratedMeasurementFrame,
    ): AlignedFrameEvidence {
        val support = supportSummary(
            measurements = measurements,
            request = request,
            reference = reference,
            frame = reference,
            dx = 0,
            dy = 0,
        )
        return AlignedFrameEvidence(
            ordinal = reference.ordinal,
            translation = TranslationEstimate(0, 0, 0.0, null, support.usablePairs),
            support = support,
            rollingShutter = RollingShutterMotionEvidence(null, null, null),
            uncertainty = AlignmentUncertainty(
                translationSigmaPixels = 0.0,
                residualSigma = 0.0,
                supportLossFraction = 1.0 - support.usableFraction,
            ),
            decision = FrameAlignmentDecision.REFERENCE,
        )
    }

    private fun alignFrame(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reference: CalibratedMeasurementFrame,
        frame: CalibratedMeasurementFrame,
        candidates: List<Pair<Int, Int>>,
    ): AlignedFrameEvidence {
        val active = measurements.profile.activeArea
        val scored = candidates.map { (dx, dy) ->
            scoreRegion(measurements, request, reference, frame, dx, dy, active.top, active.top + active.height)
        }.sortedWith(scoreComparator)
        val finite = scored.filter { it.meanCost.isFinite() }
        val bestScore = finite.firstOrNull() ?: Score(0, 0, Double.MAX_VALUE, 0, 0, 0)
        val second = finite.drop(1).firstOrNull()
        val translation = TranslationEstimate(
            dxPixels = bestScore.dx,
            dyPixels = bestScore.dy,
            meanNormalizedSquaredResidual = bestScore.meanCost,
            secondBestMeanNormalizedSquaredResidual = second?.meanCost,
            sampledPairs = bestScore.usablePairs,
        )
        val support = supportSummary(
            measurements = measurements,
            request = request,
            reference = reference,
            frame = frame,
            dx = translation.dxPixels,
            dy = translation.dyPixels,
        )
        val separation = translation.costSeparation
        val ambiguous = second != null && checkNotNull(separation) < request.minimumCostSeparation
        val decision = when {
            support.geometricVisibilityFraction < request.minimumVisibilityFraction ->
                FrameAlignmentDecision.EXCLUDED_LOW_VISIBILITY
            support.usableFraction < request.minimumUsableFraction ->
                FrameAlignmentDecision.EXCLUDED_LOW_USABLE_SUPPORT
            support.inlierFractionOfUsable < request.minimumInlierFraction ->
                FrameAlignmentDecision.EXCLUDED_LOW_INLIER_SUPPORT
            translation.meanNormalizedSquaredResidual > request.maximumMeanNormalizedSquaredResidual ->
                FrameAlignmentDecision.EXCLUDED_HIGH_RESIDUAL
            ambiguous -> FrameAlignmentDecision.EXCLUDED_AMBIGUOUS
            else -> FrameAlignmentDecision.ACCEPTED
        }
        val translationSigma = when {
            second == null -> 0.0
            separation == null || separation <= 1e-12 -> M6AlignmentLimits.MAX_UNCERTAINTY_PIXELS
            else -> min(M6AlignmentLimits.MAX_UNCERTAINTY_PIXELS, 2.0 / sqrt(separation))
        }
        val rolling = rollingShutterEvidence(measurements, request, reference, frame, candidates)
        return AlignedFrameEvidence(
            ordinal = frame.ordinal,
            translation = translation,
            support = support,
            rollingShutter = rolling,
            uncertainty = AlignmentUncertainty(
                translationSigmaPixels = translationSigma,
                residualSigma = sqrt(translation.meanNormalizedSquaredResidual),
                supportLossFraction = 1.0 - support.usableFraction,
            ),
            decision = decision,
        )
    }

    private fun rollingShutterEvidence(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reference: CalibratedMeasurementFrame,
        frame: CalibratedMeasurementFrame,
        candidates: List<Pair<Int, Int>>,
    ): RollingShutterMotionEvidence {
        val active = measurements.profile.activeArea
        val middle = active.top + active.height / 2
        if (middle <= active.top || middle >= active.top + active.height) {
            return RollingShutterMotionEvidence(null, null, null)
        }
        val top = bestFiniteScore(measurements, request, reference, frame, candidates, active.top, middle)
        val bottom = bestFiniteScore(
            measurements,
            request,
            reference,
            frame,
            candidates,
            middle,
            active.top + active.height,
        )
        if (top == null || bottom == null) return RollingShutterMotionEvidence(null, null, null)
        val topEstimate = top.toTranslationEstimate()
        val bottomEstimate = bottom.toTranslationEstimate()
        return RollingShutterMotionEvidence(
            topBandTranslation = topEstimate,
            bottomBandTranslation = bottomEstimate,
            bandDisagreementPixels = hypot(
                (top.dx - bottom.dx).toDouble(),
                (top.dy - bottom.dy).toDouble(),
            ),
        )
    }

    private fun bestFiniteScore(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reference: CalibratedMeasurementFrame,
        frame: CalibratedMeasurementFrame,
        candidates: List<Pair<Int, Int>>,
        yStart: Int,
        yEnd: Int,
    ): Score? {
        val finite = candidates.map { (dx, dy) ->
            scoreRegion(measurements, request, reference, frame, dx, dy, yStart, yEnd)
        }.filter { it.meanCost.isFinite() }.sortedWith(scoreComparator)
        val best = finite.firstOrNull() ?: return null
        return best.copy(secondBestCost = finite.drop(1).firstOrNull()?.meanCost)
    }

    private fun supportSummary(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reference: CalibratedMeasurementFrame,
        frame: CalibratedMeasurementFrame,
        dx: Int,
        dy: Int,
    ): FrameSupportSummary {
        val active = measurements.profile.activeArea
        var total = 0
        var visible = 0
        var usable = 0
        var inliers = 0
        forSampleGrid(active, request.sampleStepPixels, active.top, active.top + active.height) { x, y ->
            total++
            val mappedX = x + dx
            val mappedY = y + dy
            if (!inside(active, mappedX, mappedY)) return@forSampleGrid
            visible++
            val refSample = reference.sampleAt(x, y)
            val sourceSample = frame.sampleAt(mappedX, mappedY)
            if (refSample.lowCensored || refSample.highCensored || sourceSample.lowCensored || sourceSample.highCensored) {
                return@forSampleGrid
            }
            usable++
            val variance = max(
                M6AlignmentLimits.MIN_VARIANCE_DN2,
                refSample.varianceDn2 + sourceSample.varianceDn2,
            )
            val residualSigma = abs(refSample.signalDn - sourceSample.signalDn) / sqrt(variance)
            if (residualSigma <= request.inlierSigmaThreshold) inliers++
        }
        val geometric = fraction(visible, total)
        val usableFraction = fraction(usable, total)
        val inlierFraction = if (usable == 0) 0.0 else inliers.toDouble() / usable.toDouble()
        return FrameSupportSummary(
            geometricVisibilityFraction = geometric,
            usableFraction = usableFraction,
            inlierFractionOfUsable = inlierFraction,
            occludedFractionOfUsable = if (usable == 0) 0.0 else (usable - inliers).toDouble() / usable.toDouble(),
            sampledReferencePoints = total,
            visiblePairs = visible,
            usablePairs = usable,
            inlierPairs = inliers,
        )
    }

    private fun scoreRegion(
        measurements: CalibratedMeasurementFrameSet,
        request: AlignmentRequest,
        reference: CalibratedMeasurementFrame,
        frame: CalibratedMeasurementFrame,
        dx: Int,
        dy: Int,
        yStart: Int,
        yEnd: Int,
    ): Score {
        val active = measurements.profile.activeArea
        var total = 0
        var visible = 0
        var usable = 0
        var sum = 0.0
        forSampleGrid(active, request.sampleStepPixels, yStart, yEnd) { x, y ->
            total++
            val mappedX = x + dx
            val mappedY = y + dy
            if (!inside(active, mappedX, mappedY)) return@forSampleGrid
            visible++
            val refSample = reference.sampleAt(x, y)
            val sourceSample = frame.sampleAt(mappedX, mappedY)
            if (refSample.lowCensored || refSample.highCensored || sourceSample.lowCensored || sourceSample.highCensored) {
                return@forSampleGrid
            }
            usable++
            val variance = max(
                M6AlignmentLimits.MIN_VARIANCE_DN2,
                refSample.varianceDn2 + sourceSample.varianceDn2,
            )
            val residual = refSample.signalDn - sourceSample.signalDn
            sum += residual * residual / variance
        }
        val cost = if (usable == 0) Double.POSITIVE_INFINITY else sum / usable.toDouble()
        return Score(dx, dy, cost, total, visible, usable)
    }

    private fun candidateTranslations(radius: Int): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                result += dx to dy
                dx += 2
            }
            dy += 2
        }
        return result
    }

    private fun forSampleGrid(
        active: IntRect,
        step: Int,
        requestedYStart: Int,
        requestedYEnd: Int,
        block: (Int, Int) -> Unit,
    ) {
        val yStart = max(active.top, requestedYStart)
        val yEnd = min(active.top + active.height, requestedYEnd)
        var y = yStart
        while (y < yEnd) {
            var x = active.left
            while (x < active.left + active.width) {
                block(x, y)
                x += step
            }
            y += step
        }
    }

    private fun inside(active: IntRect, x: Int, y: Int): Boolean =
        x >= active.left && y >= active.top &&
            x.toLong() < active.left.toLong() + active.width.toLong() &&
            y.toLong() < active.top.toLong() + active.height.toLong()

    private fun fraction(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

    private data class Score(
        val dx: Int,
        val dy: Int,
        val meanCost: Double,
        val totalSamples: Int,
        val visiblePairs: Int,
        val usablePairs: Int,
        val secondBestCost: Double? = null,
    ) {
        fun toTranslationEstimate() = TranslationEstimate(
            dxPixels = dx,
            dyPixels = dy,
            meanNormalizedSquaredResidual = meanCost,
            secondBestMeanNormalizedSquaredResidual = secondBestCost,
            sampledPairs = usablePairs,
        )
    }

    private val scoreComparator = compareBy<Score>(
        { it.meanCost },
        { abs(it.dx) + abs(it.dy) },
        { it.dy },
        { it.dx },
    )
}
