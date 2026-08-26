package com.sahidcode404.camx.core.rawvideo.recording

import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterHasher
import com.sahidcode404.camx.core.rawvideo.container.CxrbReferenceWriter
import com.sahidcode404.camx.core.rawvideo.container.CxrbSegmentEpoch
import com.sahidcode404.camx.core.rawvideo.container.CxrbWriterConfig
import com.sahidcode404.camx.core.rawvideo.container.FrameOrdinal
import com.sahidcode404.camx.core.rawvideo.container.PackedNoneFrame
import com.sahidcode404.camx.core.rawvideo.container.RawVideoGap
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Bounded in-process M10 spool into the provisional M2A CXRB + mandatory PACKED_NONE baseline. */
class CxrbSensorRawVideoSpool(
    val outputFile: File,
    writerConfig: CxrbWriterConfig,
    queueFrames: Int,
    private val segmentRecordLimit: Int = M10RawVideoLimits.DEFAULT_SEGMENT_RECORDS,
) : AutoCloseable {
    // One queue slot owns at most one full RAW payload. A gap is tiny metadata attached to that
    // frame rather than a second queue record, so queueFrames is also the exact full-frame bound.
    private val queue = ArrayBlockingQueue<FrameBatch>(queueFrames)
    private val writer = CxrbReferenceWriter(outputFile, writerConfig)
    private val accepting = AtomicBoolean(true)
    private val finishing = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>(null)
    private val highWater = AtomicInteger(0)
    private val worker = Thread(::runWorker, "camx-raw-video-spool").apply {
        isDaemon = true
        start()
    }

    init {
        require(queueFrames in 1..M10RawVideoLimits.MAX_INGEST_QUEUE_FRAMES)
        require(segmentRecordLimit in 1..M10RawVideoLimits.MAX_SEGMENT_RECORDS)
    }

    @Synchronized
    fun tryAppend(gapBefore: RawVideoGap?, frame: PackedNoneFrame): Boolean {
        if (!accepting.get() || failure.get() != null) return false
        if (!queue.offer(FrameBatch(gapBefore, frame))) return false
        updateHighWater()
        return true
    }

    fun finish(ingestQueueHighWaterFrames: Int): SensorRawVideoSummary {
        accepting.set(false)
        finishing.set(true)
        worker.interrupt()
        worker.join(M10RawVideoLimits.WORKER_JOIN_TIMEOUT_MILLIS)
        if (worker.isAlive) {
            abort(deleteOutput = false)
            throw IllegalStateException("M10 spool worker did not terminate within the bounded join interval")
        }
        failure.get()?.let { throw IllegalStateException("M10 CXRB spool failed", it) }
        val result = completedSummary.get()
            ?: throw IllegalStateException("M10 spool terminated without a durable summary")
        return result.copy(ingestQueueHighWaterFrames = ingestQueueHighWaterFrames)
    }

    fun abort(deleteOutput: Boolean) {
        if (!aborted.compareAndSet(false, true)) return
        accepting.set(false)
        finishing.set(true)
        queue.clear()
        worker.interrupt()
        runCatching { writer.close() }
        runCatching { worker.join(M10RawVideoLimits.WORKER_JOIN_TIMEOUT_MILLIS) }
        if (deleteOutput) runCatching { outputFile.delete() }
    }

    override fun close() = abort(deleteOutput = false)

    private val completedSummary = AtomicReference<SensorRawVideoSummary?>(null)

    private fun runWorker() {
        var segmentOrdinal = 0uL
        var representationEpoch = 0uL
        val codecEpoch = 0uL
        var segmentOpen = false
        var recordsInSegment = 0
        var descriptorSha: String? = null
        var frameCount = 0L
        var gapCount = 0L
        var firstTimestamp: Long? = null
        var lastTimestamp: Long? = null

        fun commitIfOpen() {
            if (!segmentOpen || recordsInSegment == 0) return
            writer.commitSegment()
            segmentOpen = false
            recordsInSegment = 0
            segmentOrdinal += 1uL
        }

        fun begin(firstOrdinal: FrameOrdinal) {
            writer.beginSegment(
                CxrbSegmentEpoch(
                    segmentOrdinal = segmentOrdinal,
                    representationEpoch = representationEpoch,
                    codecEpoch = codecEpoch,
                    firstOrdinal = firstOrdinal,
                ),
            )
            segmentOpen = true
            recordsInSegment = 0
        }

        fun appendGap(value: RawVideoGap) {
            if (!segmentOpen) begin(value.firstMissingOrdinal)
            if (recordsInSegment >= segmentRecordLimit) {
                commitIfOpen()
                begin(value.firstMissingOrdinal)
            }
            writer.appendGap(value)
            recordsInSegment += 1
            gapCount += value.missingCount.toLongChecked()
        }

        fun appendFrame(value: PackedNoneFrame) {
            val nextDescriptorSha = CanonicalRasterHasher.descriptorSha256(value.identity.representation)
            if (!segmentOpen) {
                descriptorSha = nextDescriptorSha
                begin(value.frameOrdinal)
            } else if (descriptorSha != null && descriptorSha != nextDescriptorSha) {
                commitIfOpen()
                representationEpoch += 1uL
                descriptorSha = nextDescriptorSha
                begin(value.frameOrdinal)
            } else if (descriptorSha == null) {
                descriptorSha = nextDescriptorSha
            }
            if (recordsInSegment >= segmentRecordLimit) {
                commitIfOpen()
                begin(value.frameOrdinal)
            }
            writer.appendFrame(value)
            recordsInSegment += 1
            frameCount += 1L
            val ts = value.identity.timebase.imageTimestampNs
            if (firstTimestamp == null) firstTimestamp = ts
            lastTimestamp = ts
        }

        try {
            while (!aborted.get()) {
                val batch = try {
                    queue.poll(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (batch == null) {
                    if (finishing.get() && queue.isEmpty()) break
                    continue
                }
                batch.gapBefore?.let(::appendGap)
                appendFrame(batch.frame)
                if (recordsInSegment >= segmentRecordLimit) commitIfOpen()
            }
            if (!aborted.get()) {
                commitIfOpen()
                writer.close()
                completedSummary.set(
                    SensorRawVideoSummary(
                        outputPath = outputFile.absolutePath,
                        frameCount = frameCount,
                        gapCount = gapCount,
                        durableBytes = outputFile.length(),
                        ingestQueueHighWaterFrames = 0,
                        spoolQueueHighWaterRecords = highWater.get(),
                        firstSensorTimestampNs = firstTimestamp,
                        lastSensorTimestampNs = lastTimestamp,
                    ),
                )
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
            accepting.set(false)
            runCatching { writer.close() }
        }
    }

    private fun updateHighWater() {
        val records = queue.sumOf { batch -> if (batch.gapBefore == null) 1 else 2 }
        while (true) {
            val previous = highWater.get()
            if (records <= previous || highWater.compareAndSet(previous, records)) return
        }
    }

    private data class FrameBatch(
        val gapBefore: RawVideoGap?,
        val frame: PackedNoneFrame,
    )
}

private fun ULong.toLongChecked(): Long {
    require(this <= Long.MAX_VALUE.toULong()) { "M10 gap count exceeds signed summary range" }
    return toLong()
}
