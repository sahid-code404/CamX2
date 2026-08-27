package com.sahidcode404.camx.core.imaging.reconstruction

import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationBundle
import com.sahidcode404.camx.core.camera.raw.Cp2FrameCalibrationBinding
import com.sahidcode404.camx.core.camera.raw.Cp2NoiseCoefficient
import com.sahidcode404.camx.core.camera.raw.ImmutableRawBurstFrame
import com.sahidcode404.camx.core.camera.raw.ImmutableRawFrameSet
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max

/**
 * CP3 is the first production-connected multi-frame sensor-domain fusion probe. It consumes the exact
 * immutable CP1 RAW16 FrameSet plus CP2 metadata and never invents a fixed-pattern-noise coefficient.
 * Camera2 SENSOR_NOISE_PROFILE is interpreted exactly as specified: N(x)^2 = S*x + O for x normalized
 * to [0,1], then converted back to DN^2 using the per-frame black/white range.
 */
object Cp3ComputationalRawEngine {
    const val ALGORITHM_ID = "cp3.raw16.cfa-known-noise-fusion-v1"
    const val ALGORITHM_VERSION = 1

    private const val SEARCH_RADIUS_PIXELS = 8
    private const val SAMPLE_STEP_PIXELS = 32
    private const val MIN_ALIGNMENT_PAIRS = 64
    private const val MIN_ALIGNMENT_INLIER_FRACTION = 0.35
    private const val MAX_ALIGNMENT_MEAN_NORMALIZED_SQUARED_RESIDUAL = 25.0
    private const val MIN_ALIGNMENT_COST_SEPARATION = 0.02
    private const val PER_PIXEL_RESIDUAL_SIGMA = 6.0
    private const val MIN_VARIANCE_DN2 = 1e-9
    private const val OUTPUT_BYTES_PER_PIXEL = 9L
    private const val SAFETY_MARGIN_BYTES = 1024L * 1024L
    private const val MAX_RESIDENT_BYTES = 1024L * 1024L * 1024L

    fun fuse(
        frameSet: ImmutableRawFrameSet,
        calibration: Cp2CalibrationBundle,
        maxResidentBytes: Long = MAX_RESIDENT_BYTES,
    ): Cp3FusionOutcome {
        val sourceHashes = frameSet.frames.map { it.canonicalSha256 }
        fun failed(detail: String, evidence: List<Cp3FrameEvidence> = emptyList()): Cp3FusionOutcome.Failed =
            Cp3FusionOutcome.Failed(
                Cp3FusionReport(
                    success = false,
                    algorithmId = ALGORITHM_ID,
                    algorithmVersion = ALGORITHM_VERSION,
                    requestedFrames = frameSet.frames.size,
                    referenceOrdinal = null,
                    exposureIdentityFrames = 0,
                    alignedFrames = 0,
                    contributingFrames = 0,
                    activePixelCount = 0L,
                    multiFramePixelCount = 0L,
                    referenceOnlyPixelCount = 0L,
                    censoredPixelCount = 0L,
                    rejectedPixelMeasurements = 0L,
                    calibrationFingerprintSha256 = calibration.report.calibrationFingerprintSha256,
                    sourceCanonicalSha256 = sourceHashes,
                    includedOrdinals = emptyList(),
                    frameEvidence = evidence,
                    outputSha256 = null,
                    fixedPatternNoiseMode = Cp3FixedPatternNoiseMode.UNAVAILABLE_NOT_INVENTED,
                    evidencePersisted = false,
                    failureDetail = detail,
                ),
            )

        if (!calibration.report.success) {
            return failed("CP3 requires a successful exact CP2 calibration binding")
        }
        if (calibration.bindings.size != frameSet.frames.size) {
            return failed("CP3 CP2 membership diverged from the immutable RAW FrameSet")
        }
        frameSet.frames.forEachIndexed { ordinal, frame ->
            val binding = calibration.bindings[ordinal]
            if (!binding.exactResultBound || binding.ordinal != ordinal ||
                binding.sensorTimestampNs != frame.metadata.sensorTimestampNs ||
                binding.sourceCanonicalSha256 != frame.canonicalSha256
            ) {
                return failed("CP3 rejected a CP2 binding that was not exact for RAW ordinal $ordinal")
            }
        }

        val static = calibration.staticObservation
            ?: return failed("CP3 requires CP2 static sensor calibration")
        val cfa = cfaPattern(static.cfaArrangement)
            ?: return failed("CP3 supports the four public Bayer CFA arrangements only")
        val activeEvidence = static.activeArray
            ?: return failed("CP3 requires the real Camera2 active array")
        val active = IntRect(
            left = activeEvidence.left,
            top = activeEvidence.top,
            width = activeEvidence.width,
            height = activeEvidence.height,
        )
        val rawSize = frameSet.context.rawSize
        if (active.left.toLong() + active.width.toLong() > rawSize.width.toLong() ||
            active.top.toLong() + active.height.toLong() > rawSize.height.toLong()
        ) {
            return failed("CP3 active array lies outside the captured RAW raster")
        }
        val staticBlack = static.blackLevels
            ?.takeIf { it.size == 4 }
            ?.map(Int::toDouble)
            ?: return failed("CP3 requires the real four-site Camera2 black-level pattern")
        val staticWhite = static.whiteLevel
            ?: return failed("CP3 requires the real Camera2 white level")
        if (staticBlack.any { staticWhite.toDouble() <= it }) {
            return failed("CP3 Camera2 white level is not above every black-level site")
        }

        val frameCalibrations = frameSet.frames.mapIndexed { ordinal, frame ->
            frameCalibration(frame, calibration.bindings[ordinal], staticBlack, staticWhite)
        }
        val exposureGroups = frameCalibrations
            .filter { it.exposureKey != null && it.noiseProfile != null }
            .groupBy { checkNotNull(it.exposureKey) }
        val selectedGroup = exposureGroups.values
            .sortedWith(
                compareByDescending<List<FrameCalibration>> { it.size }
                    .thenBy { group -> group.minOf { it.frame.ordinal } },
            )
            .firstOrNull()
            .orEmpty()
        if (selectedGroup.size < 2) {
            val evidence = initialEvidence(frameCalibrations, emptySet(), null)
            return failed(
                "CP3 needs at least two exact-exposure RAW frames with Camera2 noise profiles",
                evidence,
            )
        }

        val selectedOrdinals = selectedGroup.map { it.frame.ordinal }.toSet()
        val reference = selectedGroup.minBy { it.frame.ordinal }
        val referenceOrdinal = reference.frame.ordinal
        val evidence = ArrayList<Cp3FrameEvidence>(frameSet.frames.size)
        val accepted = ArrayList<FrameCalibration>()
        accepted += reference

        frameCalibrations.forEach { candidate ->
            when {
                candidate.frame.ordinal == referenceOrdinal -> evidence += Cp3FrameEvidence.reference(referenceOrdinal)
                candidate.exposureKey == null -> evidence += Cp3FrameEvidence.excluded(
                    candidate.frame.ordinal,
                    Cp3FrameDecision.EXCLUDED_MISSING_EXPOSURE_IDENTITY,
                )
                candidate.noiseProfile == null -> evidence += Cp3FrameEvidence.excluded(
                    candidate.frame.ordinal,
                    Cp3FrameDecision.EXCLUDED_MISSING_NOISE_PROFILE,
                )
                candidate.frame.ordinal !in selectedOrdinals -> evidence += Cp3FrameEvidence.excluded(
                    candidate.frame.ordinal,
                    Cp3FrameDecision.EXCLUDED_EXPOSURE_IDENTITY,
                )
                else -> {
                    val estimate = align(reference, candidate, active)
                    val decision = when {
                        estimate.sampledPairs < MIN_ALIGNMENT_PAIRS -> Cp3FrameDecision.EXCLUDED_LOW_ALIGNMENT_SUPPORT
                        estimate.inlierFraction < MIN_ALIGNMENT_INLIER_FRACTION -> Cp3FrameDecision.EXCLUDED_LOW_INLIER_SUPPORT
                        estimate.meanNormalizedSquaredResidual > MAX_ALIGNMENT_MEAN_NORMALIZED_SQUARED_RESIDUAL ->
                            Cp3FrameDecision.EXCLUDED_HIGH_ALIGNMENT_RESIDUAL
                        estimate.secondBestMeanNormalizedSquaredResidual != null &&
                            estimate.secondBestMeanNormalizedSquaredResidual - estimate.meanNormalizedSquaredResidual <
                            MIN_ALIGNMENT_COST_SEPARATION -> Cp3FrameDecision.EXCLUDED_AMBIGUOUS_ALIGNMENT
                        else -> Cp3FrameDecision.INCLUDED
                    }
                    evidence += Cp3FrameEvidence(
                        ordinal = candidate.frame.ordinal,
                        decision = decision,
                        dxPixels = estimate.dxPixels,
                        dyPixels = estimate.dyPixels,
                        meanNormalizedSquaredResidual = estimate.meanNormalizedSquaredResidual,
                        secondBestMeanNormalizedSquaredResidual = estimate.secondBestMeanNormalizedSquaredResidual,
                        sampledPairs = estimate.sampledPairs,
                        inlierFraction = estimate.inlierFraction,
                    )
                    if (decision == Cp3FrameDecision.INCLUDED) accepted += candidate
                }
            }
        }
        evidence.sortBy { it.ordinal }
        if (accepted.size < 2) {
            return failed(
                "CP3 alignment rejected every non-reference frame; no multi-frame fusion was claimed",
                evidence,
            )
        }

        val activePixels = checkedMultiply(active.width.toLong(), active.height.toLong(), "CP3 active pixel proof overflow")
        if (activePixels > Int.MAX_VALUE.toLong()) {
            return failed("CP3 active output cannot be addressed by JVM primitive arrays", evidence)
        }
        val outputBytes = checkedMultiply(activePixels, OUTPUT_BYTES_PER_PIXEL, "CP3 output byte proof overflow")
        val requiredResident = checkedAdd(
            checkedAdd(frameSet.totalCanonicalBytes, outputBytes, "CP3 resident byte proof overflow"),
            SAFETY_MARGIN_BYTES,
            "CP3 resident byte proof overflow",
        )
        if (maxResidentBytes !in requiredResident..MAX_RESIDENT_BYTES) {
            return failed("CP3 resident-memory admission failed", evidence)
        }

        val acceptedByOrdinal = accepted.associateBy { it.frame.ordinal }
        val alignmentByOrdinal = evidence.associateBy { it.ordinal }
        val includedOrdinals = accepted.map { it.frame.ordinal }.sorted()
        val signalDn = FloatArray(activePixels.toInt())
        val knownVarianceDn2 = FloatArray(activePixels.toInt())
        val contributors = ByteArray(activePixels.toInt())
        var multiFramePixels = 0L
        var referenceOnlyPixels = 0L
        var censoredPixels = 0L
        var rejectedMeasurements = 0L
        var outputIndex = 0

        for (y in active.top until active.top + active.height) {
            for (x in active.left until active.left + active.width) {
                val referenceSample = sample(reference, x, y)
                if (referenceSample.censored) {
                    signalDn[outputIndex] = finiteFloat(referenceSample.signalDn, "CP3 reference signal")
                    knownVarianceDn2[outputIndex] = finiteFloat(
                        max(referenceSample.knownVarianceDn2, MIN_VARIANCE_DN2),
                        "CP3 reference known variance",
                    )
                    contributors[outputIndex] = 1
                    referenceOnlyPixels++
                    censoredPixels++
                    outputIndex++
                    continue
                }

                var sumWeight = 0.0
                var sumWeightedSignal = 0.0
                var contributorCount = 0
                includedOrdinals.forEach { ordinal ->
                    val candidate = checkNotNull(acceptedByOrdinal[ordinal])
                    val alignment = checkNotNull(alignmentByOrdinal[ordinal])
                    val mappedX = x + alignment.dxPixels
                    val mappedY = y + alignment.dyPixels
                    if (!inside(active, mappedX, mappedY)) {
                        rejectedMeasurements++
                        return@forEach
                    }
                    val candidateSample = sample(candidate, mappedX, mappedY)
                    if (candidateSample.censored) {
                        rejectedMeasurements++
                        return@forEach
                    }
                    if (ordinal != referenceOrdinal) {
                        val residualVariance = max(
                            MIN_VARIANCE_DN2,
                            referenceSample.knownVarianceDn2 + candidateSample.knownVarianceDn2,
                        )
                        val residualSigma = abs(referenceSample.signalDn - candidateSample.signalDn) /
                            kotlin.math.sqrt(residualVariance)
                        if (!residualSigma.isFinite() || residualSigma > PER_PIXEL_RESIDUAL_SIGMA) {
                            rejectedMeasurements++
                            return@forEach
                        }
                    }
                    val variance = max(candidateSample.knownVarianceDn2, MIN_VARIANCE_DN2)
                    val weight = 1.0 / variance
                    sumWeight += weight
                    sumWeightedSignal += weight * candidateSample.signalDn
                    contributorCount++
                }
                check(contributorCount > 0 && sumWeight > 0.0) {
                    "CP3 uncensored reference pixel must remain a deterministic fallback"
                }
                val fused = sumWeightedSignal / sumWeight
                val variance = 1.0 / sumWeight
                signalDn[outputIndex] = finiteFloat(fused, "CP3 fused signal")
                knownVarianceDn2[outputIndex] = finiteFloat(variance, "CP3 fused known variance")
                contributors[outputIndex] = contributorCount.toByte()
                if (contributorCount >= 2) multiFramePixels++ else referenceOnlyPixels++
                outputIndex++
            }
        }

        if (multiFramePixels == 0L) {
            return failed(
                "CP3 produced no pixel with two or more accepted RAW measurements",
                evidence,
            )
        }

        val outputSha = outputSha256(
            frameSet = frameSet,
            calibrationFingerprint = calibration.report.calibrationFingerprintSha256,
            cfaPattern = cfa,
            active = active,
            includedOrdinals = includedOrdinals,
            evidence = evidence,
            signalDn = signalDn,
            knownVarianceDn2 = knownVarianceDn2,
            contributors = contributors,
        )
        val report = Cp3FusionReport(
            success = true,
            algorithmId = ALGORITHM_ID,
            algorithmVersion = ALGORITHM_VERSION,
            requestedFrames = frameSet.frames.size,
            referenceOrdinal = referenceOrdinal,
            exposureIdentityFrames = selectedGroup.size,
            alignedFrames = evidence.count {
                it.decision == Cp3FrameDecision.REFERENCE || it.decision == Cp3FrameDecision.INCLUDED
            },
            contributingFrames = includedOrdinals.size,
            activePixelCount = activePixels,
            multiFramePixelCount = multiFramePixels,
            referenceOnlyPixelCount = referenceOnlyPixels,
            censoredPixelCount = censoredPixels,
            rejectedPixelMeasurements = rejectedMeasurements,
            calibrationFingerprintSha256 = calibration.report.calibrationFingerprintSha256,
            sourceCanonicalSha256 = sourceHashes,
            includedOrdinals = includedOrdinals,
            frameEvidence = evidence,
            outputSha256 = outputSha,
            fixedPatternNoiseMode = Cp3FixedPatternNoiseMode.UNAVAILABLE_NOT_INVENTED,
            evidencePersisted = false,
            failureDetail = null,
        )
        return Cp3FusionOutcome.Fused(
            fused = Cp3FusedCfa(
                activeArea = active,
                cfaPattern = cfa,
                signalDn = signalDn,
                knownVarianceDn2 = knownVarianceDn2,
                contributors = contributors,
                outputSha256 = outputSha,
            ),
            report = report,
        )
    }

    private fun initialEvidence(
        frames: List<FrameCalibration>,
        selectedOrdinals: Set<Int>,
        referenceOrdinal: Int?,
    ): List<Cp3FrameEvidence> = frames.map { frame ->
        when {
            frame.frame.ordinal == referenceOrdinal -> Cp3FrameEvidence.reference(frame.frame.ordinal)
            frame.exposureKey == null -> Cp3FrameEvidence.excluded(
                frame.frame.ordinal,
                Cp3FrameDecision.EXCLUDED_MISSING_EXPOSURE_IDENTITY,
            )
            frame.noiseProfile == null -> Cp3FrameEvidence.excluded(
                frame.frame.ordinal,
                Cp3FrameDecision.EXCLUDED_MISSING_NOISE_PROFILE,
            )
            frame.frame.ordinal !in selectedOrdinals -> Cp3FrameEvidence.excluded(
                frame.frame.ordinal,
                Cp3FrameDecision.EXCLUDED_EXPOSURE_IDENTITY,
            )
            else -> Cp3FrameEvidence.excluded(frame.frame.ordinal, Cp3FrameDecision.EXCLUDED_LOW_ALIGNMENT_SUPPORT)
        }
    }

    private fun frameCalibration(
        frame: ImmutableRawBurstFrame,
        binding: Cp2FrameCalibrationBinding,
        staticBlack: List<Double>,
        staticWhite: Int,
    ): FrameCalibration {
        val observation = binding.observation
        val black = observation?.dynamicBlackLevels?.takeIf { it.size == 4 } ?: staticBlack
        val white = observation?.dynamicWhiteLevel ?: staticWhite
        val validRange = black.size == 4 && black.all { it.isFinite() && it >= 0.0 && white.toDouble() > it }
        val noise = observation?.noiseProfile?.takeIf { profile -> profile.size == 4 && validRange }
        val exposure = frame.metadata.exposureTimeNs
        val iso = frame.metadata.sensitivityIso
        return FrameCalibration(
            frame = frame,
            blackLevels = black,
            whiteLevel = white.toDouble(),
            noiseProfile = noise,
            exposureKey = if (exposure != null && iso != null) ExposureKey(exposure, iso) else null,
        )
    }

    private fun align(
        reference: FrameCalibration,
        candidate: FrameCalibration,
        active: IntRect,
    ): AlignmentEstimate {
        val scores = ArrayList<AlignmentScore>()
        var dy = -SEARCH_RADIUS_PIXELS
        while (dy <= SEARCH_RADIUS_PIXELS) {
            var dx = -SEARCH_RADIUS_PIXELS
            while (dx <= SEARCH_RADIUS_PIXELS) {
                scores += scoreTranslation(reference, candidate, active, dx, dy)
                dx += 2
            }
            dy += 2
        }
        val finite = scores.filter { it.meanNormalizedSquaredResidual.isFinite() }
            .sortedWith(
                compareBy<AlignmentScore>({ it.meanNormalizedSquaredResidual }, { abs(it.dxPixels) + abs(it.dyPixels) },
                    { it.dyPixels }, { it.dxPixels }),
            )
        val best = finite.firstOrNull() ?: return AlignmentEstimate(
            dxPixels = 0,
            dyPixels = 0,
            meanNormalizedSquaredResidual = Double.MAX_VALUE,
            secondBestMeanNormalizedSquaredResidual = null,
            sampledPairs = 0,
            inlierFraction = 0.0,
        )
        val second = finite.drop(1).firstOrNull()
        return AlignmentEstimate(
            dxPixels = best.dxPixels,
            dyPixels = best.dyPixels,
            meanNormalizedSquaredResidual = best.meanNormalizedSquaredResidual,
            secondBestMeanNormalizedSquaredResidual = second?.meanNormalizedSquaredResidual,
            sampledPairs = best.sampledPairs,
            inlierFraction = best.inlierFraction,
        )
    }

    private fun scoreTranslation(
        reference: FrameCalibration,
        candidate: FrameCalibration,
        active: IntRect,
        dx: Int,
        dy: Int,
    ): AlignmentScore {
        check(dx % 2 == 0 && dy % 2 == 0) { "CP3 alignment must preserve 2x2 CFA phase" }
        var pairs = 0
        var inliers = 0
        var sumNormalizedSquaredResidual = 0.0
        var y = active.top
        while (y < active.top + active.height) {
            var x = active.left
            while (x < active.left + active.width) {
                val mappedX = x + dx
                val mappedY = y + dy
                if (inside(active, mappedX, mappedY)) {
                    val referenceSample = sample(reference, x, y)
                    val candidateSample = sample(candidate, mappedX, mappedY)
                    if (!referenceSample.censored && !candidateSample.censored) {
                        val residualVariance = max(
                            MIN_VARIANCE_DN2,
                            referenceSample.knownVarianceDn2 + candidateSample.knownVarianceDn2,
                        )
                        val residual = referenceSample.signalDn - candidateSample.signalDn
                        val normalizedSquared = residual * residual / residualVariance
                        if (normalizedSquared.isFinite()) {
                            pairs++
                            sumNormalizedSquaredResidual += normalizedSquared
                            if (normalizedSquared <= PER_PIXEL_RESIDUAL_SIGMA * PER_PIXEL_RESIDUAL_SIGMA) inliers++
                        }
                    }
                }
                x += SAMPLE_STEP_PIXELS
            }
            y += SAMPLE_STEP_PIXELS
        }
        return AlignmentScore(
            dxPixels = dx,
            dyPixels = dy,
            meanNormalizedSquaredResidual = if (pairs == 0) Double.POSITIVE_INFINITY
            else sumNormalizedSquaredResidual / pairs.toDouble(),
            sampledPairs = pairs,
            inlierFraction = if (pairs == 0) 0.0 else inliers.toDouble() / pairs.toDouble(),
        )
    }

    private fun sample(frame: FrameCalibration, x: Int, y: Int): SensorSample {
        val raw = frame.frame.raw16LittleEndianAt(x, y)
        val site = ((y and 1) shl 1) or (x and 1)
        val black = frame.blackLevels[site]
        val white = frame.whiteLevel
        val range = white - black
        check(range > 0.0 && range.isFinite()) { "CP3 requires a positive real per-site sensor range" }
        val signal = (raw.toDouble() - black).coerceIn(0.0, range)
        val normalized = signal / range
        val noise = checkNotNull(frame.noiseProfile)[site]
        val normalizedVariance = noise.shotSlope * normalized + noise.readVariance
        val varianceDn2 = normalizedVariance * range * range
        return SensorSample(
            signalDn = signal,
            knownVarianceDn2 = max(varianceDn2, 0.0),
            censored = raw.toDouble() <= black || raw.toDouble() >= white,
        )
    }

    private fun outputSha256(
        frameSet: ImmutableRawFrameSet,
        calibrationFingerprint: String,
        cfaPattern: CfaPattern,
        active: IntRect,
        includedOrdinals: List<Int>,
        evidence: List<Cp3FrameEvidence>,
        signalDn: FloatArray,
        knownVarianceDn2: FloatArray,
        contributors: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateString(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        fun updateInt(value: Int) {
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }
        updateString(ALGORITHM_ID)
        updateInt(ALGORITHM_VERSION)
        updateString(calibrationFingerprint)
        updateString(cfaPattern.name)
        updateInt(active.left); updateInt(active.top); updateInt(active.width); updateInt(active.height)
        frameSet.frames.forEach { updateString(it.canonicalSha256) }
        includedOrdinals.forEach(::updateInt)
        evidence.forEach { frame ->
            updateInt(frame.ordinal)
            updateString(frame.decision.name)
            updateInt(frame.dxPixels)
            updateInt(frame.dyPixels)
            updateString(java.lang.Double.toHexString(frame.meanNormalizedSquaredResidual))
            updateString(
                frame.secondBestMeanNormalizedSquaredResidual?.let(java.lang.Double::toHexString) ?: "null",
            )
        }
        signalDn.forEach { updateInt(java.lang.Float.floatToIntBits(it)) }
        knownVarianceDn2.forEach { updateInt(java.lang.Float.floatToIntBits(it)) }
        digest.update(contributors)
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun cfaPattern(arrangement: Int?): CfaPattern? = when (arrangement) {
        0 -> CfaPattern.RGGB
        1 -> CfaPattern.GRBG
        2 -> CfaPattern.GBRG
        3 -> CfaPattern.BGGR
        else -> null
    }

    private fun inside(active: IntRect, x: Int, y: Int): Boolean =
        x >= active.left && y >= active.top &&
            x.toLong() < active.left.toLong() + active.width.toLong() &&
            y.toLong() < active.top.toLong() + active.height.toLong()

    private fun checkedAdd(left: Long, right: Long, message: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException(message, error)
    }

    private fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException(message, error)
    }

    private fun finiteFloat(value: Double, label: String): Float {
        require(value.isFinite() && value >= 0.0) { "$label must be finite and non-negative" }
        val result = value.toFloat()
        require(result.isFinite()) { "$label exceeds deterministic Float storage" }
        return result
    }

    private data class ExposureKey(val exposureTimeNs: Long, val sensitivityIso: Int)

    private data class FrameCalibration(
        val frame: ImmutableRawBurstFrame,
        val blackLevels: List<Double>,
        val whiteLevel: Double,
        val noiseProfile: List<Cp2NoiseCoefficient>?,
        val exposureKey: ExposureKey?,
    )

    private data class SensorSample(
        val signalDn: Double,
        val knownVarianceDn2: Double,
        val censored: Boolean,
    )

    private data class AlignmentScore(
        val dxPixels: Int,
        val dyPixels: Int,
        val meanNormalizedSquaredResidual: Double,
        val sampledPairs: Int,
        val inlierFraction: Double,
    )

    private data class AlignmentEstimate(
        val dxPixels: Int,
        val dyPixels: Int,
        val meanNormalizedSquaredResidual: Double,
        val secondBestMeanNormalizedSquaredResidual: Double?,
        val sampledPairs: Int,
        val inlierFraction: Double,
    )
}

enum class Cp3FixedPatternNoiseMode {
    UNAVAILABLE_NOT_INVENTED,
}

enum class Cp3FrameDecision {
    REFERENCE,
    INCLUDED,
    EXCLUDED_MISSING_EXPOSURE_IDENTITY,
    EXCLUDED_EXPOSURE_IDENTITY,
    EXCLUDED_MISSING_NOISE_PROFILE,
    EXCLUDED_LOW_ALIGNMENT_SUPPORT,
    EXCLUDED_LOW_INLIER_SUPPORT,
    EXCLUDED_HIGH_ALIGNMENT_RESIDUAL,
    EXCLUDED_AMBIGUOUS_ALIGNMENT,
}

data class Cp3FrameEvidence(
    val ordinal: Int,
    val decision: Cp3FrameDecision,
    val dxPixels: Int,
    val dyPixels: Int,
    val meanNormalizedSquaredResidual: Double,
    val secondBestMeanNormalizedSquaredResidual: Double?,
    val sampledPairs: Int,
    val inlierFraction: Double,
) {
    init {
        require(ordinal >= 0)
        require(dxPixels % 2 == 0 && dyPixels % 2 == 0)
        require(meanNormalizedSquaredResidual.isFinite() && meanNormalizedSquaredResidual >= 0.0)
        require(
            secondBestMeanNormalizedSquaredResidual == null ||
                secondBestMeanNormalizedSquaredResidual.isFinite() &&
                secondBestMeanNormalizedSquaredResidual >= meanNormalizedSquaredResidual
        )
        require(sampledPairs >= 0)
        require(inlierFraction.isFinite() && inlierFraction in 0.0..1.0)
    }

    companion object {
        fun reference(ordinal: Int) = Cp3FrameEvidence(
            ordinal = ordinal,
            decision = Cp3FrameDecision.REFERENCE,
            dxPixels = 0,
            dyPixels = 0,
            meanNormalizedSquaredResidual = 0.0,
            secondBestMeanNormalizedSquaredResidual = null,
            sampledPairs = 0,
            inlierFraction = 1.0,
        )

        fun excluded(ordinal: Int, decision: Cp3FrameDecision) = Cp3FrameEvidence(
            ordinal = ordinal,
            decision = decision,
            dxPixels = 0,
            dyPixels = 0,
            meanNormalizedSquaredResidual = 0.0,
            secondBestMeanNormalizedSquaredResidual = null,
            sampledPairs = 0,
            inlierFraction = 0.0,
        )
    }
}

data class Cp3FusionReport(
    val success: Boolean,
    val algorithmId: String,
    val algorithmVersion: Int,
    val requestedFrames: Int,
    val referenceOrdinal: Int?,
    val exposureIdentityFrames: Int,
    val alignedFrames: Int,
    val contributingFrames: Int,
    val activePixelCount: Long,
    val multiFramePixelCount: Long,
    val referenceOnlyPixelCount: Long,
    val censoredPixelCount: Long,
    val rejectedPixelMeasurements: Long,
    val calibrationFingerprintSha256: String,
    sourceCanonicalSha256: List<String>,
    includedOrdinals: List<Int>,
    frameEvidence: List<Cp3FrameEvidence>,
    val outputSha256: String?,
    val fixedPatternNoiseMode: Cp3FixedPatternNoiseMode,
    val evidencePersisted: Boolean,
    val failureDetail: String?,
) {
    val sourceCanonicalSha256: List<String> = Collections.unmodifiableList(ArrayList(sourceCanonicalSha256))
    val includedOrdinals: List<Int> = Collections.unmodifiableList(ArrayList(includedOrdinals.sorted()))
    val frameEvidence: List<Cp3FrameEvidence> = Collections.unmodifiableList(ArrayList(frameEvidence.sortedBy { it.ordinal }))

    init {
        require(algorithmId.isNotBlank() && algorithmVersion > 0)
        require(requestedFrames > 0)
        require(exposureIdentityFrames in 0..requestedFrames)
        require(alignedFrames in 0..requestedFrames)
        require(contributingFrames in 0..requestedFrames)
        require(activePixelCount >= 0L && multiFramePixelCount >= 0L && referenceOnlyPixelCount >= 0L)
        require(censoredPixelCount >= 0L && rejectedPixelMeasurements >= 0L)
        require(calibrationFingerprintSha256.length == 64)
        require(this.sourceCanonicalSha256.size == requestedFrames)
        require(this.sourceCanonicalSha256.all { it.length == 64 })
        require(this.includedOrdinals.distinct().size == this.includedOrdinals.size)
        require(outputSha256 == null || outputSha256.length == 64)
        require(success == (outputSha256 != null && contributingFrames >= 2 && multiFramePixelCount > 0L))
        require((failureDetail == null) == success)
    }

    fun withEvidencePersisted(persisted: Boolean): Cp3FusionReport = copy(evidencePersisted = persisted)
}

class Cp3FusedCfa internal constructor(
    val activeArea: IntRect,
    val cfaPattern: CfaPattern,
    signalDn: FloatArray,
    knownVarianceDn2: FloatArray,
    contributors: ByteArray,
    val outputSha256: String,
) {
    private val signalDn = signalDn.copyOf()
    private val knownVarianceDn2 = knownVarianceDn2.copyOf()
    private val contributors = contributors.copyOf()

    init {
        val expected = activeArea.width.toLong() * activeArea.height.toLong()
        require(expected <= Int.MAX_VALUE.toLong())
        require(this.signalDn.size == expected.toInt())
        require(this.knownVarianceDn2.size == expected.toInt())
        require(this.contributors.size == expected.toInt())
        require(outputSha256.length == 64)
    }

    fun copySignalDn(): FloatArray = signalDn.copyOf()
    fun copyKnownVarianceDn2(): FloatArray = knownVarianceDn2.copyOf()
    fun copyContributorCounts(): ByteArray = contributors.copyOf()
}

sealed interface Cp3FusionOutcome {
    data class Fused(val fused: Cp3FusedCfa, val report: Cp3FusionReport) : Cp3FusionOutcome
    data class Failed(val report: Cp3FusionReport) : Cp3FusionOutcome
}
