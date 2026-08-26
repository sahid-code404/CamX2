package com.sahidcode404.camx.core.imaging.calibration

import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.raw.ImmutableRawBurstFrame
import com.sahidcode404.camx.core.camera.raw.ImmutableRawFrameSet
import com.sahidcode404.camx.core.camera.raw.RawBurstFrameMetadata
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class CalibrationReservation private constructor(
    val inputCanonicalBytes: Long,
    val calibratedCopyBytes: Long,
    val safetyMarginBytes: Long,
    val requiredResidentBytes: Long,
    val maxResidentBytes: Long,
) {
    companion object {
        fun forFrameSet(frameSet: ImmutableRawFrameSet, maxResidentBytes: Long): CalibrationReservation {
            val input = frameSet.totalCanonicalBytes
            require(input > 0L) { "Calibration requires non-empty canonical burst evidence" }
            val copies = input
            val required = checkedAdd(
                checkedAdd(input, copies, "M5 resident byte proof overflow"),
                M5CalibrationLimits.CALIBRATION_SAFETY_MARGIN_BYTES,
                "M5 resident byte proof overflow",
            )
            require(maxResidentBytes in required..M5CalibrationLimits.MAX_RESIDENT_BYTES) {
                "M5 resident budget does not cover the immutable calibration copy proof"
            }
            return CalibrationReservation(
                inputCanonicalBytes = input,
                calibratedCopyBytes = copies,
                safetyMarginBytes = M5CalibrationLimits.CALIBRATION_SAFETY_MARGIN_BYTES,
                requiredResidentBytes = required,
                maxResidentBytes = maxResidentBytes,
            )
        }
    }
}

data class CalibratedSample(
    val rawDn: Int,
    val blackLevelDn: Double,
    val whiteLevelDn: Double,
    val signalDn: Double,
    val normalizedSignal: Double,
    val varianceDn2: Double,
    val lowCensored: Boolean,
    val highCensored: Boolean,
    val insideActiveArea: Boolean,
    val cfaSiteIndex: Int,
    val cfaColor: CfaSiteColor,
)

class CalibratedMeasurementFrame internal constructor(
    val ordinal: Int,
    val metadata: RawBurstFrameMetadata,
    val sourceCanonicalSha256: String,
    val profile: M5CalibrationProfile,
    canonicalRaster: ByteArray,
) {
    private val canonicalRaster = canonicalRaster.copyOf()
    val copiedCanonicalSha256: String = sha256(this.canonicalRaster)

    init {
        val expectedBytes = checkedMultiply(
            checkedMultiply(profile.rawSize.width.toLong(), profile.rawSize.height.toLong(), "RAW pixel count overflow"),
            2L,
            "RAW16 byte count overflow",
        )
        require(this.canonicalRaster.size.toLong() == expectedBytes) {
            "Calibrated frame canonical extent does not match the exact calibration profile"
        }
        require(copiedCanonicalSha256 == sourceCanonicalSha256) {
            "M5 calibration input copy changed canonical sensor evidence"
        }
    }

    fun copyCanonicalRaster(): ByteArray = canonicalRaster.copyOf()

    fun sampleAt(x: Int, y: Int): CalibratedSample {
        require(x in 0 until profile.rawSize.width && y in 0 until profile.rawSize.height) {
            "Calibration sample coordinate lies outside the RAW raster"
        }
        val pixelIndex = y.toLong() * profile.rawSize.width.toLong() + x.toLong()
        val byteIndexLong = checkedMultiply(pixelIndex, 2L, "RAW16 sample offset overflow")
        require(byteIndexLong <= Int.MAX_VALUE.toLong() - 1L) { "RAW16 sample offset exceeds JVM array indexing" }
        val byteIndex = byteIndexLong.toInt()
        val rawDn = when (profile.sampleByteOrder) {
            SensorSampleByteOrder.LITTLE_ENDIAN_16 ->
                (canonicalRaster[byteIndex].toInt() and 0xff) or
                    ((canonicalRaster[byteIndex + 1].toInt() and 0xff) shl 8)
        }
        val black = profile.blackLevelsDn.valueAt(x, y)
        val white = profile.whiteLevelsDn.valueAt(x, y)
        val unclampedSignal = rawDn.toDouble() - black
        val signal = max(0.0, unclampedSignal)
        val range = white - black
        val normalized = (signal / range).coerceIn(0.0, 1.0)
        val varianceSignal = min(signal, range)
        val variance = profile.noiseModel.parametersAt(x, y).varianceForSignalDn(varianceSignal)
        val active = profile.activeArea
        val insideActive = x >= active.left && y >= active.top &&
            x < active.left + active.width && y < active.top + active.height
        return CalibratedSample(
            rawDn = rawDn,
            blackLevelDn = black,
            whiteLevelDn = white,
            signalDn = signal,
            normalizedSignal = normalized,
            varianceDn2 = variance,
            lowCensored = rawDn.toDouble() <= black,
            highCensored = rawDn.toDouble() >= white,
            insideActiveArea = insideActive,
            cfaSiteIndex = profile.siteIndexAt(x, y),
            cfaColor = profile.siteColorAt(x, y),
        )
    }
}

class CalibratedMeasurementFrameSet internal constructor(
    val context: RawCaptureContext,
    val profile: M5CalibrationProfile,
    val reservation: CalibrationReservation,
    frames: List<CalibratedMeasurementFrame>,
) {
    val frames: List<CalibratedMeasurementFrame> = Collections.unmodifiableList(ArrayList(frames.sortedBy { it.ordinal }))

    init {
        require(this.frames.isNotEmpty()) { "Calibrated measurement set cannot be empty" }
        require(this.frames.indices.all { this.frames[it].ordinal == it }) {
            "Calibrated measurement frame ordinals must remain contiguous"
        }
        require(context.canonicalLensFingerprint == profile.canonicalLensFingerprint)
        require(context.cameraProfileFingerprint == profile.cameraProfileFingerprint)
        require(context.rawSize == profile.rawSize)
    }
}

object ReferenceCalibrationEngine {
    fun calibrate(
        frameSet: ImmutableRawFrameSet,
        profile: M5CalibrationProfile,
        reservation: CalibrationReservation,
    ): CalibratedMeasurementFrameSet {
        require(frameSet.context.canonicalLensFingerprint == profile.canonicalLensFingerprint) {
            "Calibration profile canonical lens does not match the immutable FrameSet"
        }
        require(frameSet.context.cameraProfileFingerprint == profile.cameraProfileFingerprint) {
            "Calibration profile fingerprint does not match the immutable FrameSet"
        }
        require(frameSet.context.rawSize == profile.rawSize) {
            "Calibration profile RAW dimensions do not match the immutable FrameSet"
        }
        require(reservation.inputCanonicalBytes == frameSet.totalCanonicalBytes &&
            reservation.calibratedCopyBytes == frameSet.totalCanonicalBytes
        ) { "Calibration reservation is not bound to this FrameSet extent" }

        val calibrated = ArrayList<CalibratedMeasurementFrame>(frameSet.frames.size)
        frameSet.frames.forEach { source: ImmutableRawBurstFrame ->
            calibrated += CalibratedMeasurementFrame(
                ordinal = source.ordinal,
                metadata = source.metadata,
                sourceCanonicalSha256 = source.canonicalSha256,
                profile = profile,
                canonicalRaster = source.copyCanonicalRaster(),
            )
        }
        return CalibratedMeasurementFrameSet(frameSet.context, profile, reservation, calibrated)
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

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
