package com.sahidcode404.camx.core.rawvideo.recording

import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.camera.acquisition.M1AcquisitionLimits
import com.sahidcode404.camx.core.camera.model.IntSize
import java.io.File
import kotlin.math.min

object M10RawVideoLimits {
    const val RAW_SENSOR_BYTES_PER_PIXEL = 2L
    const val DEFAULT_INGEST_QUEUE_FRAMES = 2
    const val MAX_INGEST_QUEUE_FRAMES = 8
    const val DEFAULT_PAIR_ENTRIES = 8
    const val MAX_PAIR_ENTRIES = 32
    const val DEFAULT_SEGMENT_RECORDS = 128
    const val MAX_SEGMENT_RECORDS = 1024
    const val DEFAULT_MAX_RESIDENT_BYTES = 256L * 1024L * 1024L
    const val MAX_RESIDENT_BYTES = 768L * 1024L * 1024L
    const val FIXED_SAFETY_MARGIN_BYTES = 4L * 1024L * 1024L
    const val INGEST_BACKPRESSURE_TIMEOUT_MILLIS = 250L
    const val SESSION_TIMEOUT_MILLIS = 5_000L
    const val STOP_DRAIN_TIMEOUT_MILLIS = 5_000L
    const val WORKER_JOIN_TIMEOUT_MILLIS = 10_000L
    const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
}

data class SensorRawVideoProfile(
    val rawSize: IntSize,
    val activeArea: IntRect,
    val cfaPattern: CfaPattern,
    val effectiveBits: Int,
) {
    init {
        require(effectiveBits in 1..16) { "M10 effective RAW precision must fit the public RAW_SENSOR container" }
        require(activeArea.left.toLong() + activeArea.width.toLong() <= rawSize.width.toLong())
        require(activeArea.top.toLong() + activeArea.height.toLong() <= rawSize.height.toLong())
    }
}

internal data class DetachedPairingBudget(
    val pendingImageFrames: Int,
    val pendingImageBytes: Long,
    val reservedDetachedBytes: Long,
    val requiredResidentBytes: Long,
)

internal fun defaultDetachedPairingBudget(canonicalBytesPerFrame: Long): DetachedPairingBudget =
    detachedPairingBudget(
        canonicalBytesPerFrame = canonicalBytesPerFrame,
        queueFrames = M10RawVideoLimits.DEFAULT_INGEST_QUEUE_FRAMES,
        maxResidentBytes = M10RawVideoLimits.DEFAULT_MAX_RESIDENT_BYTES,
    )

private fun detachedPairingBudget(
    canonicalBytesPerFrame: Long,
    queueFrames: Int,
    maxResidentBytes: Long,
): DetachedPairingBudget {
    val oneQueueBytes = checkedMultiply(
        canonicalBytesPerFrame,
        queueFrames.toLong(),
        "M10 queue reservation overflow",
    )
    val bothQueueBytes = checkedMultiply(oneQueueBytes, 2L, "M10 ingest/spool queue reservation overflow")
    val fixedBytes = checkedAdd(
        bothQueueBytes,
        M10RawVideoLimits.FIXED_SAFETY_MARGIN_BYTES,
        "M10 resident reservation overflow",
    )
    require(fixedBytes < maxResidentBytes) {
        "M10 resident budget cannot cover the bounded canonical ingest and spool queues"
    }

    // One detached frame may be in-flight between pairing and ingest while older image-before-result
    // evidence remains in the timestamp-skew map. Reserve both states explicitly in addition to both
    // full-frame asynchronous queues.
    val detachedBudgetBytes = maxResidentBytes - fixedBytes
    val detachedFrameCapacity = detachedBudgetBytes / canonicalBytesPerFrame
    require(detachedFrameCapacity >= 2L) {
        "M10 resident budget cannot cover one pending and one in-flight detached RAW frame"
    }
    val pendingImageFrames = min(
        M10RawVideoLimits.DEFAULT_PAIR_ENTRIES.toLong(),
        detachedFrameCapacity - 1L,
    ).toInt()
    require(pendingImageFrames >= 1)
    val pendingImageBytes = checkedMultiply(
        canonicalBytesPerFrame,
        pendingImageFrames.toLong(),
        "M10 timestamp-pairing reservation overflow",
    )
    val reservedDetachedBytes = checkedMultiply(
        canonicalBytesPerFrame,
        pendingImageFrames.toLong() + 1L,
        "M10 detached pairing reservation overflow",
    )
    val requiredResidentBytes = checkedAdd(
        fixedBytes,
        reservedDetachedBytes,
        "M10 resident reservation overflow",
    )
    check(requiredResidentBytes <= maxResidentBytes) {
        "M10 detached pairing reservation exceeded the admitted resident budget"
    }
    return DetachedPairingBudget(
        pendingImageFrames = pendingImageFrames,
        pendingImageBytes = pendingImageBytes,
        reservedDetachedBytes = reservedDetachedBytes,
        requiredResidentBytes = requiredResidentBytes,
    )
}

class SensorRawVideoReservation private constructor(
    val rawSize: IntSize,
    val canonicalBytesPerFrame: Long,
    val ingestQueueFrames: Int,
    val spoolQueueFrames: Int,
    val imageReaderMaxImages: Int,
    val pairingPendingImageFrames: Int,
    val pairingPendingImageBytes: Long,
    val reservedCanonicalQueueBytes: Long,
    val reservedSpoolQueueBytes: Long,
    val reservedDetachedPairingBytes: Long,
    val requiredResidentBytes: Long,
    val maxResidentBytes: Long,
) {
    companion object {
        fun forRawSensor(
            rawSize: IntSize,
            ingestQueueFrames: Int = M10RawVideoLimits.DEFAULT_INGEST_QUEUE_FRAMES,
            maxResidentBytes: Long = M10RawVideoLimits.DEFAULT_MAX_RESIDENT_BYTES,
        ): SensorRawVideoReservation {
            require(ingestQueueFrames in 1..M10RawVideoLimits.MAX_INGEST_QUEUE_FRAMES)
            require(maxResidentBytes in 1..M10RawVideoLimits.MAX_RESIDENT_BYTES)
            val pixels = checkedMultiply(rawSize.width.toLong(), rawSize.height.toLong(), "M10 RAW pixel extent overflow")
            val frameBytes = checkedMultiply(
                pixels,
                M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL,
                "M10 canonical RAW frame extent overflow",
            )
            require(frameBytes in 1..M1AcquisitionLimits.MAX_CANONICAL_RASTER_BYTES) {
                "M10 canonical RAW frame exceeds the M1 sensor-raster bound"
            }
            require(frameBytes <= Int.MAX_VALUE.toLong()) {
                "M10 reference ingest requires one canonical frame addressable by a JVM byte array"
            }
            val queueBytes = checkedMultiply(frameBytes, ingestQueueFrames.toLong(), "M10 queue reservation overflow")
            val pairingBudget = detachedPairingBudget(frameBytes, ingestQueueFrames, maxResidentBytes)
            val imageReaderMaxImages = checkedAddInt(ingestQueueFrames, 2, "M10 ImageReader bound overflow")
            return SensorRawVideoReservation(
                rawSize = rawSize,
                canonicalBytesPerFrame = frameBytes,
                ingestQueueFrames = ingestQueueFrames,
                spoolQueueFrames = ingestQueueFrames,
                imageReaderMaxImages = imageReaderMaxImages,
                pairingPendingImageFrames = pairingBudget.pendingImageFrames,
                pairingPendingImageBytes = pairingBudget.pendingImageBytes,
                reservedCanonicalQueueBytes = queueBytes,
                reservedSpoolQueueBytes = queueBytes,
                reservedDetachedPairingBytes = pairingBudget.reservedDetachedBytes,
                requiredResidentBytes = pairingBudget.requiredResidentBytes,
                maxResidentBytes = maxResidentBytes,
            )
        }
    }
}

sealed interface SensorRawVideoStatus {
    data object Idle : SensorRawVideoStatus
    data class Starting(val outputPath: String) : SensorRawVideoStatus
    data class Recording(val outputPath: String, val startedElapsedRealtimeNs: Long) : SensorRawVideoStatus
    data class Stopping(val outputPath: String) : SensorRawVideoStatus
    data class Completed(val summary: SensorRawVideoSummary) : SensorRawVideoStatus
    data class Failed(val outputPath: String?, val reason: String) : SensorRawVideoStatus
}

data class SensorRawVideoSummary(
    val outputPath: String,
    val frameCount: Long,
    val gapCount: Long,
    val durableBytes: Long,
    val ingestQueueHighWaterFrames: Int,
    val spoolQueueHighWaterRecords: Int,
    val firstSensorTimestampNs: Long?,
    val lastSensorTimestampNs: Long?,
) {
    init {
        require(outputPath.isNotBlank())
        require(frameCount >= 0L && gapCount >= 0L && durableBytes >= 0L)
        require(ingestQueueHighWaterFrames >= 0 && spoolQueueHighWaterRecords >= 0)
        require((firstSensorTimestampNs == null) == (lastSensorTimestampNs == null))
        require(firstSensorTimestampNs == null || firstSensorTimestampNs > 0L)
        require(lastSensorTimestampNs == null || lastSensorTimestampNs >= firstSensorTimestampNs!!)
    }
}

sealed interface SensorRawVideoStartOutcome {
    data class Started(val outputPath: String) : SensorRawVideoStartOutcome
    data class Failed(val reason: String) : SensorRawVideoStartOutcome
    data object Cancelled : SensorRawVideoStartOutcome
}

sealed interface SensorRawVideoStopOutcome {
    data class Completed(val summary: SensorRawVideoSummary) : SensorRawVideoStopOutcome
    data class Failed(val outputPath: String?, val reason: String) : SensorRawVideoStopOutcome
    data object NotRecording : SensorRawVideoStopOutcome
    data object Cancelled : SensorRawVideoStopOutcome
}

fun interface SensorRawVideoSpoolFactory {
    fun create(maxFrameBytes: Long, queueFrames: Int): CxrbSensorRawVideoSpool
}

internal fun File.requireEmptyRegularTarget() {
    require(!exists() || length() == 0L) { "M10 raw-video output must be a new empty file" }
    parentFile?.let { require(it.isDirectory || it.mkdirs()) { "M10 raw-video directory is unavailable" } }
}

private fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
    Math.multiplyExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException(message, error)
}

private fun checkedAdd(left: Long, right: Long, message: String): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException(message, error)
}

private fun checkedAddInt(left: Int, right: Int, message: String): Int = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalArgumentException(message, error)
}
