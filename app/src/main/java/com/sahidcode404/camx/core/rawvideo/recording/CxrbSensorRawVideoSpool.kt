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
    private val queueCapacityRecords = Math.addExact(Math.multiplyExact(queueFrames, 2), 2)
    private val queue = ArrayBlockingQueue<Record>(queueCapacityRecords)
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
        val required = if (gapBefore == null) 1 else 2
        if (queue.remainingCapacity() < required) return false
        if (gapBefore != null && !queue.offer(Record.Gap(gapBefore))) return false
        if (!queue.offer(Record.Frame(frame))) return false
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

        try {
            while (!aborted.get()) {
                val record = try {
                    queue.poll(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (record == null) {
                    if (finishing.get() && queue.isEmpty()) break
                    continue
                }
                when (record) {
                    is Record.Gap -> {
                        if (!segmentOpen) begin(record.value.firstMissingOrdinal)
                        if (recordsInSegment >= segmentRecordLimit) {
                            commitIfOpen()
                            begin(record.value.firstMissingOrdinal)
                        }
                        writer.appendGap(record.value)
                        recordsInSegment += 1
                        gapCount += record.value.missingCount.toLongChecked()
                    }
                    is Record.Frame -> {
                        val nextDescriptorSha = CanonicalRasterHasher.descriptorSha256(record.value.identity.representation)
                        if (!segmentOpen) {
                            descriptorSha = nextDescriptorSha
                            begin(record.value.frameOrdinal)
                        } else if (descriptorSha != null && descriptorSha != nextDescriptorSha) {
                            commitIfOpen()
                            representationEpoch += 1uL
                            descriptorSha = nextDescriptorSha
                            begin(record.value.frameOrdinal)
                        } else if (descriptorSha == null) {
                            descriptorSha = nextDescriptorSha
                        }
                        if (recordsInSegment >= segmentRecordLimit) {
                            commitIfOpen()
                            begin(record.value.frameOrdinal)
                        }
                        writer.appendFrame(record.value)
                        recordsInSegment += 1
                        frameCount += 1L
                        val ts = record.value.identity.timebase.imageTimestampNs
                        if (firstTimestamp == null) firstTimestamp = ts
                        lastTimestamp = ts
                    }
                }
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
        val size = queue.size
        while (true) {
            val previous = highWater.get()
            if (size <= previous || highWater.compareAndSet(previous, size)) return
        }
    }

    private sealed interface Record {
        data class Frame(val value: PackedNoneFrame) : Record
        data class Gap(val value: RawVideoGap) : Record
    }
}

private fun ULong.toLongChecked(): Long {
    require(this <= Long.MAX_VALUE.toULong()) { "M10 gap count exceeds signed summary range" }
    return toLong()
}
