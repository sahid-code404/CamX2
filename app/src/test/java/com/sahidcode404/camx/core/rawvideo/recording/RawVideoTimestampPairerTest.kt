package com.sahidcode404.camx.core.rawvideo.recording

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoTimestampPairerTest {
    @Test
    fun resultThenImagePairsExactlyWithoutEviction() {
        val pairer = RawVideoTimestampPairer<TestImage, String>(maximumPendingEntries = 4)
        assertEquals(null, pairer.offerResult(100L, 7L, "r7"))
        val pair = checkNotNull(pairer.offerImage(100L, TestImage()))
        assertEquals(100L, pair.timestampNs)
        assertEquals(7L, pair.frameNumber)
        assertEquals("r7", pair.result)
        pair.close()
        assertEquals(0, pairer.pendingCount())
    }

    @Test
    fun pendingOverflowClosesOwnedImagesAndFailsInsteadOfDropping() {
        val closes = AtomicInteger(0)
        val pairer = RawVideoTimestampPairer<TestImage, String>(maximumPendingEntries = 2)
        pairer.offerImage(1L, TestImage(closes))
        pairer.offerImage(2L, TestImage(closes))
        val failure = runCatching { pairer.offerImage(3L, TestImage(closes)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(3, closes.get())
    }

    @Test
    fun duplicateImageTimestampClosesRejectedOwnership() {
        val closes = AtomicInteger(0)
        val pairer = RawVideoTimestampPairer<TestImage, String>(maximumPendingEntries = 4)
        pairer.offerImage(10L, TestImage(closes))
        val failure = runCatching { pairer.offerImage(10L, TestImage(closes)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, closes.get())
        pairer.close()
        assertEquals(2, closes.get())
    }

    private class TestImage(private val closes: AtomicInteger = AtomicInteger()) : AutoCloseable {
        override fun close() { closes.incrementAndGet() }
    }
}
