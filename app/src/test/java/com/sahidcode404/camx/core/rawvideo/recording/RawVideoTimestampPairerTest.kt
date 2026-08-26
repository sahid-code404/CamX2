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
    fun resultMetadataSkewCanExceedDetachedImageEntryBudgetWithoutCrossCharging() {
        val pairer = RawVideoTimestampPairer<TestImage, String>(
            maximumPendingEntries = 2,
            maximumPendingResultEntries = 16,
        )

        repeat(12) { index ->
            val timestamp = 1_000L + index
            assertEquals(null, pairer.offerResult(timestamp, index.toLong(), "r$index"))
        }

        assertEquals(0, pairer.pendingImageCount())
        assertEquals(12, pairer.pendingResultCount())
        repeat(12) { index ->
            val timestamp = 1_000L + index
            val pair = checkNotNull(pairer.offerImage(timestamp, TestImage()))
            assertEquals(index.toLong(), pair.frameNumber)
            assertEquals("r$index", pair.result)
            pair.close()
        }
        assertEquals(0, pairer.pendingCount())
    }

    @Test
    fun resultMetadataOverflowFailsClosedInsteadOfEvictingOldResults() {
        val pairer = RawVideoTimestampPairer<TestImage, String>(
            maximumPendingEntries = 2,
            maximumPendingResultEntries = 3,
        )
        pairer.offerResult(1L, 1L, "r1")
        pairer.offerResult(2L, 2L, "r2")
        pairer.offerResult(3L, 3L, "r3")

        val failure = runCatching { pairer.offerResult(4L, 4L, "r4") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("bounded result-metadata entry budget"))
        assertTrue(failure?.message.orEmpty().contains("images=0 results=4"))
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
        assertTrue(failure?.message.orEmpty().contains("bounded detached-image entry budget"))
        assertEquals(3, closes.get())
    }

    @Test
    fun detachedByteOverflowClosesAllOwnedEvidenceAndFailsInsteadOfDropping() {
        val closes = AtomicInteger(0)
        val pairer = RawVideoTimestampPairer<TestImage, String>(
            maximumPendingEntries = 4,
            maximumPendingImageBytes = 10L,
        )

        pairer.offerImage(1L, TestImage(closes, retainedByteCount = 6L))
        assertEquals(6L, pairer.pendingImageByteCount())
        val failure = runCatching {
            pairer.offerImage(2L, TestImage(closes, retainedByteCount = 6L))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(2, closes.get())
        assertEquals(0L, pairer.pendingImageByteCount())
    }

    @Test
    fun detachedFrameByteExtentCannotChangeInsidePairingEpoch() {
        val closes = AtomicInteger(0)
        val pairer = RawVideoTimestampPairer<TestImage, String>(
            maximumPendingEntries = 4,
            maximumPendingImageBytes = 100L,
        )

        pairer.offerImage(1L, TestImage(closes, retainedByteCount = 6L))
        val failure = runCatching {
            pairer.offerImage(2L, TestImage(closes, retainedByteCount = 7L))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(2, closes.get())
        assertEquals(0L, pairer.pendingImageByteCount())
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

    @Test
    fun offerAfterCloseClosesRejectedSourceLease() {
        val closes = AtomicInteger(0)
        val pairer = RawVideoTimestampPairer<TestImage, String>(maximumPendingEntries = 4)
        pairer.close()

        val failure = runCatching { pairer.offerImage(20L, TestImage(closes)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, closes.get())
    }

    @Test
    fun invalidTimestampClosesRejectedSourceLease() {
        val closes = AtomicInteger(0)
        val pairer = RawVideoTimestampPairer<TestImage, String>(maximumPendingEntries = 4)

        val failure = runCatching { pairer.offerImage(0L, TestImage(closes)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, closes.get())
        pairer.close()
    }

    private class TestImage(
        private val closes: AtomicInteger = AtomicInteger(),
        override val retainedByteCount: Long = 0L,
    ) : RetainedByteEvidence {
        override fun close() { closes.incrementAndGet() }
    }
}
