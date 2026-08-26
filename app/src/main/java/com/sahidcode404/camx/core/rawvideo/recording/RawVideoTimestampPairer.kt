package com.sahidcode404.camx.core.rawvideo.recording

import android.hardware.camera2.CaptureResult
import android.media.Image
import java.util.LinkedHashMap

class PairedRawVideoSample<I : AutoCloseable, R> internal constructor(
    val timestampNs: Long,
    val frameNumber: Long,
    private var image: I?,
    val result: R,
) : AutoCloseable {
    fun takeImage(): I {
        val value = checkNotNull(image) { "M10 paired image ownership already moved" }
        image = null
        return value
    }

    override fun close() {
        val value = image
        image = null
        value?.close()
    }
}

/**
 * Heap-only handoff for the asynchronous ingest queue. The ImageReader lease has already been
 * detached by RawVideoTimestampPairer before this object is created.
 */
internal class DetachedRawVideoPair private constructor(
    val timestampNs: Long,
    val frameNumber: Long,
    private var image: DetachedRawSensorImage?,
    val result: CaptureResult,
) : AutoCloseable {
    fun takeImage(): DetachedRawSensorImage {
        val value = checkNotNull(image) { "M10 detached RAW image ownership already moved" }
        image = null
        return value
    }

    override fun close() {
        val value = image
        image = null
        value?.close()
    }

    companion object {
        fun from(pair: PairedRawVideoSample<Image, CaptureResult>): DetachedRawVideoPair {
            val image = pair.takeImage()
            return try {
                val detached = image as? DetachedRawSensorImage
                    ?: throw IllegalArgumentException("M10 paired RAW image escaped without detachment")
                DetachedRawVideoPair(
                    timestampNs = pair.timestampNs,
                    frameNumber = pair.frameNumber,
                    image = detached,
                    result = pair.result,
                )
            } catch (error: Throwable) {
                runCatching { image.close() }
                throw error
            } finally {
                pair.close()
            }
        }
    }
}

/**
 * Exact SENSOR_TIMESTAMP streaming pairer. Overflow is fatal; it never evicts evidence.
 *
 * Android ImageReader Images are special-cased at this ownership boundary: the complete RAW plane
 * is detached to heap-backed evidence and the native Image is closed before it can enter the
 * timestamp-skew map. This means unmatched image/result ordering cannot consume ImageReader.maxImages.
 */
class RawVideoTimestampPairer<I : AutoCloseable, R>(
    private val maximumPendingEntries: Int = M10RawVideoLimits.DEFAULT_PAIR_ENTRIES,
) : AutoCloseable {
    private val images = LinkedHashMap<Long, I>()
    private val results = LinkedHashMap<Long, ResultRecord<R>>()
    private var closed = false

    init {
        require(maximumPendingEntries in 1..M10RawVideoLimits.MAX_PAIR_ENTRIES)
    }

    @Synchronized
    fun offerImage(timestampNs: Long, image: I): PairedRawVideoSample<I, R>? {
        require(timestampNs > 0L) { "M10 image timestamp must be positive" }
        check(!closed) { "M10 timestamp pairer is closed" }
        if (images.containsKey(timestampNs)) {
            image.close()
            throw IllegalArgumentException("M10 duplicate image timestamp $timestampNs")
        }

        val ownedImage = detachImageReaderLease(image)
        val result = results.remove(timestampNs)
        if (result != null) {
            return PairedRawVideoSample(timestampNs, result.frameNumber, ownedImage, result.value)
        }
        images[timestampNs] = ownedImage
        enforceBound()
        return null
    }

    @Synchronized
    fun offerResult(timestampNs: Long, frameNumber: Long, result: R): PairedRawVideoSample<I, R>? {
        require(timestampNs > 0L) { "M10 result timestamp must be positive" }
        require(frameNumber >= 0L) { "M10 Camera2 frame number cannot be negative" }
        check(!closed) { "M10 timestamp pairer is closed" }
        require(!results.containsKey(timestampNs)) { "M10 duplicate capture-result timestamp $timestampNs" }
        val image = images.remove(timestampNs)
        if (image != null) {
            return PairedRawVideoSample(timestampNs, frameNumber, image, result)
        }
        results[timestampNs] = ResultRecord(frameNumber, result)
        enforceBound()
        return null
    }

    @Synchronized
    fun pendingImageCount(): Int = images.size

    @Synchronized
    fun pendingResultCount(): Int = results.size

    @Synchronized
    fun pendingCount(): Int = images.size + results.size

    @Synchronized
    fun unmatchedResultFrameNumbers(): List<Long> = results.values.map(ResultRecord<R>::frameNumber).sorted()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        images.values.forEach { image -> runCatching { image.close() } }
        images.clear()
        results.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun detachImageReaderLease(image: I): I {
        if (image !is Image || image is DetachedRawSensorImage) return image
        return DetachedRawSensorImage.copyAndClose(image) as I
    }

    private fun enforceBound() {
        if (images.size + results.size <= maximumPendingEntries) return
        close()
        throw IllegalStateException("M10 timestamp pairing exceeded the bounded pending-entry budget")
    }

    private data class ResultRecord<R>(val frameNumber: Long, val value: R)
}
