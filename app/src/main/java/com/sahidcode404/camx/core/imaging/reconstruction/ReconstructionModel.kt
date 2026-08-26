package com.sahidcode404.camx.core.imaging.reconstruction

import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.imaging.alignment.AlignmentEvidenceSet
import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet
import com.sahidcode404.camx.core.imaging.calibration.CfaSiteColor
import com.sahidcode404.camx.core.imaging.graph.AlgorithmId
import com.sahidcode404.camx.core.imaging.graph.DeterminismClass
import com.sahidcode404.camx.core.imaging.graph.GraphBackend
import com.sahidcode404.camx.core.imaging.graph.GraphRepresentation
import com.sahidcode404.camx.core.imaging.graph.GraphSha256
import com.sahidcode404.camx.core.imaging.graph.GraphUncertaintySemantics
import com.sahidcode404.camx.core.imaging.graph.PhotometricDomain
import java.util.Collections

object M7ReconstructionLimits {
    const val MAX_RESIDENT_BYTES = 1024L * 1024L * 1024L
    const val INPUT_BYTES_PER_PIXEL = 2L
    const val OUTPUT_RESERVATION_BYTES_PER_PIXEL = 16L
    const val WORKING_RESERVATION_BYTES_PER_PIXEL = 16L
    const val FIXED_SAFETY_MARGIN_BYTES = 1024L * 1024L
    const val MAX_BUILD_COMMIT_CHARS = 128
    const val MAX_VALIDATION_SAMPLES = 1_000_000
    const val MIN_VARIANCE_DN2 = 1e-9
}

enum class ReconstructionFrameDecision {
    REFERENCE,
    INCLUDED,
    EXCLUDED_BY_ALIGNMENT_SUBSET,
    EXCLUDED_EXPOSURE_IDENTITY,
    EXCLUDED_ALIGNMENT_UNCERTAINTY,
    EXCLUDED_ROLLING_SHUTTER_UNCERTAINTY,
}

enum class ReconstructionFallbackKind {
    FULL_ALIGNED_SET,
    SMALLER_SUBSET,
    REFERENCE_ONLY,
}

data class ReconstructionRequest(
    val minimumContributingFrames: Int,
    val maximumAlignmentSigmaPixels: Double,
    val maximumRollingShutterDisagreementPixels: Double,
    val maximumPerPixelResidualSigma: Double,
) {
    init {
        require(minimumContributingFrames > 0) { "M7 minimum contributing frame count must be positive" }
        require(maximumAlignmentSigmaPixels.isFinite() && maximumAlignmentSigmaPixels >= 0.0)
        require(
            maximumRollingShutterDisagreementPixels.isFinite() &&
                maximumRollingShutterDisagreementPixels >= 0.0
        )
        require(maximumPerPixelResidualSigma.isFinite() && maximumPerPixelResidualSigma > 0.0)
    }
}

data class ReconstructionProvenanceContext(val buildCommit: String) {
    init {
        require(buildCommit.isNotBlank() && buildCommit.length <= M7ReconstructionLimits.MAX_BUILD_COMMIT_CHARS) {
            "M7 build commit must be nonblank and bounded"
        }
        require(buildCommit.all { it.code in 0x21..0x7e }) {
            "M7 build commit must use printable ASCII"
        }
    }
}

class ReconstructionReservation private constructor(
    val frameCount: Int,
    val activePixelCount: Long,
    val inputCanonicalBytes: Long,
    val outputReservationBytes: Long,
    val workingReservationBytes: Long,
    val safetyMarginBytes: Long,
    val requiredResidentBytes: Long,
    val maxResidentBytes: Long,
    val request: ReconstructionRequest,
    val measurementBindingSha256: String,
) {
    companion object {
        fun forInputs(
            measurements: CalibratedMeasurementFrameSet,
            alignment: AlignmentEvidenceSet,
            request: ReconstructionRequest,
            maxResidentBytes: Long,
        ): ReconstructionReservation {
            require(alignment.isBoundTo(measurements)) {
                "M7 alignment evidence is not bound to the supplied calibrated measurements"
            }
            require(request.minimumContributingFrames <= measurements.frames.size) {
                "M7 minimum contributing frame count cannot exceed FrameSet membership"
            }
            val rawPixels = checkedMultiply(
                measurements.profile.rawSize.width.toLong(),
                measurements.profile.rawSize.height.toLong(),
                "M7 RAW pixel proof overflow",
            )
            val inputPerFrame = checkedMultiply(
                rawPixels,
                M7ReconstructionLimits.INPUT_BYTES_PER_PIXEL,
                "M7 input byte proof overflow",
            )
            val inputBytes = checkedMultiply(
                inputPerFrame,
                measurements.frames.size.toLong(),
                "M7 input resident proof overflow",
            )
            val active = measurements.profile.activeArea
            val activePixels = checkedMultiply(active.width.toLong(), active.height.toLong(), "M7 active-area proof overflow")
            require(activePixels in 1..Int.MAX_VALUE.toLong()) {
                "M7 software reference requires an active raster addressable by JVM primitive arrays"
            }
            val outputBytes = checkedMultiply(
                activePixels,
                M7ReconstructionLimits.OUTPUT_RESERVATION_BYTES_PER_PIXEL,
                "M7 output reservation overflow",
            )
            val workingBytes = checkedMultiply(
                active.width.toLong(),
                M7ReconstructionLimits.WORKING_RESERVATION_BYTES_PER_PIXEL,
                "M7 row-working reservation overflow",
            )
            val required = checkedAdd(
                checkedAdd(inputBytes, outputBytes, "M7 resident proof overflow"),
                checkedAdd(
                    workingBytes,
                    M7ReconstructionLimits.FIXED_SAFETY_MARGIN_BYTES,
                    "M7 resident proof overflow",
                ),
                "M7 resident proof overflow",
            )
            require(required <= M7ReconstructionLimits.MAX_RESIDENT_BYTES) {
                "M7 scalar reference exceeds the globally bounded resident-memory implementation"
            }
            require(maxResidentBytes in required..M7ReconstructionLimits.MAX_RESIDENT_BYTES) {
                "M7 resident budget does not cover the proven reference reconstruction extent"
            }
            return ReconstructionReservation(
                frameCount = measurements.frames.size,
                activePixelCount = activePixels,
                inputCanonicalBytes = inputBytes,
                outputReservationBytes = outputBytes,
                workingReservationBytes = workingBytes,
                safetyMarginBytes = M7ReconstructionLimits.FIXED_SAFETY_MARGIN_BYTES,
                requiredResidentBytes = required,
                maxResidentBytes = maxResidentBytes,
                request = request,
                measurementBindingSha256 = alignment.measurementBindingSha256,
            )
        }
    }
}

data class ReconstructionFrameEvidence(
    val ordinal: Int,
    val sourceCanonicalSha256: String,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val alignmentTranslationSigmaPixels: Double,
    val rollingShutterDisagreementPixels: Double?,
    val decision: ReconstructionFrameDecision,
) {
    init {
        require(ordinal >= 0)
        require(sourceCanonicalSha256.length == 64 && sourceCanonicalSha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(alignmentTranslationSigmaPixels.isFinite() && alignmentTranslationSigmaPixels >= 0.0)
        require(
            rollingShutterDisagreementPixels == null ||
                rollingShutterDisagreementPixels.isFinite() && rollingShutterDisagreementPixels >= 0.0
        )
    }
}

data class ReconstructionUncertaintySummary(
    val meanRadiometricVarianceDn2: Double,
    val maximumRadiometricVarianceDn2: Double,
    val meanEffectiveSampleCount: Double,
    val referenceOnlyPixelFraction: Double,
    val censoredPixelFraction: Double,
    val maximumIncludedAlignmentSigmaPixels: Double,
    val rejectedInvisibleMeasurements: Long,
    val rejectedCensoredMeasurements: Long,
    val rejectedOccludedMeasurements: Long,
    val rejectedResidualMeasurements: Long,
) {
    init {
        require(meanRadiometricVarianceDn2.isFinite() && meanRadiometricVarianceDn2 >= 0.0)
        require(maximumRadiometricVarianceDn2.isFinite() && maximumRadiometricVarianceDn2 >= 0.0)
        require(meanEffectiveSampleCount.isFinite() && meanEffectiveSampleCount >= 0.0)
        require(referenceOnlyPixelFraction.isFinite() && referenceOnlyPixelFraction in 0.0..1.0)
        require(censoredPixelFraction.isFinite() && censoredPixelFraction in 0.0..1.0)
        require(maximumIncludedAlignmentSigmaPixels.isFinite() && maximumIncludedAlignmentSigmaPixels >= 0.0)
        require(rejectedInvisibleMeasurements >= 0L)
        require(rejectedCensoredMeasurements >= 0L)
        require(rejectedOccludedMeasurements >= 0L)
        require(rejectedResidualMeasurements >= 0L)
    }
}

data class FusedCfaSample(
    val radianceDn: Double,
    val varianceDn2: Double,
    val contributingFrames: Int,
    val effectiveSampleCount: Double,
    val lowCensored: Boolean,
    val highCensored: Boolean,
    val referenceOnly: Boolean,
    val measurementValid: Boolean,
    val cfaColor: CfaSiteColor,
) {
    init {
        require(radianceDn.isFinite() && radianceDn >= 0.0)
        require(varianceDn2.isFinite() && varianceDn2 >= 0.0)
        require(contributingFrames >= 0)
        require(effectiveSampleCount.isFinite() && effectiveSampleCount >= 0.0)
        require(!measurementValid || !lowCensored && !highCensored && contributingFrames > 0)
    }
}

class ReconstructionProvenance internal constructor(
    sourceCanonicalSha256: List<String>,
    includedOrdinals: List<Int>,
    val calibrationProfileSha256: String,
    val measurementBindingSha256: String,
    val alignmentEvidenceSha256: String,
    val algorithmId: AlgorithmId,
    val algorithmVersion: Int,
    val graphSha256: GraphSha256,
    val backend: GraphBackend,
    val determinismClass: DeterminismClass,
    val buildCommit: String,
    val learnedPriorChangedPixels: Boolean,
    val outputSha256: String,
    val manifestSha256: String,
) {
    val sourceCanonicalSha256: List<String> = Collections.unmodifiableList(ArrayList(sourceCanonicalSha256))
    val includedOrdinals: List<Int> = Collections.unmodifiableList(ArrayList(includedOrdinals.sorted()))

    init {
        require(this.sourceCanonicalSha256.isNotEmpty())
        require(this.sourceCanonicalSha256.all { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })
        require(this.includedOrdinals.isNotEmpty() && this.includedOrdinals.distinct().size == this.includedOrdinals.size)
        require(algorithmVersion > 0)
        require(!learnedPriorChangedPixels) { "M7 scalar reference cannot claim learned pixel mutation" }
        listOf(calibrationProfileSha256, measurementBindingSha256, alignmentEvidenceSha256, outputSha256, manifestSha256).forEach {
            require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' })
        }
    }
}

class FusedCfaRadiance internal constructor(
    val activeArea: IntRect,
    val cfaPattern: CfaPattern,
    val frameEvidence: List<ReconstructionFrameEvidence>,
    val fallbackKind: ReconstructionFallbackKind,
    val uncertainty: ReconstructionUncertaintySummary,
    val provenance: ReconstructionProvenance,
    radianceDn: FloatArray,
    varianceDn2: FloatArray,
    effectiveSampleCount: FloatArray,
    contributingFrames: ByteArray,
    flags: ByteArray,
) {
    val representation: GraphRepresentation = GraphRepresentation.FUSED_CFA_RADIANCE
    val photometricDomain: PhotometricDomain = PhotometricDomain.LINEAR_SENSOR_RADIANCE
    val uncertaintySemantics: GraphUncertaintySemantics = GraphUncertaintySemantics.FULL_RECONSTRUCTION_UNCERTAINTY
    val width: Int = activeArea.width
    val height: Int = activeArea.height
    val scaleNumerator: Int = 1
    val scaleDenominator: Int = 1

    private val radianceDn = radianceDn.copyOf()
    private val varianceDn2 = varianceDn2.copyOf()
    private val effectiveSampleCount = effectiveSampleCount.copyOf()
    private val contributingFrames = contributingFrames.copyOf()
    private val flags = flags.copyOf()

    init {
        val expected = activeArea.width.toLong() * activeArea.height.toLong()
        require(expected <= Int.MAX_VALUE.toLong())
        val size = expected.toInt()
        require(this.radianceDn.size == size)
        require(this.varianceDn2.size == size)
        require(this.effectiveSampleCount.size == size)
        require(this.contributingFrames.size == size)
        require(this.flags.size == size)
        require(frameEvidence.isNotEmpty())
    }

    fun sampleAt(sensorX: Int, sensorY: Int): FusedCfaSample {
        require(sensorX in activeArea.left until activeArea.left + activeArea.width &&
            sensorY in activeArea.top until activeArea.top + activeArea.height
        ) { "Fused CFA sample coordinate lies outside the active reference sensor grid" }
        val x = sensorX - activeArea.left
        val y = sensorY - activeArea.top
        val index = y * activeArea.width + x
        val flag = flags[index].toInt() and 0xff
        return FusedCfaSample(
            radianceDn = radianceDn[index].toDouble(),
            varianceDn2 = varianceDn2[index].toDouble(),
            contributingFrames = contributingFrames[index].toInt() and 0xff,
            effectiveSampleCount = effectiveSampleCount[index].toDouble(),
            lowCensored = flag and FLAG_LOW_CENSORED != 0,
            highCensored = flag and FLAG_HIGH_CENSORED != 0,
            referenceOnly = flag and FLAG_REFERENCE_ONLY != 0,
            measurementValid = flag and FLAG_VALID_MEASUREMENT != 0,
            cfaColor = cfaColorAt(cfaPattern, sensorX, sensorY),
        )
    }

    internal companion object {
        const val FLAG_LOW_CENSORED = 1
        const val FLAG_HIGH_CENSORED = 1 shl 1
        const val FLAG_REFERENCE_ONLY = 1 shl 2
        const val FLAG_VALID_MEASUREMENT = 1 shl 3
    }
}

private fun cfaColorAt(pattern: CfaPattern, x: Int, y: Int): CfaSiteColor {
    val site = ((y and 1) shl 1) or (x and 1)
    return when (pattern) {
        CfaPattern.RGGB -> when (site) { 0 -> CfaSiteColor.RED; 3 -> CfaSiteColor.BLUE; else -> CfaSiteColor.GREEN }
        CfaPattern.GRBG -> when (site) { 1 -> CfaSiteColor.RED; 2 -> CfaSiteColor.BLUE; else -> CfaSiteColor.GREEN }
        CfaPattern.GBRG -> when (site) { 1 -> CfaSiteColor.BLUE; 2 -> CfaSiteColor.RED; else -> CfaSiteColor.GREEN }
        CfaPattern.BGGR -> when (site) { 0 -> CfaSiteColor.BLUE; 3 -> CfaSiteColor.RED; else -> CfaSiteColor.GREEN }
    }
}

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
