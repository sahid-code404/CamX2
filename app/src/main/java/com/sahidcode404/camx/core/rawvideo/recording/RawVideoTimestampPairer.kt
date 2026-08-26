package com.sahidcode404.camx.core.rawvideo.recording

import android.media.Image
import java.util.LinkedHashMap

/**
 * One exact timestamp pair.
 *
 * I describes the source lease type accepted by RawVideoTimestampPairer. Production M10 accepts
 * Camera2 Image leases, but those leases are converted to DetachedRawSensorImage before they can
 * become pending timestamp state. The pair therefore owns AutoCloseable detached evidence rather
 * than promising that a framework Image remains alive after offerImage returns.
 */
class PairedRawVideoSample<I : AutoCloseable, R> internal constructor(
    val timestampNs: Long,
    val frameNumber: Long,
    private var ownedEvidence: AutoCloseable?,
    val result: R,
) : AutoCloseable {
    internal fun takeDetachedRawSensorImage(): DetachedRawSensorImage {
        val value = checkNotNull(ownedEvidence) { "M10 paired RAW evidence ownership already moved" }
        ownedEvidence = null
        if (value is DetachedRawSensorImage) return value
        runCatching { value.close() }
        error("M10 production RAW-video pair did not contain detached RAW_SENSOR evidence")
    }

    override fun close() {
        val value = ownedEvidence
        ownedEvidence = null
        value?.close()
    }
}

/**
 * Exact SENSOR_TIMESTAMP streaming pairer. Overflow is fatal; it never evicts evidence.
 *
 * Android ImageReader Images are special-cased at this synchronous ownership boundary: the
 * meaningful RAW raster is copied to DetachedRawSensorImage and the native Image is closed before
 * the timestamp-skew map can retain it. Unmatched callback ordering therefore cannot consume
 * ImageReader.maxImages slots.
 */
class RawVideoTimestampPairer<I : AutoCloseable, R>(
    private val maximumPendingEntries: Int = M10RawVideoLimits.DEFAULT_PAIR_ENTRIES,
) : AutoCloseable {
    private val images = LinkedHashMap<Long, AutoCloseable>()
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

        val ownedEvidence = detachImageReaderLease(image)
        val result = results.remove(timestampNs)
        if (result != null) {
            return PairedRawVideoSample(timestampNs, result.frameNumber, ownedEvidence, result.value)
        }
        images[timestampNs] = ownedEvidence
        enforceBound()
        return null
    }

    @Synchronized
    fun offerResult(timestampNs: Long, frameNumber: Long, result: R): PairedRawVideoSample<I, R>? {
        require(timestampNs > 0L) { "M10 result timestamp must be positive" }
        require(frameNumber >= 0L) { "M10 Camera2 frame number cannot be negative" }
        check(!closed) { "M10 timestamp pairer is closed" }
        require(!results.containsKey(timestampNs)) { "M10 duplicate capture-result timestamp $timestampNs" }
        val ownedEvidence = images.remove(timestampNs)
        if (ownedEvidence != null) {
            return PairedRawVideoSample(timestampNs, frameNumber, ownedEvidence, result)
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

    private fun detachImageReaderLease(image: I): AutoCloseable {
        if (image !is Image) return image
        return DetachedRawSensorImage.copyAndClose(image)
    }

    private fun enforceBound() {
        if (images.size + results.size <= maximumPendingEntries) return
        close()
        throw IllegalStateException("M10 timestamp pairing exceeded the bounded pending-entry budget")
    }

    private data class ResultRecord<R>(val frameNumber: Long, val value: R)
}
