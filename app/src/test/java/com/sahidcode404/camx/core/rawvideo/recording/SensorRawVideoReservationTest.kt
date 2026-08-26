package com.sahidcode404.camx.core.rawvideo.recording

import com.sahidcode404.camx.core.camera.model.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRawVideoReservationTest {
    @Test
    fun reservationProvesBothFrameQueuesAndDetachedPairingBeforeCapture() {
        val reservation = SensorRawVideoReservation.forRawSensor(
            rawSize = IntSize(4000, 3000),
            ingestQueueFrames = 2,
            maxResidentBytes = 192L * 1024L * 1024L,
        )

        assertEquals(24_000_000L, reservation.canonicalBytesPerFrame)
        assertEquals(48_000_000L, reservation.reservedCanonicalQueueBytes)
        assertEquals(2, reservation.spoolQueueFrames)
        assertEquals(48_000_000L, reservation.reservedSpoolQueueBytes)
        assertEquals(3, reservation.pairingPendingImageFrames)
        assertEquals(72_000_000L, reservation.pairingPendingImageBytes)
        assertEquals(96_000_000L, reservation.reservedDetachedPairingBytes)
        assertEquals(196_194_304L, reservation.requiredResidentBytes)
        assertEquals(4, reservation.imageReaderMaxImages)
        assertTrue(reservation.requiredResidentBytes <= reservation.maxResidentBytes)
    }

    @Test
    fun reservationFailsClosedWhenDetachedPendingAndInflightFramesCannotFit() {
        val failure = runCatching {
            SensorRawVideoReservation.forRawSensor(
                rawSize = IntSize(4000, 3000),
                ingestQueueFrames = 2,
                maxResidentBytes = 128L * 1024L * 1024L,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("pending and one in-flight detached RAW frame"))
    }

    @Test
    fun reservationFailsClosedWhenFrameQueuesCannotFitBudget() {
        val failure = runCatching {
            SensorRawVideoReservation.forRawSensor(
                rawSize = IntSize(8000, 6000),
                ingestQueueFrames = 4,
                maxResidentBytes = 128L * 1024L * 1024L,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("ingest and spool queues"))
    }
}
