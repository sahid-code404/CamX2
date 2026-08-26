package com.sahidcode404.camx.core.rawvideo.recording

import android.media.Image
import java.util.LinkedHashMap

internal interface RetainedByteEvidence : AutoCloseable {
    val retainedByteCount: Long
}

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
 * ImageReader.maxImages slots. Detached image evidence is also byte-bounded by the same default
 * M10 resident-admission formula used by SensorRawVideoReservation unless a stricter explicit cap
 * is supplied for a test or future custom admission path.
 */
class RawVideoTimestampPairer<I : AutoCloseable, R>(
    private val maximumPendingEntries: Int = M10RawVideoLimits.DEFAULT_PAIR_ENTRIES,
    maximumPendingImageBytes: Long? = null,
) : AutoCloseable {
    private val images = LinkedHashMap<Long, PendingImage>()
    private val results = LinkedHashMap<Long, ResultRecord<R>>()
    private var pendingImageBytes = 0L
    private var expectedRetainedFrameBytes: Long? = null
    private var resolvedMaximumPendingImageBytes: Long? = maximumPendingImageBytes
    private var closed = false

    init {
        require(maximumPendingEntries in 1..M10RawVideoLimits.MAX_PAIR_ENTRIES)
        require(maximumPendingImageBytes == null || maximumPendingImageBytes in 1..M10RawVideoLimits.MAX_RESIDENT_BYTES)
    }

    @Synchronized
    fun offerImage(timestampNs: Long, image: I): PairedRawVideoSample<I, R>? {
        if (timestampNs <= 0L) {
            runCatching { image.close() }
            throw IllegalArgumentException("M10 image timestamp must be positive")
        }
        if (closed) {
            runCatching { image.close() }
            throw IllegalStateException("M10 timestamp pairer is closed")
        }
        if (images.containsKey(timestampNs)) {
            image.close()
            throw IllegalArgumentException("M10 duplicate image timestamp $timestampNs")
        }

        val ownedEvidence = detachImageReaderLease(image)
        val result = results.remove(timestampNs)
        if (result != null) {
            return PairedRawVideoSample(timestampNs, result.frameNumber, ownedEvidence, result.value)
        }

        val retainedBytes = retainedBytesOf(ownedEvidence)
        val byteLimit = try {
            resolvePendingImageByteLimit(retainedBytes)
        } catch (failure: Throwable) {
            runCatching { ownedEvidence.close() }
            close()
            throw failure
        }
        val nextBytes = try {
            Math.addExact(pendingImageBytes, retainedBytes)
        } catch (overflow: ArithmeticException) {
            runCatching { ownedEvidence.close() }
            close()
            throw IllegalStateException("M10 timestamp-pairing retained-byte accounting overflowed", overflow)
        }
        if (nextBytes > byteLimit) {
            runCatching { ownedEvidence.close() }
            close()
            throw IllegalStateException(
                "M10 detached timestamp pairing exceeded the admitted pending-image byte budget",
            )
        }

        images[timestampNs] = PendingImage(ownedEvidence, retainedBytes)
        pendingImageBytes = nextBytes
        enforceBound()
        return null
    }

    @Synchronized
    fun offerResult(timestampNs: Long, frameNumber: Long, result: R): PairedRawVideoSample<I, R>? {
        require(timestampNs > 0L) { "M10 result timestamp must be positive" }
        require(frameNumber >= 0L) { "M10 Camera2 frame number cannot be negative" }
        check(!closed) { "M10 timestamp pairer is closed" }
        require(!results.containsKey(timestampNs)) { "M10 duplicate capture-result timestamp $timestampNs" }
        val pendingImage = images.remove(timestampNs)
        if (pendingImage != null) {
            pendingImageBytes = Math.subtractExact(pendingImageBytes, pendingImage.retainedBytes)
            return PairedRawVideoSample(timestampNs, frameNumber, pendingImage.evidence, result)
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
    fun pendingImageByteCount(): Long = pendingImageBytes

    @Synchronized
    fun unmatchedResultFrameNumbers(): List<Long> = results.values.map(ResultRecord<R>::frameNumber).sorted()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        images.values.forEach { pending -> runCatching { pending.evidence.close() } }
        images.clear()
        results.clear()
        pendingImageBytes = 0L
    }

    private fun detachImageReaderLease(image: I): AutoCloseable {
        if (image !is Image) return image
        return DetachedRawSensorImage.copyAndClose(image)
    }

    private fun retainedBytesOf(evidence: AutoCloseable): Long {
        val retainedBytes = (evidence as? RetainedByteEvidence)?.retainedByteCount ?: 0L
        require(retainedBytes >= 0L) { "M10 retained evidence cannot report negative bytes" }
        return retainedBytes
    }

    private fun resolvePendingImageByteLimit(retainedBytes: Long): Long {
        if (retainedBytes == 0L) {
            return resolvedMaximumPendingImageBytes ?: M10RawVideoLimits.MAX_RESIDENT_BYTES
        }
        val expected = expectedRetainedFrameBytes
        require(expected == null || expected == retainedBytes) {
            "M10 detached RAW frame byte extent changed inside the pairing epoch"
        }
        if (expected == null) expectedRetainedFrameBytes = retainedBytes
        val resolved = resolvedMaximumPendingImageBytes
        if (resolved != null) return resolved
        return defaultDetachedPairingBudget(retainedBytes).pendingImageBytes.also {
            resolvedMaximumPendingImageBytes = it
        }
    }

    private fun enforceBound() {
        if (images.size + results.size <= maximumPendingEntries) return
        close()
        throw IllegalStateException("M10 timestamp pairing exceeded the bounded pending-entry budget")
    }

    private data class PendingImage(val evidence: AutoCloseable, val retainedBytes: Long)
    private data class ResultRecord<R>(val frameNumber: Long, val value: R)
}
