package com.sahidcode404.camx.core.rawvideo.recording

import com.sahidcode404.camx.core.camera.model.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRawVideoReservationTest {
    @Test
    fun reservationProvesBoundedCanonicalQueueBeforeCapture() {
        val reservation = SensorRawVideoReservation.forRawSensor(
            rawSize = IntSize(4000, 3000),
            ingestQueueFrames = 2,
            maxResidentBytes = 64L * 1024L * 1024L,
        )
        assertEquals(24_000_000L, reservation.canonicalBytesPerFrame)
        assertEquals(48_000_000L, reservation.reservedCanonicalQueueBytes)
        assertEquals(4, reservation.imageReaderMaxImages)
        assertTrue(reservation.requiredResidentBytes <= reservation.maxResidentBytes)
    }

    @Test
    fun reservationFailsClosedWhenQueueCannotFitBudget() {
        val failure = runCatching {
            SensorRawVideoReservation.forRawSensor(
                rawSize = IntSize(8000, 6000),
                ingestQueueFrames = 4,
                maxResidentBytes = 128L * 1024L * 1024L,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
