package com.sahidcode404.camx.core.imaging.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlignmentReservationTest {
    @Test
    fun reservationBindsExactFrameSetAndRequest() {
        val measurements = M6AlignmentTestFixtures.translatedMeasurements()
        val request = M6AlignmentTestFixtures.request()
        val reservation = AlignmentReservation.forMeasurements(measurements, request)
        assertEquals(measurements.frames.size, reservation.frameCount)
        assertEquals(request, reservation.request)
        assertEquals((request.searchRadiusPixels + 1) * (request.searchRadiusPixels + 1), reservation.candidateCount)
    }

    @Test
    fun unboundedEvaluationRequestFailsBeforeAlignment() {
        val measurements = M6AlignmentTestFixtures.translatedMeasurements()
        val request = M6AlignmentTestFixtures.request(searchRadius = 32)
        assertThrows(IllegalArgumentException::class.java) {
            AlignmentReservation.forMeasurements(measurements, request, maxScoreEvaluations = 10L)
        }
    }

    @Test
    fun oddTranslationSearchRadiusIsRejectedBecauseItChangesCfaPhase() {
        assertThrows(IllegalArgumentException::class.java) {
            M6AlignmentTestFixtures.request(searchRadius = 3)
        }
    }
}
