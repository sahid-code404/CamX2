package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.RawContractLimits
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections

object M4BurstLimits {
    const val MIN_FRAMES = 2
    const val MAX_FRAMES = 16
    const val RAW_SENSOR_BYTES_PER_PIXEL = 2L
    const val MAX_SOURCE_BYTES_PER_FRAME = 512L * 1024L * 1024L
    const val MAX_TOTAL_CANONICAL_BYTES = 512L * 1024L * 1024L
    const val MAX_RESIDENT_BYTES = 1024L * 1024L * 1024L
    const val METADATA_RESERVATION_BYTES_PER_FRAME = 4L * 1024L
    const val FIXED_SAFETY_MARGIN_BYTES = 1024L * 1024L
    const val DEFAULT_TIMEOUT_MILLIS = 10_000L
}

/**
 * Pre-capture upper bound for one RAW_SENSOR burst. The source extent is deliberately explicit:
 * Camera2 does not advertise Image.Plane row stride before delivery, so an exact-profile caller must
 * provide a previously certified upper bound. A frame whose delivered extent exceeds it is rejected.
 */
class RawBurstReservation private constructor(
    val frameCount: Int,
    val rawSize: IntSize,
    val canonicalBytesPerFrame: Long,
    val maxSourceBytesPerFrame: Long,
    val sourceReservationBytes: Long,
    val canonicalCopyReservationBytes: Long,
    val metadataReservationBytes: Long,
    val safetyMarginBytes: Long,
    val requiredResidentBytes: Long,
    val maxResidentBytes: Long,
    val timeoutMillis: Long,
) {
    companion object {
        fun forRawSensor(
            frameCount: Int,
            rawSize: IntSize,
            maxSourceBytesPerFrame: Long,
            maxResidentBytes: Long,
            timeoutMillis: Long = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        ): RawBurstReservation {
            require(frameCount in M4BurstLimits.MIN_FRAMES..M4BurstLimits.MAX_FRAMES) {
                "M4 burst frame count must be within the bounded implementation range"
            }
            require(timeoutMillis in RawContractLimits.MINIMUM_TIMEOUT_MILLIS..
                RawContractLimits.MAXIMUM_TIMEOUT_MILLIS
            ) { "M4 burst timeout is outside the frozen RAW timeout contract" }

            val pixels = checkedMultiply(rawSize.width.toLong(), rawSize.height.toLong(), "RAW pixel count overflow")
            val canonicalPerFrame = checkedMultiply(
                pixels,
                M4BurstLimits.RAW_SENSOR_BYTES_PER_PIXEL,
                "RAW canonical byte count overflow",
            )
            require(canonicalPerFrame > 0L && canonicalPerFrame <= M4BurstLimits.MAX_SOURCE_BYTES_PER_FRAME) {
                "RAW canonical frame extent exceeds the M4 bound"
            }
            require(maxSourceBytesPerFrame in canonicalPerFrame..M4BurstLimits.MAX_SOURCE_BYTES_PER_FRAME) {
                "Certified source extent must cover the canonical RAW frame and remain bounded"
            }

            val sourceBytes = checkedMultiply(
                frameCount.toLong(),
                maxSourceBytesPerFrame,
                "Burst source reservation overflow",
            )
            val canonicalBytes = checkedMultiply(
                frameCount.toLong(),
                canonicalPerFrame,
                "Burst canonical reservation overflow",
            )
            require(canonicalBytes <= M4BurstLimits.MAX_TOTAL_CANONICAL_BYTES) {
                "Burst canonical bytes exceed the M4 bound"
            }
            val metadataBytes = checkedMultiply(
                frameCount.toLong(),
                M4BurstLimits.METADATA_RESERVATION_BYTES_PER_FRAME,
                "Burst metadata reservation overflow",
            )
            val resident = checkedAdd(
                checkedAdd(sourceBytes, canonicalBytes, "Burst resident reservation overflow"),
                checkedAdd(
                    metadataBytes,
                    M4BurstLimits.FIXED_SAFETY_MARGIN_BYTES,
                    "Burst resident reservation overflow",
                ),
                "Burst resident reservation overflow",
            )
            require(maxResidentBytes in resident..M4BurstLimits.MAX_RESIDENT_BYTES) {
                "Burst resident budget does not cover the proven worst-case reservation"
            }
            return RawBurstReservation(
                frameCount = frameCount,
                rawSize = rawSize,
                canonicalBytesPerFrame = canonicalPerFrame,
                maxSourceBytesPerFrame = maxSourceBytesPerFrame,
                sourceReservationBytes = sourceBytes,
                canonicalCopyReservationBytes = canonicalBytes,
                metadataReservationBytes = metadataBytes,
                safetyMarginBytes = M4BurstLimits.FIXED_SAFETY_MARGIN_BYTES,
                requiredResidentBytes = resident,
                maxResidentBytes = maxResidentBytes,
                timeoutMillis = timeoutMillis,
            )
        }
    }
}

data class RawBurstFrameMetadata(
    val sensorTimestampNs: Long,
    val frameNumber: Long,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val frameDurationNs: Long?,
) {
    init {
        require(sensorTimestampNs > 0L) { "Burst sensor timestamp must be positive" }
        require(frameNumber >= 0L) { "Burst frame number cannot be negative" }
        require(exposureTimeNs == null || exposureTimeNs > 0L) { "Exposure time must be positive when present" }
        require(sensitivityIso == null || sensitivityIso > 0) { "Sensitivity must be positive when present" }
        require(frameDurationNs == null || frameDurationNs > 0L) { "Frame duration must be positive when present" }
    }
}

/** Immutable canonical RAW_SENSOR evidence. Padding is excluded and source layout remains explicit. */
class ImmutableRawBurstFrame internal constructor(
    val ordinal: Int,
    val rawSize: IntSize,
    val sourceRowStrideBytes: Int,
    val sourcePixelStrideBytes: Int,
    val sourceRequiredBytes: Long,
    val canonicalRowBytes: Int,
    val metadata: RawBurstFrameMetadata,
    canonicalRaster: ByteArray,
) {
    private val canonicalRaster = canonicalRaster.copyOf()
    val canonicalByteCount: Long = this.canonicalRaster.size.toLong()
    val canonicalSha256: String = sha256(this.canonicalRaster)

    init {
        require(ordinal >= 0) { "Burst frame ordinal cannot be negative" }
        require(sourceRowStrideBytes >= canonicalRowBytes && canonicalRowBytes > 0) {
            "RAW source row stride cannot be smaller than its canonical row"
        }
        require(sourcePixelStrideBytes == M4BurstLimits.RAW_SENSOR_BYTES_PER_PIXEL.toInt()) {
            "M4 RAW_SENSOR evidence requires the public two-byte pixel layout"
        }
        require(sourceRequiredBytes >= canonicalByteCount) {
            "RAW source extent cannot be smaller than canonical evidence"
        }
        require(canonicalByteCount > 0L && canonicalByteCount <= M4BurstLimits.MAX_SOURCE_BYTES_PER_FRAME) {
            "Canonical RAW frame is empty or exceeds the M4 bound"
        }
        RawBurstDiagnosticsHub.frameCopiedAndAccepted()
    }

    fun copyCanonicalRaster(): ByteArray = canonicalRaster.copyOf()

    /** Read-only RAW16 access for downstream sensor-domain processing without duplicating the frame. */
    internal fun raw16LittleEndianAt(x: Int, y: Int): Int {
        require(x in 0 until rawSize.width && y in 0 until rawSize.height) {
            "RAW16 sample coordinate lies outside the immutable burst frame"
        }
        val pixelIndex = y.toLong() * rawSize.width.toLong() + x.toLong()
        val byteIndexLong = checkedMultiply(
            pixelIndex,
            M4BurstLimits.RAW_SENSOR_BYTES_PER_PIXEL,
            "RAW16 sample offset overflow",
        )
        require(byteIndexLong <= Int.MAX_VALUE.toLong() - 1L) {
            "RAW16 sample offset exceeds JVM array indexing"
        }
        val byteIndex = byteIndexLong.toInt()
        return (canonicalRaster[byteIndex].toInt() and 0xff) or
            ((canonicalRaster[byteIndex + 1].toInt() and 0xff) shl 8)
    }

    /** Internal hot-path read. Bounds are proven by CP3 active/mapped-coordinate checks. */
    internal fun raw16LittleEndianAtUnchecked(x: Int, y: Int): Int {
        val pixelIndex = y * rawSize.width + x
        val byteIndex = pixelIndex shl 1
        return (canonicalRaster[byteIndex].toInt() and 0xff) or
            ((canonicalRaster[byteIndex + 1].toInt() and 0xff) shl 8)
    }

    internal fun writeCanonicalRaster(output: OutputStream) {
        output.write(canonicalRaster)
    }
}

/**
 * Fully copied, immutable membership for one capture token / canonical lens / profile. Android Image
 * ownership is released before this value crosses the camera transaction boundary.
 */
class ImmutableRawFrameSet internal constructor(
    val context: RawCaptureContext,
    val reservation: RawBurstReservation,
    frames: List<ImmutableRawBurstFrame>,
) {
    val frames: List<ImmutableRawBurstFrame> = Collections.unmodifiableList(ArrayList(frames.sortedBy { it.ordinal }))
    val totalCanonicalBytes: Long

    init {
        require(context.rawSize == reservation.rawSize) { "FrameSet RAW size diverged from its reservation" }
        require(context.timeoutMillis == reservation.timeoutMillis) { "FrameSet timeout diverged from its reservation" }
        require(this.frames.size == reservation.frameCount) { "FrameSet is incomplete" }
        require(this.frames.indices.all { this.frames[it].ordinal == it }) { "FrameSet ordinals must be contiguous" }
        require(this.frames.map { it.metadata.sensorTimestampNs }.distinct().size == this.frames.size) {
            "FrameSet sensor timestamps must be unique"
        }
        var total = 0L
        this.frames.forEach { frame ->
            require(frame.rawSize == reservation.rawSize) { "FrameSet contains a mismatched RAW size" }
            require(frame.canonicalByteCount == reservation.canonicalBytesPerFrame) {
                "FrameSet canonical extent diverged from the reservation"
            }
            require(frame.sourceRequiredBytes <= reservation.maxSourceBytesPerFrame) {
                "Frame source extent exceeds the admitted per-frame bound"
            }
            total = checkedAdd(total, frame.canonicalByteCount, "FrameSet canonical byte total overflow")
        }
        require(total == reservation.canonicalCopyReservationBytes) {
            "FrameSet canonical byte total diverged from the reservation"
        }
        totalCanonicalBytes = total
    }
}

sealed interface RawBurstCaptureOutcome {
    data class Captured(val frameSet: ImmutableRawFrameSet) : RawBurstCaptureOutcome
    data class Failed(val failure: CameraFailure) : RawBurstCaptureOutcome
    data object Cancelled : RawBurstCaptureOutcome
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
