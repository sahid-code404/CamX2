package com.sahidcode404.camx.core.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBurstTimestampPairerTest {
    @Test
    fun outOfOrderCallbacksProduceExactOrdinalOrder() {
        val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 4)
        val images = List(4) { CountingCloseable() }

        // CP1 acceptance case: images arrive 3,1,0,2 and results arrive 0,2,3,1.
        pairer.offerImage(400L, images[3])
        pairer.offerImage(200L, images[1])
        pairer.offerImage(100L, images[0])
        pairer.offerImage(300L, images[2])
        pairer.offerResult(100L, 0, "r0")
        pairer.offerResult(300L, 2, "r2")
        pairer.offerResult(400L, 3, "r3")
        val completed = pairer.offerResult(200L, 1, "r1")

        assertNotNull(completed)
        assertEquals(listOf(0, 1, 2, 3), completed!!.pairs.map { it.ordinal })
        assertEquals(listOf(100L, 200L, 300L, 400L), completed.pairs.map { it.timestampNs })
        completed.close()
        images.forEach { assertEquals(1, it.closeCount) }
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
    fun duplicateResultTimestampFailsAndIsCounted() {
        val diagnostics = RawBurstDiagnosticsHub.begin()
        try {
            val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 2)
            pairer.offerResult(100L, 0, "r0")

            assertThrows(RawBurstPairingException::class.java) {
                pairer.offerResult(100L, 1, "duplicate timestamp")
            }

            val snapshot = RawBurstDiagnosticsHub.finish(diagnostics)
            assertEquals(2, snapshot.resultsReceived)
            assertEquals(1, snapshot.duplicateResultTimestamps)
            assertEquals(0, snapshot.duplicateOrdinals)
            assertEquals(1, snapshot.unmatchedResults)
        } catch (failure: Throwable) {
            runCatching { RawBurstDiagnosticsHub.finish(diagnostics) }
            throw failure
        }
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
    fun resultAndImageWithoutPartnersRemainTruthfullyUnmatchedOnClose() {
        val diagnostics = RawBurstDiagnosticsHub.begin()
        try {
            val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 2)
            val unmatchedImage = CountingCloseable()
            pairer.offerImage(100L, unmatchedImage)
            pairer.offerResult(200L, 1, "unmatched-result")
            pairer.close()

            val snapshot = RawBurstDiagnosticsHub.finish(diagnostics)
            assertEquals(1, snapshot.imagesReceived)
            assertEquals(1, snapshot.resultsReceived)
            assertEquals(0, snapshot.exactPairsCreated)
            assertEquals(1, snapshot.unmatchedImages)
            assertEquals(1, snapshot.unmatchedResults)
            assertEquals(1, unmatchedImage.closeCount)
        } catch (failure: Throwable) {
            runCatching { RawBurstDiagnosticsHub.finish(diagnostics) }
            throw failure
        }
    }

    @Test
    fun duplicateOrdinalIsCountedWithoutCreatingFalseMembership() {
        val diagnostics = RawBurstDiagnosticsHub.begin()
        try {
            val pairer = RawBurstTimestampPairer<CountingCloseable, String>(expectedFrames = 2)
            pairer.offerResult(100L, 0, "r0")

            assertThrows(RawBurstPairingException::class.java) {
                pairer.offerResult(200L, 0, "duplicate-ordinal")
            }

            val snapshot = RawBurstDiagnosticsHub.finish(diagnostics)
            assertEquals(1, snapshot.duplicateOrdinals)
            assertEquals(0, snapshot.exactPairsCreated)
            assertEquals(1, snapshot.unmatchedResults)
        } catch (failure: Throwable) {
            runCatching { RawBurstDiagnosticsHub.finish(diagnostics) }
            throw failure
        }
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
