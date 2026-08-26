package com.sahidcode404.camx.core.imaging.reconstruction

import com.sahidcode404.camx.core.imaging.alignment.AlignmentEvidenceSet
import com.sahidcode404.camx.core.imaging.alignment.FrameAlignmentDecision
import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet
import com.sahidcode404.camx.core.imaging.graph.AlgorithmId
import com.sahidcode404.camx.core.imaging.graph.DeterminismClass
import com.sahidcode404.camx.core.imaging.graph.GraphBackend
import com.sahidcode404.camx.core.imaging.graph.GraphSha256
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.max

object M7ReferenceSemantics {
    val ALGORITHM_ID = AlgorithmId("m7.reference.fused-cfa.inverse-variance-v1")
    const val ALGORITHM_VERSION = 1
    const val GRAPH_CANONICAL = "m7|fused-cfa-radiance|reference-grid-1x|same-exposure-iso|m6-inlier-only|inverse-variance|no-demosaic|no-remosaic|no-render|no-ai|v1"
    val GRAPH_SHA256 = GraphSha256(sha256Utf8(GRAPH_CANONICAL))
}

object ReferenceReconstructionEngine {
    fun reconstruct(
        measurements: CalibratedMeasurementFrameSet,
        alignment: AlignmentEvidenceSet,
        request: ReconstructionRequest,
        reservation: ReconstructionReservation,
        provenanceContext: ReconstructionProvenanceContext,
    ): FusedCfaRadiance {
        require(alignment.isBoundTo(measurements)) {
            "M7 alignment evidence is not bound to the supplied M5 measurements"
        }
        require(reservation.request == request && reservation.frameCount == measurements.frames.size) {
            "M7 reservation is not bound to this reconstruction request and FrameSet"
        }
        require(reservation.measurementBindingSha256 == alignment.measurementBindingSha256) {
            "M7 reservation measurement binding diverged from M6 evidence"
        }
        require(reservation.activePixelCount == measurements.profile.activeArea.width.toLong() * measurements.profile.activeArea.height.toLong())

        val frameEvidence = classifyFrames(measurements, alignment, request)
        var included = frameEvidence.filter {
            it.decision == ReconstructionFrameDecision.REFERENCE || it.decision == ReconstructionFrameDecision.INCLUDED
        }.map { it.ordinal }.sorted()
        if (included.size < request.minimumContributingFrames) included = listOf(alignment.referenceOrdinal)
        val fallback = when {
            included.size == measurements.frames.size -> ReconstructionFallbackKind.FULL_ALIGNED_SET
            included.size == 1 -> ReconstructionFallbackKind.REFERENCE_ONLY
            else -> ReconstructionFallbackKind.SMALLER_SUBSET
        }

        val active = measurements.profile.activeArea
        val pixelCount = reservation.activePixelCount.toInt()
        val radiance = FloatArray(pixelCount)
        val variance = FloatArray(pixelCount)
        val effective = FloatArray(pixelCount)
        val contributors = ByteArray(pixelCount)
        val flags = ByteArray(pixelCount)
        val reference = measurements.frames[alignment.referenceOrdinal]

        var sumVariance = 0.0
        var maxVariance = 0.0
        var sumEffective = 0.0
        var referenceOnlyPixels = 0L
        var censoredPixels = 0L
        var rejectedInvisible = 0L
        var rejectedCensored = 0L
        var rejectedOccluded = 0L
        var rejectedResidual = 0L

        var index = 0
        for (sensorY in active.top until active.top + active.height) {
            for (sensorX in active.left until active.left + active.width) {
                val referenceSample = reference.sampleAt(sensorX, sensorY)
                if (referenceSample.lowCensored || referenceSample.highCensored) {
                    radiance[index] = finiteFloat(referenceSample.signalDn, "M7 censored reference signal")
                    variance[index] = finiteFloat(max(referenceSample.varianceDn2, M7ReconstructionLimits.MIN_VARIANCE_DN2), "M7 censored variance")
                    effective[index] = 0f
                    contributors[index] = 0
                    var flag = FusedCfaRadiance.FLAG_REFERENCE_ONLY
                    if (referenceSample.lowCensored) flag = flag or FusedCfaRadiance.FLAG_LOW_CENSORED
                    if (referenceSample.highCensored) flag = flag or FusedCfaRadiance.FLAG_HIGH_CENSORED
                    flags[index] = flag.toByte()
                    censoredPixels++
                    referenceOnlyPixels++
                    sumVariance += variance[index].toDouble()
                    maxVariance = max(maxVariance, variance[index].toDouble())
                    index++
                    continue
                }

                var sumWeight = 0.0
                var sumWeightedSignal = 0.0
                var sumSquaredWeight = 0.0
                var count = 0

                included.forEach { ordinal ->
                    val aligned = alignment.frames[ordinal]
                    val support = alignment.supportAt(ordinal, sensorX, sensorY)
                    if (!support.visible) {
                        rejectedInvisible++
                        return@forEach
                    }
                    if (support.censored) {
                        rejectedCensored++
                        return@forEach
                    }
                    if (!support.inlier || support.occluded) {
                        rejectedOccluded++
                        return@forEach
                    }
                    val residual = support.normalizedResidualSigma
                    if (residual != null && residual > request.maximumPerPixelResidualSigma) {
                        rejectedResidual++
                        return@forEach
                    }
                    val mappedX = sensorX + aligned.translation.dxPixels
                    val mappedY = sensorY + aligned.translation.dyPixels
                    val sample = measurements.frames[ordinal].sampleAt(mappedX, mappedY)
                    val sampleVariance = max(sample.varianceDn2, M7ReconstructionLimits.MIN_VARIANCE_DN2)
                    val weight = 1.0 / sampleVariance
                    sumWeight += weight
                    sumWeightedSignal += weight * sample.signalDn
                    sumSquaredWeight += weight * weight
                    count++
                }

                check(count > 0 && sumWeight > 0.0) { "M7 uncensored reference measurement must remain a valid deterministic fallback" }
                val fused = sumWeightedSignal / sumWeight
                val fusedVariance = 1.0 / sumWeight
                val effectiveSamples = sumWeight * sumWeight / sumSquaredWeight
                radiance[index] = finiteFloat(fused, "M7 fused radiance")
                variance[index] = finiteFloat(fusedVariance, "M7 fused variance")
                effective[index] = finiteFloat(effectiveSamples, "M7 effective sample count")
                contributors[index] = count.toByte()
                var flag = FusedCfaRadiance.FLAG_VALID_MEASUREMENT
                if (count == 1) {
                    flag = flag or FusedCfaRadiance.FLAG_REFERENCE_ONLY
                    referenceOnlyPixels++
                }
                flags[index] = flag.toByte()
                sumVariance += fusedVariance
                maxVariance = max(maxVariance, fusedVariance)
                sumEffective += effectiveSamples
                index++
            }
        }

        val pixelDenominator = pixelCount.toDouble()
        val uncertainty = ReconstructionUncertaintySummary(
            meanRadiometricVarianceDn2 = sumVariance / pixelDenominator,
            maximumRadiometricVarianceDn2 = maxVariance,
            meanEffectiveSampleCount = sumEffective / pixelDenominator,
            referenceOnlyPixelFraction = referenceOnlyPixels.toDouble() / pixelDenominator,
            censoredPixelFraction = censoredPixels.toDouble() / pixelDenominator,
            maximumIncludedAlignmentSigmaPixels = included.maxOf { alignment.frames[it].uncertainty.translationSigmaPixels },
            rejectedInvisibleMeasurements = rejectedInvisible,
            rejectedCensoredMeasurements = rejectedCensored,
            rejectedOccludedMeasurements = rejectedOccluded,
            rejectedResidualMeasurements = rejectedResidual,
        )

        val outputSha = outputSha256(
            active.left,
            active.top,
            active.width,
            active.height,
            measurements.profile.cfaPattern.name,
            radiance,
            variance,
            effective,
            contributors,
            flags,
        )
        val alignmentSha = alignmentEvidenceSha256(alignment)
        val sourceHashes = measurements.frames.map { it.sourceCanonicalSha256 }
        val manifestSha = manifestSha256(
            sourceHashes = sourceHashes,
            includedOrdinals = included,
            calibrationProfileSha256 = measurements.profile.digestSha256(),
            measurementBindingSha256 = alignment.measurementBindingSha256,
            alignmentEvidenceSha256 = alignmentSha,
            buildCommit = provenanceContext.buildCommit,
            outputSha256 = outputSha,
            fallback = fallback,
        )
        val provenance = ReconstructionProvenance(
            sourceCanonicalSha256 = sourceHashes,
            includedOrdinals = included,
            calibrationProfileSha256 = measurements.profile.digestSha256(),
            measurementBindingSha256 = alignment.measurementBindingSha256,
            alignmentEvidenceSha256 = alignmentSha,
            algorithmId = M7ReferenceSemantics.ALGORITHM_ID,
            algorithmVersion = M7ReferenceSemantics.ALGORITHM_VERSION,
            graphSha256 = M7ReferenceSemantics.GRAPH_SHA256,
            backend = GraphBackend.SCALAR_REFERENCE,
            determinismClass = DeterminismClass.BIT_EXACT,
            buildCommit = provenanceContext.buildCommit,
            learnedPriorChangedPixels = false,
            outputSha256 = outputSha,
            manifestSha256 = manifestSha,
        )
        return FusedCfaRadiance(
            activeArea = active,
            cfaPattern = measurements.profile.cfaPattern,
            frameEvidence = Collections.unmodifiableList(ArrayList(frameEvidence)),
            fallbackKind = fallback,
            uncertainty = uncertainty,
            provenance = provenance,
            radianceDn = radiance,
            varianceDn2 = variance,
            effectiveSampleCount = effective,
            contributingFrames = contributors,
            flags = flags,
        )
    }

    private fun classifyFrames(
        measurements: CalibratedMeasurementFrameSet,
        alignment: AlignmentEvidenceSet,
        request: ReconstructionRequest,
    ): List<ReconstructionFrameEvidence> {
        val referenceMetadata = measurements.frames[alignment.referenceOrdinal].metadata
        return measurements.frames.map { frame ->
            val aligned = alignment.frames[frame.ordinal]
            val rolling = aligned.rollingShutter.bandDisagreementPixels
            val decision = when {
                frame.ordinal == alignment.referenceOrdinal -> ReconstructionFrameDecision.REFERENCE
                frame.ordinal !in alignment.reconstructionOrdinals || aligned.decision != FrameAlignmentDecision.ACCEPTED ->
                    ReconstructionFrameDecision.EXCLUDED_BY_ALIGNMENT_SUBSET
                referenceMetadata.exposureTimeNs == null || referenceMetadata.sensitivityIso == null ||
                    frame.metadata.exposureTimeNs != referenceMetadata.exposureTimeNs ||
                    frame.metadata.sensitivityIso != referenceMetadata.sensitivityIso ->
                    ReconstructionFrameDecision.EXCLUDED_EXPOSURE_IDENTITY
                aligned.uncertainty.translationSigmaPixels > request.maximumAlignmentSigmaPixels ->
                    ReconstructionFrameDecision.EXCLUDED_ALIGNMENT_UNCERTAINTY
                rolling != null && rolling > request.maximumRollingShutterDisagreementPixels ->
                    ReconstructionFrameDecision.EXCLUDED_ROLLING_SHUTTER_UNCERTAINTY
                else -> ReconstructionFrameDecision.INCLUDED
            }
            ReconstructionFrameEvidence(
                ordinal = frame.ordinal,
                sourceCanonicalSha256 = frame.sourceCanonicalSha256,
                exposureTimeNs = frame.metadata.exposureTimeNs,
                sensitivityIso = frame.metadata.sensitivityIso,
                alignmentTranslationSigmaPixels = aligned.uncertainty.translationSigmaPixels,
                rollingShutterDisagreementPixels = rolling,
                decision = decision,
            )
        }
    }
}

private fun alignmentEvidenceSha256(alignment: AlignmentEvidenceSet): String {
    val canonical = buildString {
        append(alignment.measurementBindingSha256).append('|')
        append(alignment.referenceOrdinal).append('|')
        append(alignment.fallbackKind.name)
        alignment.reconstructionOrdinals.forEach { append('|').append(it) }
        alignment.frames.forEach { frame ->
            append('|').append(frame.ordinal)
            append('|').append(frame.decision.name)
            append('|').append(frame.translation.dxPixels).append(',').append(frame.translation.dyPixels)
            append('|').append(java.lang.Double.toHexString(frame.translation.meanNormalizedSquaredResidual))
            append('|').append(
                frame.translation.secondBestMeanNormalizedSquaredResidual?.let {
                    java.lang.Double.toHexString(it)
                } ?: "null",
            )
            append('|').append(java.lang.Double.toHexString(frame.uncertainty.translationSigmaPixels))
            append('|').append(java.lang.Double.toHexString(frame.uncertainty.residualSigma))
            append('|').append(java.lang.Double.toHexString(frame.uncertainty.supportLossFraction))
            append('|').append(
                frame.rollingShutter.bandDisagreementPixels?.let {
                    java.lang.Double.toHexString(it)
                } ?: "null",
            )
        }
    }
    return sha256Utf8(canonical)
}

private fun outputSha256(
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    cfaPattern: String,
    radiance: FloatArray,
    variance: FloatArray,
    effective: FloatArray,
    contributors: ByteArray,
    flags: ByteArray,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun updateInt(value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
    updateInt(left); updateInt(top); updateInt(width); updateInt(height)
    digest.update(cfaPattern.toByteArray(Charsets.UTF_8))
    radiance.forEach { updateInt(java.lang.Float.floatToIntBits(it)) }
    variance.forEach { updateInt(java.lang.Float.floatToIntBits(it)) }
    effective.forEach { updateInt(java.lang.Float.floatToIntBits(it)) }
    digest.update(contributors)
    digest.update(flags)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun manifestSha256(
    sourceHashes: List<String>,
    includedOrdinals: List<Int>,
    calibrationProfileSha256: String,
    measurementBindingSha256: String,
    alignmentEvidenceSha256: String,
    buildCommit: String,
    outputSha256: String,
    fallback: ReconstructionFallbackKind,
): String = sha256Utf8(buildString {
    append(M7ReferenceSemantics.ALGORITHM_ID.value).append('|')
    append(M7ReferenceSemantics.ALGORITHM_VERSION).append('|')
    append(M7ReferenceSemantics.GRAPH_SHA256.value).append('|')
    append(GraphBackend.SCALAR_REFERENCE.name).append('|')
    append(DeterminismClass.BIT_EXACT.name).append('|')
    append(calibrationProfileSha256).append('|').append(measurementBindingSha256).append('|')
    append(alignmentEvidenceSha256).append('|').append(buildCommit).append('|').append(outputSha256).append('|')
    append(fallback.name).append("|learned=false")
    sourceHashes.forEach { append('|').append(it) }
    includedOrdinals.forEach { append('|').append(it) }
})

private fun finiteFloat(value: Double, label: String): Float {
    require(value.isFinite() && value >= 0.0) { "$label must be finite and non-negative" }
    val result = value.toFloat()
    require(result.isFinite()) { "$label exceeds deterministic Float storage" }
    return result
}

private fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
