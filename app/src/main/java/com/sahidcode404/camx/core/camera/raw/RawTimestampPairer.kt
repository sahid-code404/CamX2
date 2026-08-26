package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.RawPair
import com.sahidcode404.camx.core.camera.model.RawContractLimits
import java.util.LinkedHashMap

/** Exact, callback-order-independent SENSOR_TIMESTAMP pairer with deterministic image closure. */
class RawTimestampPairer<I : AutoCloseable, R : Any>(
    private val maximumEntries: Int = 4,
    val timeoutMillis: Long = RawContractLimits.DEFAULT_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val images = LinkedHashMap<Long, I>()
    private val results = LinkedHashMap<Long, R>()
    private var closed = false

    init {
        require(maximumEntries in 1..MAXIMUM_ALLOWED_ENTRIES) {
            "Timestamp capacity must be between 1 and $MAXIMUM_ALLOWED_ENTRIES"
        }
        require(timeoutMillis in RawContractLimits.MINIMUM_TIMEOUT_MILLIS..
            RawContractLimits.MAXIMUM_TIMEOUT_MILLIS
        ) {
            "Pairing timeout must be between ${RawContractLimits.MINIMUM_TIMEOUT_MILLIS} and " +
                "${RawContractLimits.MAXIMUM_TIMEOUT_MILLIS} milliseconds"
        }
    }

    @Synchronized
    fun offerImage(timestampNs: Long, image: I): RawPair<I, R>? {
        if (closed) {
            image.closeQuietly()
            return null
        }
        if (timestampNs <= 0L) {
            image.closeQuietly()
            return null
        }
        val result = results.remove(timestampNs)
        if (result != null) return RawPair(timestampNs, image, result)

        images.put(timestampNs, image)?.closeQuietly()
        trimImages()
        trimResults()
        return null
    }

    @Synchronized
    fun offerResult(timestampNs: Long, result: R): RawPair<I, R>? {
        if (closed) return null
        if (timestampNs <= 0L) return null
        val image = images.remove(timestampNs)
        if (image != null) return RawPair(timestampNs, image, result)

        results[timestampNs] = result
        trimImages()
        trimResults()
        return null
    }

    @Synchronized
    fun pendingCounts(): Pair<Int, Int> = images.size to results.size

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        images.values.forEach { image -> image.closeQuietly() }
        images.clear()
        results.clear()
    }

    private fun trimImages() {
        while (images.size > maximumEntries) {
            val oldest = images.entries.first()
            images.remove(oldest.key)?.closeQuietly()
        }
    }

    private fun trimResults() {
        while (results.size > maximumEntries) {
            results.remove(results.entries.first().key)
        }
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val MAXIMUM_ALLOWED_ENTRIES = 32
    }
}
