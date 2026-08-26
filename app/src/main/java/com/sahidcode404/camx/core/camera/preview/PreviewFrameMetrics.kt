package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest

/**
 * Low-frequency immutable projection. movingAverageFps is based only on the currently retained
 * interval window; p50/p95 use integer nearest-rank semantics: ceil(sampleCount * percentile).
 */
data class PreviewFrameMetricsSnapshot(
    val requested: PreviewFpsRequest,
    val resolved: CameraFpsCapability?,
    val sampleCount: Int,
    val movingAverageFps: Double?,
    val p50FrameIntervalNs: Long?,
    val p95FrameIntervalNs: Long?,
)

/** Owns exactly one fixed LongArray ring. recordSensorTimestamp() performs only primitive O(1) work. */
class PreviewFrameMetrics(
    private val requested: PreviewFpsRequest,
    private val resolved: CameraFpsCapability?,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val intervals: LongArray
    private var size = 0
    private var writeIndex = 0
    private var previousTimestampNs = 0L

    init {
        require(capacity in MIN_CAPACITY..MAX_CAPACITY) {
            "Metrics capacity must be between $MIN_CAPACITY and $MAX_CAPACITY"
        }
        intervals = LongArray(capacity)
    }

    @Synchronized
    fun recordSensorTimestamp(timestampNs: Long) {
        if (timestampNs <= 0L) return
        val previous = previousTimestampNs
        if (previous == 0L) {
            previousTimestampNs = timestampNs
            return
        }
        if (timestampNs <= previous) return
        val intervalNs = timestampNs - previous
        previousTimestampNs = timestampNs
        intervals[writeIndex] = intervalNs
        writeIndex += 1
        if (writeIndex == intervals.size) writeIndex = 0
        if (size < intervals.size) size += 1
    }

    @Synchronized
    fun snapshot(): PreviewFrameMetricsSnapshot {
        if (size == 0) return PreviewFrameMetricsSnapshot(requested, resolved, 0, null, null, null)

        val ordered = LongArray(size)
        var sumIntervalsNs = 0.0
        for (index in 0 until size) {
            val intervalNs = intervals[index]
            ordered[index] = intervalNs
            sumIntervalsNs += intervalNs.toDouble()
        }
        ordered.sort()
        val meanIntervalNs = sumIntervalsNs / size.toDouble()
        val averageFps = NANOSECONDS_PER_SECOND.toDouble() / meanIntervalNs
        return PreviewFrameMetricsSnapshot(
            requested = requested,
            resolved = resolved,
            sampleCount = size,
            movingAverageFps = averageFps.takeIf { it.isFinite() && it > 0.0 },
            p50FrameIntervalNs = nearestRank(ordered, 50),
            p95FrameIntervalNs = nearestRank(ordered, 95),
        )
    }

    private fun nearestRank(sorted: LongArray, percentile: Int): Long {
        val rank = ((sorted.size.toLong() * percentile.toLong() + 99L) / 100L)
            .coerceIn(1L, sorted.size.toLong())
            .toInt()
        return sorted[rank - 1]
    }

    private companion object {
        const val MIN_CAPACITY = 2
        const val MAX_CAPACITY = 4_096
        const val DEFAULT_CAPACITY = 120
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
    }
}
