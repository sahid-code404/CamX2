package com.sahidcode404.camx.core.imaging.calibration

import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs

object M5CalibrationLimits {
    const val MAX_PROFILE_ID_CHARS = 128
    const val MAX_PROFILE_VERSION_CHARS = 64
    const val MAX_ILLUMINANT_LABEL_CHARS = 64
    const val MAX_COLOR_CALIBRATIONS = 2
    const val MAX_VALIDATION_SAMPLES = 1_000_000
    const val RAW16_MAX_DN = 65_535.0
    const val MAX_RESIDENT_BYTES = 1024L * 1024L * 1024L
    const val CALIBRATION_SAFETY_MARGIN_BYTES = 1024L * 1024L
}

enum class CalibrationOrigin {
    CAMERA_METADATA,
    PROFILED_CORPUS,
    COMBINED,
}

enum class SensorSampleByteOrder {
    LITTLE_ENDIAN_16,
}

enum class CfaSiteColor {
    RED,
    GREEN,
    BLUE,
}

data class CfaDoubleQuad(
    val site00: Double,
    val site01: Double,
    val site10: Double,
    val site11: Double,
) {
    init {
        require(values().all { it.isFinite() && it >= 0.0 }) {
            "CFA calibration values must be finite and non-negative"
        }
    }

    fun valueAt(x: Int, y: Int): Double = valueByIndex(((y and 1) shl 1) or (x and 1))

    fun valueByIndex(index: Int): Double = when (index) {
        0 -> site00
        1 -> site01
        2 -> site10
        3 -> site11
        else -> throw IllegalArgumentException("CFA site index must be in 0..3")
    }

    fun values(): List<Double> = listOf(site00, site01, site10, site11)
}

data class CalibrationConfidenceVector(
    val blackLevel: Double,
    val whiteLevel: Double,
    val cfaAndActiveArea: Double,
    val shotNoise: Double,
    val readNoise: Double,
    val fixedPatternNoise: Double,
    val colorCalibration: Double?,
) {
    init {
        listOf(blackLevel, whiteLevel, cfaAndActiveArea, shotNoise, readNoise, fixedPatternNoise).forEach {
            require(it.isFinite() && it in 0.0..1.0) { "Calibration confidence must be finite and in [0, 1]" }
        }
        require(colorCalibration == null || colorCalibration.isFinite() && colorCalibration in 0.0..1.0) {
            "Color-calibration confidence must be absent or finite and in [0, 1]"
        }
    }
}

data class NoiseParameters(
    val shotVarianceSlopeDn2PerDn: Double,
    val readVarianceDn2: Double,
    val fixedPatternFractionSigma: Double,
) {
    init {
        require(shotVarianceSlopeDn2PerDn.isFinite() && shotVarianceSlopeDn2PerDn >= 0.0)
        require(readVarianceDn2.isFinite() && readVarianceDn2 >= 0.0)
        require(fixedPatternFractionSigma.isFinite() && fixedPatternFractionSigma >= 0.0)
    }

    fun varianceForSignalDn(signalDn: Double): Double {
        require(signalDn.isFinite() && signalDn >= 0.0) { "Signal DN must be finite and non-negative" }
        val fpnSigmaDn = fixedPatternFractionSigma * signalDn
        return shotVarianceSlopeDn2PerDn * signalDn + readVarianceDn2 + fpnSigmaDn * fpnSigmaDn
    }
}

data class CfaNoiseModel(
    val site00: NoiseParameters,
    val site01: NoiseParameters,
    val site10: NoiseParameters,
    val site11: NoiseParameters,
) {
    fun parametersAt(x: Int, y: Int): NoiseParameters = parametersByIndex(((y and 1) shl 1) or (x and 1))

    fun parametersByIndex(index: Int): NoiseParameters = when (index) {
        0 -> site00
        1 -> site01
        2 -> site10
        3 -> site11
        else -> throw IllegalArgumentException("CFA site index must be in 0..3")
    }

    fun values(): List<NoiseParameters> = listOf(site00, site01, site10, site11)
}

data class Matrix3x3(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
) {
    init {
        require(values().all(Double::isFinite)) { "Color matrix values must be finite" }
        require(abs(determinant()) > 1e-12) { "Color matrix must be non-singular" }
    }

    fun determinant(): Double =
        m00 * (m11 * m22 - m12 * m21) -
            m01 * (m10 * m22 - m12 * m20) +
            m02 * (m10 * m21 - m11 * m20)

    fun values(): List<Double> = listOf(m00, m01, m02, m10, m11, m12, m20, m21, m22)
}

data class ReferenceIlluminant(
    val code: Int,
    val label: String,
) {
    init {
        require(code > 0) { "Reference illuminant code must be positive" }
        require(label.isNotBlank() && label.length <= M5CalibrationLimits.MAX_ILLUMINANT_LABEL_CHARS) {
            "Reference illuminant label must be nonblank and bounded"
        }
    }
}

data class ColorMatrixEntry(
    val illuminant: ReferenceIlluminant,
    val sensorToXyz: Matrix3x3,
)

class ColorMatrixCalibration(entries: List<ColorMatrixEntry>) {
    val entries: List<ColorMatrixEntry> = Collections.unmodifiableList(
        ArrayList(entries.sortedBy { it.illuminant.code }),
    )

    init {
        require(this.entries.isNotEmpty() && this.entries.size <= M5CalibrationLimits.MAX_COLOR_CALIBRATIONS) {
            "Color calibration requires one or two illuminant-bound matrices"
        }
        require(this.entries.map { it.illuminant.code }.distinct().size == this.entries.size) {
            "Color calibration illuminants must be unique"
        }
    }
}

class M5CalibrationProfile(
    val profileId: String,
    val version: String,
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val rawSize: IntSize,
    val activeArea: IntRect,
    val cfaPattern: CfaPattern,
    val blackLevelsDn: CfaDoubleQuad,
    val whiteLevelsDn: CfaDoubleQuad,
    val noiseModel: CfaNoiseModel,
    val colorCalibration: ColorMatrixCalibration?,
    val confidence: CalibrationConfidenceVector,
    val origin: CalibrationOrigin,
    val sampleByteOrder: SensorSampleByteOrder = SensorSampleByteOrder.LITTLE_ENDIAN_16,
) {
    init {
        require(profileId.isNotBlank() && profileId.length <= M5CalibrationLimits.MAX_PROFILE_ID_CHARS) {
            "Calibration profile ID must be nonblank and bounded"
        }
        require(version.isNotBlank() && version.length <= M5CalibrationLimits.MAX_PROFILE_VERSION_CHARS) {
            "Calibration profile version must be nonblank and bounded"
        }
        require(activeArea.left.toLong() + activeArea.width.toLong() <= rawSize.width.toLong() &&
            activeArea.top.toLong() + activeArea.height.toLong() <= rawSize.height.toLong()
        ) { "Calibration active area must lie within the RAW raster" }
        for (site in 0..3) {
            val black = blackLevelsDn.valueByIndex(site)
            val white = whiteLevelsDn.valueByIndex(site)
            require(white > black && white <= M5CalibrationLimits.RAW16_MAX_DN) {
                "Each CFA site requires a finite white level above black and within RAW16"
            }
        }
        require((colorCalibration == null) == (confidence.colorCalibration == null)) {
            "Color confidence must exist exactly when color calibration exists"
        }
    }

    fun siteIndexAt(x: Int, y: Int): Int = ((y and 1) shl 1) or (x and 1)

    fun siteColorAt(x: Int, y: Int): CfaSiteColor = when (cfaPattern) {
        CfaPattern.RGGB -> when (siteIndexAt(x, y)) {
            0 -> CfaSiteColor.RED
            3 -> CfaSiteColor.BLUE
            else -> CfaSiteColor.GREEN
        }
        CfaPattern.GRBG -> when (siteIndexAt(x, y)) {
            1 -> CfaSiteColor.RED
            2 -> CfaSiteColor.BLUE
            else -> CfaSiteColor.GREEN
        }
        CfaPattern.GBRG -> when (siteIndexAt(x, y)) {
            1 -> CfaSiteColor.BLUE
            2 -> CfaSiteColor.RED
            else -> CfaSiteColor.GREEN
        }
        CfaPattern.BGGR -> when (siteIndexAt(x, y)) {
            0 -> CfaSiteColor.BLUE
            3 -> CfaSiteColor.RED
            else -> CfaSiteColor.GREEN
        }
    }

    fun digestSha256(): String {
        val canonical = buildString {
            append(profileId).append('|').append(version).append('|')
            append(canonicalLensFingerprint.value).append('|').append(cameraProfileFingerprint.value).append('|')
            append(rawSize.width).append('x').append(rawSize.height).append('|')
            append(activeArea.left).append(',').append(activeArea.top).append(',')
            append(activeArea.width).append(',').append(activeArea.height).append('|')
            append(cfaPattern.name).append('|').append(origin.name).append('|').append(sampleByteOrder.name)
            blackLevelsDn.values().forEach { append('|').append(java.lang.Double.toHexString(it)) }
            whiteLevelsDn.values().forEach { append('|').append(java.lang.Double.toHexString(it)) }
            noiseModel.values().forEach { noise ->
                append('|').append(java.lang.Double.toHexString(noise.shotVarianceSlopeDn2PerDn))
                append('|').append(java.lang.Double.toHexString(noise.readVarianceDn2))
                append('|').append(java.lang.Double.toHexString(noise.fixedPatternFractionSigma))
            }
            append('|').append(java.lang.Double.toHexString(confidence.blackLevel))
            append('|').append(java.lang.Double.toHexString(confidence.whiteLevel))
            append('|').append(java.lang.Double.toHexString(confidence.cfaAndActiveArea))
            append('|').append(java.lang.Double.toHexString(confidence.shotNoise))
            append('|').append(java.lang.Double.toHexString(confidence.readNoise))
            append('|').append(java.lang.Double.toHexString(confidence.fixedPatternNoise))
            confidence.colorCalibration?.let { append('|').append(java.lang.Double.toHexString(it)) }
            colorCalibration?.entries?.forEach { entry ->
                append('|').append(entry.illuminant.code).append(':').append(entry.illuminant.label)
                entry.sensorToXyz.values().forEach { append('|').append(java.lang.Double.toHexString(it)) }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
