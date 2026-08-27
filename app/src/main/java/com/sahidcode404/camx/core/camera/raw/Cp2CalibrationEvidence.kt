package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import java.security.MessageDigest
import java.util.Collections

/** Exact integer rectangle copied out of Camera2 metadata. */
data class Cp2RectEvidence(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "CP2 rectangle origin cannot be negative" }
        require(right > left && bottom > top) { "CP2 rectangle must have positive area" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Exact rational Camera2 matrix element; never rounded during evidence capture. */
data class Cp2RationalEvidence(
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(denominator != 0) { "CP2 rational denominator cannot be zero" }
    }

    fun asDouble(): Double = numerator.toDouble() / denominator.toDouble()
}

class Cp2Matrix3x3Evidence(values: List<Cp2RationalEvidence>) {
    val values: List<Cp2RationalEvidence> = Collections.unmodifiableList(ArrayList(values))

    init {
        require(this.values.size == 9) { "CP2 color matrix must contain exactly nine rational elements" }
    }
}

/** Camera2 noise profile pair: variance = shotSlope * signal + readVariance. */
data class Cp2NoiseCoefficient(
    val shotSlope: Double,
    val readVariance: Double,
) {
    init {
        require(shotSlope.isFinite() && shotSlope >= 0.0) { "CP2 shot-noise slope must be finite and non-negative" }
        require(readVariance.isFinite() && readVariance >= 0.0) { "CP2 read-noise variance must be finite and non-negative" }
    }
}

/**
 * Immutable static calibration copied from the exact CameraCharacteristics used by the RAW owner.
 * Optional Camera2 metadata remains nullable instead of being replaced by guessed constants.
 */
class Cp2StaticCalibrationObservation(
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val rawSize: IntSize,
    val cfaArrangement: Int?,
    val activeArray: Cp2RectEvidence?,
    val preCorrectionActiveArray: Cp2RectEvidence?,
    blackLevels: List<Int>?,
    val whiteLevel: Int?,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
    val colorTransform1: Cp2Matrix3x3Evidence?,
    val colorTransform2: Cp2Matrix3x3Evidence?,
    val calibrationTransform1: Cp2Matrix3x3Evidence?,
    val calibrationTransform2: Cp2Matrix3x3Evidence?,
    val forwardMatrix1: Cp2Matrix3x3Evidence?,
    val forwardMatrix2: Cp2Matrix3x3Evidence?,
) {
    val blackLevels: List<Int>? = blackLevels?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        require(this.blackLevels == null || this.blackLevels.size == 4) {
            "CP2 static black-level pattern must contain exactly four CFA entries"
        }
        require(this.blackLevels == null || this.blackLevels.all { it >= 0 }) {
            "CP2 static black levels cannot be negative"
        }
        require(whiteLevel == null || whiteLevel > 0) { "CP2 white level must be positive when present" }
    }
}

/** Immutable per-request calibration metadata copied from the exact TotalCaptureResult. */
class Cp2DynamicCalibrationObservation(
    val ordinal: Int,
    val sensorTimestampNs: Long,
    dynamicBlackLevels: List<Double>?,
    val dynamicWhiteLevel: Int?,
    noiseProfile: List<Cp2NoiseCoefficient>?,
) {
    val dynamicBlackLevels: List<Double>? = dynamicBlackLevels?.let {
        Collections.unmodifiableList(ArrayList(it))
    }
    val noiseProfile: List<Cp2NoiseCoefficient>? = noiseProfile?.let {
        Collections.unmodifiableList(ArrayList(it))
    }

    init {
        require(ordinal >= 0) { "CP2 dynamic ordinal cannot be negative" }
        require(sensorTimestampNs > 0L) { "CP2 dynamic SENSOR_TIMESTAMP must be positive" }
        require(this.dynamicBlackLevels == null || this.dynamicBlackLevels.size == 4) {
            "CP2 dynamic black-level pattern must contain exactly four CFA entries"
        }
        require(this.dynamicBlackLevels == null || this.dynamicBlackLevels.all { it.isFinite() && it >= 0.0 }) {
            "CP2 dynamic black levels must be finite and non-negative"
        }
        require(dynamicWhiteLevel == null || dynamicWhiteLevel > 0) {
            "CP2 dynamic white level must be positive when present"
        }
        require(this.noiseProfile == null || this.noiseProfile.size == 4) {
            "CP2 Bayer noise profile must contain exactly four channel entries"
        }
    }
}

data class Cp2FrameCalibrationBinding(
    val ordinal: Int,
    val sensorTimestampNs: Long,
    val sourceCanonicalSha256: String,
    val observation: Cp2DynamicCalibrationObservation?,
) {
    val exactResultBound: Boolean = observation?.let {
        it.ordinal == ordinal && it.sensorTimestampNs == sensorTimestampNs
    } == true
}

data class Cp2CalibrationReport(
    val requestedFrames: Int,
    val exactDynamicBindings: Int,
    val noiseProfileFrames: Int,
    val dynamicBlackLevelFrames: Int,
    val dynamicWhiteLevelFrames: Int,
    val staticIdentityMatches: Boolean,
    val bayerCfaSupported: Boolean,
    val activeArrayPresent: Boolean,
    val staticBlackLevelsPresent: Boolean,
    val staticWhiteLevelPresent: Boolean,
    val colorMatrixPairsPresent: Int,
    val unboundOrdinals: List<Int>,
    val calibrationFingerprintSha256: String,
    val evidencePersisted: Boolean = false,
) {
    init {
        require(requestedFrames > 0)
        require(exactDynamicBindings in 0..requestedFrames)
        require(noiseProfileFrames in 0..requestedFrames)
        require(dynamicBlackLevelFrames in 0..requestedFrames)
        require(dynamicWhiteLevelFrames in 0..requestedFrames)
        require(colorMatrixPairsPresent in 0..2)
        require(calibrationFingerprintSha256.length == 64)
    }

    /** CP2 core success means exact frame binding plus truthful static sensor interpretation. */
    val success: Boolean =
        staticIdentityMatches &&
            bayerCfaSupported &&
            activeArrayPresent &&
            staticBlackLevelsPresent &&
            staticWhiteLevelPresent &&
            exactDynamicBindings == requestedFrames &&
            unboundOrdinals.isEmpty()

    /** Noise-aware CP3 may use Camera2 noise only when every burst member actually supplied it. */
    val fusionNoiseReady: Boolean = success && noiseProfileFrames == requestedFrames

    /**
     * Frozen M5 additionally requires fixed-pattern-noise evidence, which Camera2 does not publish.
     * CP2 therefore never fabricates a complete M5 profile from Camera2 metadata alone.
     */
    val directM5ProfileReady: Boolean = false
}

class Cp2CalibrationBundle(
    val staticObservation: Cp2StaticCalibrationObservation?,
    bindings: List<Cp2FrameCalibrationBinding>,
    val report: Cp2CalibrationReport,
) {
    val bindings: List<Cp2FrameCalibrationBinding> = Collections.unmodifiableList(ArrayList(bindings.sortedBy { it.ordinal }))

    init {
        require(this.bindings.size == report.requestedFrames) {
            "CP2 bundle membership must match the captured RAW FrameSet"
        }
        require(this.bindings.indices.all { this.bindings[it].ordinal == it }) {
            "CP2 bundle ordinals must remain contiguous"
        }
    }
}

object Cp2CalibrationAssembler {
    fun assemble(
        frameSet: ImmutableRawFrameSet,
        staticObservation: Cp2StaticCalibrationObservation?,
        dynamicObservations: List<Cp2DynamicCalibrationObservation>,
    ): Cp2CalibrationBundle {
        val context = frameSet.context
        val staticMatches = staticObservation?.let { static ->
            static.canonicalLensFingerprint == context.canonicalLensFingerprint &&
                static.cameraProfileFingerprint == context.cameraProfileFingerprint &&
                static.routeId == context.routeId &&
                static.rawSize == context.rawSize
        } == true

        val dynamicByOrdinal = dynamicObservations.associateBy { it.ordinal }
        require(dynamicByOrdinal.size == dynamicObservations.size) {
            "CP2 dynamic evidence contains duplicate request ordinals"
        }
        val bindings = frameSet.frames.map { frame ->
            val candidate = dynamicByOrdinal[frame.ordinal]
                ?.takeIf { it.sensorTimestampNs == frame.metadata.sensorTimestampNs }
            Cp2FrameCalibrationBinding(
                ordinal = frame.ordinal,
                sensorTimestampNs = frame.metadata.sensorTimestampNs,
                sourceCanonicalSha256 = frame.canonicalSha256,
                observation = candidate,
            )
        }
        val exact = bindings.count(Cp2FrameCalibrationBinding::exactResultBound)
        val noise = bindings.count { binding -> binding.observation?.noiseProfile?.size == 4 }
        val dynamicBlack = bindings.count { binding -> binding.observation?.dynamicBlackLevels?.size == 4 }
        val dynamicWhite = bindings.count { binding -> binding.observation?.dynamicWhiteLevel != null }
        val unbound = bindings.filterNot(Cp2FrameCalibrationBinding::exactResultBound).map { it.ordinal }
        val bayer = staticObservation?.cfaArrangement in 0..3
        val staticBlack = staticObservation?.blackLevels?.size == 4
        val staticWhite = staticObservation?.whiteLevel?.let { it > 0 } == true
        val matrixPairs = listOf(
            staticObservation?.colorTransform1 to staticObservation?.referenceIlluminant1,
            staticObservation?.colorTransform2 to staticObservation?.referenceIlluminant2,
        ).count { (matrix, illuminant) -> matrix != null && illuminant != null }

        val digest = digest(staticObservation, bindings)
        val report = Cp2CalibrationReport(
            requestedFrames = frameSet.frames.size,
            exactDynamicBindings = exact,
            noiseProfileFrames = noise,
            dynamicBlackLevelFrames = dynamicBlack,
            dynamicWhiteLevelFrames = dynamicWhite,
            staticIdentityMatches = staticMatches,
            bayerCfaSupported = bayer,
            activeArrayPresent = staticObservation?.activeArray != null,
            staticBlackLevelsPresent = staticBlack,
            staticWhiteLevelPresent = staticWhite,
            colorMatrixPairsPresent = matrixPairs,
            unboundOrdinals = Collections.unmodifiableList(ArrayList(unbound)),
            calibrationFingerprintSha256 = digest,
        )
        return Cp2CalibrationBundle(staticObservation, bindings, report)
    }

    private fun digest(
        staticObservation: Cp2StaticCalibrationObservation?,
        bindings: List<Cp2FrameCalibrationBinding>,
    ): String {
        val canonical = buildString {
            append("camx2-cp2-v1")
            if (staticObservation == null) {
                append("|static:null")
            } else {
                val static = staticObservation
                append("|lens:").append(static.canonicalLensFingerprint.value)
                append("|profile:").append(static.cameraProfileFingerprint.value)
                append("|route:").append(static.routeId.value)
                append("|raw:").append(static.rawSize.width).append('x').append(static.rawSize.height)
                append("|cfa:").append(static.cfaArrangement)
                appendRect("active", static.activeArray)
                appendRect("pre", static.preCorrectionActiveArray)
                append("|black:").append(static.blackLevels?.joinToString(",") ?: "null")
                append("|white:").append(static.whiteLevel)
                append("|illum1:").append(static.referenceIlluminant1)
                append("|illum2:").append(static.referenceIlluminant2)
                appendMatrix("color1", static.colorTransform1)
                appendMatrix("color2", static.colorTransform2)
                appendMatrix("cal1", static.calibrationTransform1)
                appendMatrix("cal2", static.calibrationTransform2)
                appendMatrix("forward1", static.forwardMatrix1)
                appendMatrix("forward2", static.forwardMatrix2)
            }
            bindings.forEach { binding ->
                append("|frame:").append(binding.ordinal)
                append(':').append(binding.sensorTimestampNs)
                append(':').append(binding.sourceCanonicalSha256)
                val dynamic = binding.observation
                if (dynamic == null) {
                    append(":dynamic:null")
                } else {
                    append(":black=").append(dynamic.dynamicBlackLevels?.joinToString(",") { java.lang.Double.toHexString(it) } ?: "null")
                    append(":white=").append(dynamic.dynamicWhiteLevel)
                    append(":noise=").append(
                        dynamic.noiseProfile?.joinToString(",") {
                            java.lang.Double.toHexString(it.shotSlope) + "/" + java.lang.Double.toHexString(it.readVariance)
                        } ?: "null",
                    )
                }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.appendRect(label: String, rect: Cp2RectEvidence?) {
        append('|').append(label).append(':')
        if (rect == null) append("null") else append(rect.left).append(',').append(rect.top).append(',')
            .append(rect.right).append(',').append(rect.bottom)
    }

    private fun StringBuilder.appendMatrix(label: String, matrix: Cp2Matrix3x3Evidence?) {
        append('|').append(label).append(':')
        if (matrix == null) {
            append("null")
        } else {
            matrix.values.forEachIndexed { index, rational ->
                if (index > 0) append(',')
                append(rational.numerator).append('/').append(rational.denominator)
            }
        }
    }
}
