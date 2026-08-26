package com.sahidcode404.camx.core.imaging.interchange

import com.sahidcode404.camx.core.imaging.calibration.M5CalibrationProfile
import com.sahidcode404.camx.core.imaging.reconstruction.FusedCfaRadiance
import java.security.MessageDigest
import kotlin.math.ceil

object M8BComputationalDngLimits {
    const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
    const val MAX_IMAGE_PIXELS = 50_000_000L
    const val MAX_METADATA_RESERVATION_BYTES = 1024L * 1024L
    const val MAX_PRIVATE_MANIFEST_BYTES = 256L * 1024L
    const val MAX_PRIVATE_DATA_BYTES = 768L * 1024L * 1024L
    const val MAX_UNIQUE_CAMERA_MODEL_BYTES = 255
    const val MAX_IFD_ENTRIES = 64
    const val MAX_STRIPS = 65_536
    const val ROWS_PER_STRIP = 64
    const val FLOAT_SAMPLE_BYTES = 4L
    const val PRIVATE_UNCERTAINTY_BYTES_PER_PIXEL = 10L
    const val PRIVATE_HEADER_BYTES = 20L
    const val MAX_SRATIONAL_DENOMINATOR = 1_000_000
}

data class ComputationalCfaDngAuthority(
    val uniqueCameraModel: String,
    val calibrationProfile: M5CalibrationProfile,
) {
    init {
        val bytes = uniqueCameraModel.toByteArray(Charsets.UTF_8)
        require(uniqueCameraModel.isNotBlank()) { "M8B UniqueCameraModel authority must be nonblank" }
        require(bytes.size <= M8BComputationalDngLimits.MAX_UNIQUE_CAMERA_MODEL_BYTES) {
            "M8B UniqueCameraModel authority exceeds the bounded DNG field"
        }
        require(uniqueCameraModel.all { it.code in 0x20..0x7e }) {
            "M8B UniqueCameraModel authority must use printable ASCII"
        }
    }
}

class ComputationalDngReservation private constructor(
    val width: Int,
    val height: Int,
    val pixelCount: Long,
    val rowsPerStrip: Int,
    val stripCount: Int,
    val imageBytes: Long,
    val privateDataBytes: Long,
    val metadataReservationBytes: Long,
    val requiredOutputBytes: Long,
    val maxOutputBytes: Long,
    val outputWhiteLevelDn: Long,
) {
    companion object {
        fun forNegative(
            negative: FusedCfaRadiance,
            authority: ComputationalCfaDngAuthority,
            privateManifestBytes: Int,
            maxOutputBytes: Long,
        ): ComputationalDngReservation {
            val profile = authority.calibrationProfile
            require(profile.digestSha256() == negative.provenance.calibrationProfileSha256) {
                "M8B calibration authority is not bound to the M7 computational negative"
            }
            require(profile.activeArea == negative.activeArea && profile.cfaPattern == negative.cfaPattern) {
                "M8B calibration geometry/CFA authority diverges from the M7 computational negative"
            }
            require(profile.colorCalibration != null) {
                "M8B non-monochrome CFA DNG requires accepted color calibration; metadata is never fabricated"
            }
            require(profile.colorCalibration.entries.all { it.illuminant.code in 1..0xffff }) {
                "M8B DNG calibration illuminants must fit the standards field"
            }
            require(privateManifestBytes in 1..M8BComputationalDngLimits.MAX_PRIVATE_MANIFEST_BYTES.toInt()) {
                "M8B private manifest must be non-empty and bounded"
            }

            val pixels = checkedMultiply(
                negative.width.toLong(),
                negative.height.toLong(),
                "M8B output pixel count overflow",
            )
            require(pixels in 1..M8BComputationalDngLimits.MAX_IMAGE_PIXELS) {
                "M8B computational DNG raster exceeds the bounded reference implementation"
            }
            val rowsPerStrip = minOf(M8BComputationalDngLimits.ROWS_PER_STRIP, negative.height)
            val stripCount = ((negative.height.toLong() + rowsPerStrip - 1L) / rowsPerStrip).toInt()
            require(stripCount in 1..M8BComputationalDngLimits.MAX_STRIPS) {
                "M8B computational DNG strip table exceeds the bounded reference implementation"
            }
            val imageBytes = checkedMultiply(
                pixels,
                M8BComputationalDngLimits.FLOAT_SAMPLE_BYTES,
                "M8B float raster byte count overflow",
            )
            val uncertaintyBytes = checkedMultiply(
                pixels,
                M8BComputationalDngLimits.PRIVATE_UNCERTAINTY_BYTES_PER_PIXEL,
                "M8B uncertainty byte count overflow",
            )
            val privateBytes = checkedAdd(
                checkedAdd(
                    M8BComputationalDngLimits.PRIVATE_HEADER_BYTES,
                    privateManifestBytes.toLong(),
                    "M8B private-data byte count overflow",
                ),
                uncertaintyBytes,
                "M8B private-data byte count overflow",
            )
            require(privateBytes <= M8BComputationalDngLimits.MAX_PRIVATE_DATA_BYTES) {
                "M8B DNG private uncertainty/provenance payload exceeds its parser/writer bound"
            }
            val required = checkedAdd(
                checkedAdd(
                    M8BComputationalDngLimits.MAX_METADATA_RESERVATION_BYTES,
                    privateBytes,
                    "M8B output reservation overflow",
                ),
                checkedAdd(imageBytes, 16L, "M8B output reservation overflow"),
                "M8B output reservation overflow",
            )
            require(maxOutputBytes in required..M8BComputationalDngLimits.MAX_FILE_BYTES) {
                "M8B output budget does not cover the proven streaming DNG extent"
            }

            val outputWhite = ceil(
                profile.whiteLevelsDn.values().zip(profile.blackLevelsDn.values()) { white, black -> white - black }
                    .maxOrNull() ?: error("M8B calibration has no CFA levels"),
            ).toLong()
            require(outputWhite in 1..0xffff_ffffL) {
                "M8B output-derived DNG white level must fit TIFF LONG"
            }

            return ComputationalDngReservation(
                width = negative.width,
                height = negative.height,
                pixelCount = pixels,
                rowsPerStrip = rowsPerStrip,
                stripCount = stripCount,
                imageBytes = imageBytes,
                privateDataBytes = privateBytes,
                metadataReservationBytes = M8BComputationalDngLimits.MAX_METADATA_RESERVATION_BYTES,
                requiredOutputBytes = required,
                maxOutputBytes = maxOutputBytes,
                outputWhiteLevelDn = outputWhite,
            )
        }
    }
}

data class ComputationalDngReceipt(
    val byteCount: Long,
    val sha256: String,
    val sourceOutputSha256: String,
    val sourceManifestSha256: String,
    val privateManifestSha256: String,
    val stripCount: Int,
    val outputWhiteLevelDn: Long,
) {
    init {
        require(byteCount > 0L)
        listOf(sha256, sourceOutputSha256, sourceManifestSha256, privateManifestSha256).forEach {
            require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' })
        }
        require(stripCount > 0)
        require(outputWhiteLevelDn > 0L)
    }
}

internal object ComputationalDngManifest {
    fun canonical(
        negative: FusedCfaRadiance,
        authority: ComputationalCfaDngAuthority,
    ): String = buildString {
        val profile = authority.calibrationProfile
        append("camx2-computational-dng-private-manifest-v1\n")
        append("product=FusedCfaRadiance\n")
        append("encoding=float32-black-subtracted-linear-sensor-radiance\n")
        append("uniqueCameraModel=").append(authority.uniqueCameraModel.length).append(':')
            .append(authority.uniqueCameraModel).append('\n')
        append("grid=").append(negative.width).append('x').append(negative.height).append("@")
            .append(negative.activeArea.left).append(',').append(negative.activeArea.top).append('\n')
        append("cfa=").append(negative.cfaPattern.name).append('\n')
        append("calibrationProfileSha256=").append(negative.provenance.calibrationProfileSha256).append('\n')
        append("measurementBindingSha256=").append(negative.provenance.measurementBindingSha256).append('\n')
        append("alignmentEvidenceSha256=").append(negative.provenance.alignmentEvidenceSha256).append('\n')
        append("algorithmId=").append(negative.provenance.algorithmId.value).append('\n')
        append("algorithmVersion=").append(negative.provenance.algorithmVersion).append('\n')
        append("graphSha256=").append(negative.provenance.graphSha256.value).append('\n')
        append("backend=").append(negative.provenance.backend.name).append('\n')
        append("determinism=").append(negative.provenance.determinismClass.name).append('\n')
        append("buildCommit=").append(negative.provenance.buildCommit.length).append(':')
            .append(negative.provenance.buildCommit).append('\n')
        append("learnedPriorChangedPixels=").append(negative.provenance.learnedPriorChangedPixels).append('\n')
        append("outputSha256=").append(negative.provenance.outputSha256).append('\n')
        append("m7ManifestSha256=").append(negative.provenance.manifestSha256).append('\n')
        append("sourceCanonicalSha256=").append(negative.provenance.sourceCanonicalSha256.joinToString(",")).append('\n')
        append("includedOrdinals=").append(negative.provenance.includedOrdinals.joinToString(",")).append('\n')
        append("outputBlackLevelDn=0\n")
        append("sourceSignalRangesDn=")
        profile.whiteLevelsDn.values().zip(profile.blackLevelsDn.values()) { white, black ->
            java.lang.Double.toHexString(white - black)
        }.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append('\n')
        append("uncertainty.meanRadiometricVarianceDn2=")
            .append(java.lang.Double.toHexString(negative.uncertainty.meanRadiometricVarianceDn2)).append('\n')
        append("uncertainty.maximumRadiometricVarianceDn2=")
            .append(java.lang.Double.toHexString(negative.uncertainty.maximumRadiometricVarianceDn2)).append('\n')
        append("uncertainty.meanEffectiveSampleCount=")
            .append(java.lang.Double.toHexString(negative.uncertainty.meanEffectiveSampleCount)).append('\n')
        append("uncertainty.referenceOnlyPixelFraction=")
            .append(java.lang.Double.toHexString(negative.uncertainty.referenceOnlyPixelFraction)).append('\n')
        append("uncertainty.censoredPixelFraction=")
            .append(java.lang.Double.toHexString(negative.uncertainty.censoredPixelFraction)).append('\n')
        append("uncertainty.maximumIncludedAlignmentSigmaPixels=")
            .append(java.lang.Double.toHexString(negative.uncertainty.maximumIncludedAlignmentSigmaPixels)).append('\n')
        append("uncertainty.rejectedInvisibleMeasurements=").append(negative.uncertainty.rejectedInvisibleMeasurements).append('\n')
        append("uncertainty.rejectedCensoredMeasurements=").append(negative.uncertainty.rejectedCensoredMeasurements).append('\n')
        append("uncertainty.rejectedOccludedMeasurements=").append(negative.uncertainty.rejectedOccludedMeasurements).append('\n')
        append("uncertainty.rejectedResidualMeasurements=").append(negative.uncertainty.rejectedResidualMeasurements).append('\n')
        profile.colorCalibration?.entries?.forEachIndexed { index, entry ->
            append("color[").append(index).append("].illuminant=").append(entry.illuminant.code)
                .append(':').append(entry.illuminant.label.length).append(':').append(entry.illuminant.label).append('\n')
            append("color[").append(index).append("].sensorToXyz=")
            entry.sensorToXyz.values().forEachIndexed { valueIndex, value ->
                if (valueIndex > 0) append(',')
                append(java.lang.Double.toHexString(value))
            }
            append('\n')
        }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun checkedAdd(left: Long, right: Long, message: String): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException(message, error)
}

internal fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
    Math.multiplyExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException(message, error)
}
