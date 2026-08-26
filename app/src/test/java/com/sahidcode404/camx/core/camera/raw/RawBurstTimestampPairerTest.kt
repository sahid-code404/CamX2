package com.sahidcode404.camx.core.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBurstTimestampPairerTest {
    @Test
    fun outOfOrderCallbacksProduceExactOrdinalOrder() {
        val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 3)
        val firstImage = CountingCloseable()
        val secondImage = CountingCloseable()
        val thirdImage = CountingCloseable()

        pairer.offerResult(300L, 2, "r2")
        pairer.offerImage(100L, firstImage)
        pairer.offerResult(100L, 0, "r0")
        pairer.offerImage(300L, thirdImage)
        pairer.offerResult(200L, 1, "r1")
        val completed = pairer.offerImage(200L, secondImage)

        assertNotNull(completed)
        assertEquals(listOf(0, 1, 2), completed!!.pairs.map { it.ordinal })
        assertEquals(listOf(100L, 200L, 300L), completed.pairs.map { it.timestampNs })
        completed.close()
        assertEquals(1, firstImage.closeCount)
        assertEquals(1, secondImage.closeCount)
        assertEquals(1, thirdImage.closeCount)
    }

    @Test
    fun duplicateResultOrdinalFailsClosedAndReleasesPendingImages() {
        val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 2)
        val pending = CountingCloseable()
        pairer.offerImage(100L, pending)
        pairer.offerResult(200L, 0, "r0")

        val failure = assertThrows(RawBurstPairingException::class.java) {
            pairer.offerResult(300L, 0, "duplicate")
        }

        assertTrue(failure.message!!.contains("ordinal"))
        assertEquals(1, pending.closeCount)
    }

    @Test
    fun duplicateImageTimestampClosesBothStillOwnedImagesExactlyOnce() {
        val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 2)
        val first = CountingCloseable()
        val duplicate = CountingCloseable()
        pairer.offerImage(100L, first)

        assertThrows(RawBurstPairingException::class.java) {
            pairer.offerImage(100L, duplicate)
        }

        assertEquals(1, first.closeCount)
        assertEquals(1, duplicate.closeCount)
    }

    @Test
    fun closingIncompleteBurstNeverReturnsPartialFrameSet() {
        val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 3)
        val image = CountingCloseable()
        assertEquals(null, pairer.offerImage(100L, image))
        assertEquals(null, pairer.offerResult(100L, 0, "r0"))
        assertEquals(Triple(0, 0, 1), pairer.pendingCounts())

        pairer.close()

        assertEquals(1, image.closeCount)
        assertEquals(Triple(0, 0, 0), pairer.pendingCounts())
    }

    @Test
    fun imageOwnershipMovesAtMostOnce() {
        val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 2)
        pairer.offerImage(100L, CountingCloseable())
        pairer.offerResult(100L, 0, "r0")
        pairer.offerImage(200L, CountingCloseable())
        val set = pairer.offerResult(200L, 1, "r1")!!
        val pair = set.pairs.first()

        val image = pair.takeImage()
        assertThrows(IllegalStateException::class.java) { pair.takeImage() }
        pair.close()
        assertEquals(0, image.closeCount)
        image.close()
        assertEquals(1, image.closeCount)
        set.close()
    }

    private class CountingCloseable : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
