package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.RawContractLimits
import java.util.Collections
import java.util.LinkedHashMap

class RawBurstPairingException(message: String) : IllegalStateException(message)

class RawBurstPair<I : AutoCloseable, R : Any> internal constructor(
    val ordinal: Int,
    val timestampNs: Long,
    image: I,
    val result: R,
) : AutoCloseable {
    private var ownedImage: I? = image

    init {
        require(ordinal >= 0) { "Burst pair ordinal cannot be negative" }
        require(timestampNs > 0L) { "Burst pair timestamp must be positive" }
    }

    @Synchronized
    fun takeImage(): I = checkNotNull(ownedImage) { "Burst image ownership already transferred" }
        .also { ownedImage = null }

    @Synchronized
    override fun close() {
        val image = ownedImage ?: return
        ownedImage = null
        runCatching { image.close() }
    }
}

class RawBurstPairSet<I : AutoCloseable, R : Any> internal constructor(
    pairs: List<RawBurstPair<I, R>>,
) : AutoCloseable {
    val pairs: List<RawBurstPair<I, R>> = Collections.unmodifiableList(ArrayList(pairs.sortedBy { it.ordinal }))

    init {
        require(this.pairs.isNotEmpty()) { "Burst pair set cannot be empty" }
        require(this.pairs.indices.all { this.pairs[it].ordinal == it }) { "Burst pair ordinals must be contiguous" }
        require(this.pairs.map { it.timestampNs }.distinct().size == this.pairs.size) {
            "Burst pair timestamps must be unique"
        }
    }

    override fun close() {
        pairs.forEach { pair -> pair.close() }
    }
}

/**
 * Exact SENSOR_TIMESTAMP pairer for a known finite request set. Unlike the one-shot convenience
 * pairer, M4 never trims evidence: overflow, duplicate timestamps, duplicate ordinals, or malformed
 * callbacks fail the entire set and close every still-owned image.
 */
class RawBurstTimestampPairer<I : AutoCloseable, R : Any>(
    val expectedFrames: Int,
    val timeoutMillis: Long = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
) : AutoCloseable {
    private data class TaggedResult<R : Any>(val ordinal: Int, val result: R)

    private val images = LinkedHashMap<Long, I>()
    private val results = LinkedHashMap<Long, TaggedResult<R>>()
    private val pairedByOrdinal = LinkedHashMap<Int, RawBurstPair<I, R>>()
    private val seenResultOrdinals = linkedSetOf<Int>()
    private val seenImageTimestamps = linkedSetOf<Long>()
    private val seenResultTimestamps = linkedSetOf<Long>()
    private var closed = false
    private var completed = false

    init {
        require(expectedFrames in M4BurstLimits.MIN_FRAMES..M4BurstLimits.MAX_FRAMES) {
            "Burst pairer expected frame count is outside the M4 bound"
        }
        require(timeoutMillis in RawContractLimits.MINIMUM_TIMEOUT_MILLIS..
            RawContractLimits.MAXIMUM_TIMEOUT_MILLIS
        ) { "Burst pairing timeout is outside the frozen RAW timeout contract" }
    }

    @Synchronized
    fun offerImage(timestampNs: Long, image: I): RawBurstPairSet<I, R>? {
        if (closed || completed) {
            image.closeQuietly()
            return null
        }
        RawBurstDiagnosticsHub.imageReceived()
        if (timestampNs <= 0L) failWithImage(image, "Burst image timestamp is not positive")
        if (!seenImageTimestamps.add(timestampNs)) {
            RawBurstDiagnosticsHub.duplicateImageTimestamp()
            failWithImage(image, "Duplicate burst image timestamp")
        }
        if (seenImageTimestamps.size > expectedFrames) failWithImage(image, "Burst delivered more images than reserved")

        val tagged = results.remove(timestampNs)
        if (tagged != null) {
            addPair(tagged.ordinal, timestampNs, image, tagged.result)
        } else {
            images[timestampNs] = image
        }
        return completeIfReady()
    }

    @Synchronized
    fun offerResult(timestampNs: Long, ordinal: Int, result: R): RawBurstPairSet<I, R>? {
        ensureOpen()
        RawBurstDiagnosticsHub.resultReceived()
        if (timestampNs <= 0L) fail("Burst result timestamp is not positive")
        if (ordinal !in 0 until expectedFrames) fail("Burst result ordinal is outside the reserved range")
        if (!seenResultTimestamps.add(timestampNs)) {
            RawBurstDiagnosticsHub.duplicateResultTimestamp()
            fail("Duplicate burst result timestamp")
        }
        if (!seenResultOrdinals.add(ordinal)) {
            RawBurstDiagnosticsHub.duplicateOrdinal()
            fail("Duplicate burst result ordinal")
        }
        if (seenResultTimestamps.size > expectedFrames) fail("Burst delivered more results than reserved")

        // CP2 sees only an already-validated request ordinal/timestamp. Observer errors can never
        // affect the fail-closed M4 pairing transaction.
        RawBurstResultObservationHub.observe(timestampNs, ordinal, result)

        val image = images.remove(timestampNs)
        if (image != null) {
            addPair(ordinal, timestampNs, image, result)
        } else {
            results[timestampNs] = TaggedResult(ordinal, result)
        }
        return completeIfReady()
    }

    @Synchronized
    fun pendingCounts(): Triple<Int, Int, Int> = Triple(images.size, results.size, pairedByOrdinal.size)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        RawBurstDiagnosticsHub.pairingClosed(images.size, results.size)
        images.values.forEach { it.closeQuietly() }
        images.clear()
        results.clear()
        pairedByOrdinal.values.forEach { it.close() }
        pairedByOrdinal.clear()
    }

    private fun addPair(ordinal: Int, timestampNs: Long, image: I, result: R) {
        if (pairedByOrdinal.containsKey(ordinal)) failWithImage(image, "Burst ordinal was paired twice")
        pairedByOrdinal[ordinal] = RawBurstPair(ordinal, timestampNs, image, result)
        RawBurstDiagnosticsHub.exactPairCreated()
        if (pairedByOrdinal.size > expectedFrames) fail("Burst paired more frames than reserved")
    }

    private fun completeIfReady(): RawBurstPairSet<I, R>? {
        if (pairedByOrdinal.size != expectedFrames) return null
        if (images.isNotEmpty() || results.isNotEmpty()) fail("Burst completed with unmatched evidence")
        if ((0 until expectedFrames).any { it !in pairedByOrdinal }) fail("Burst completed with an ordinal gap")
        val pairSet = RawBurstPairSet(pairedByOrdinal.values.toList())
        pairedByOrdinal.clear()
        completed = true
        return pairSet
    }

    private fun ensureOpen() {
        if (closed || completed) fail("Burst pairer is no longer accepting callbacks")
    }

    private fun failWithImage(image: I, message: String): Nothing {
        image.closeQuietly()
        fail(message)
    }

    private fun fail(message: String): Nothing {
        close()
        throw RawBurstPairingException(message)
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }
}
