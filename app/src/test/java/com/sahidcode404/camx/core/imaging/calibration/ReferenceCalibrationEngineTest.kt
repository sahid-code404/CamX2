package com.sahidcode404.camx.core.imaging.calibration

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCalibrationEngineTest {
    @Test
    fun referenceCalibrationDecodesLittleEndianAndPreservesCensoring() {
        val frameSet = M5CalibrationTestFixtures.frameSet()
        val profile = M5CalibrationTestFixtures.profile()
        val reservation = CalibrationReservation.forFrameSet(frameSet, 4L * 1024L * 1024L)
        val calibrated = ReferenceCalibrationEngine.calibrate(frameSet, profile, reservation)

        val low = calibrated.frames[0].sampleAt(0, 0)
        assertEquals(64, low.rawDn)
        assertEquals(0.0, low.signalDn, 0.0)
        assertTrue(low.lowCensored)
        assertFalse(low.highCensored)
        assertTrue(low.insideActiveArea)
        assertEquals(CfaSiteColor.RED, low.cfaColor)

        val high = calibrated.frames[0].sampleAt(1, 1)
        assertEquals(1023, high.rawDn)
        assertEquals(1.0, high.normalizedSignal, 0.0)
        assertTrue(high.highCensored)
        assertEquals(CfaSiteColor.BLUE, high.cfaColor)
    }

    @Test
    fun profileIdentityMismatchFailsClosedBeforeCopy() {
        val frameSet = M5CalibrationTestFixtures.frameSet()
        val wrong = M5CalibrationTestFixtures.profile(
            cameraProfileFingerprint = CameraProfileFingerprint("different-profile"),
        )
        val reservation = CalibrationReservation.forFrameSet(frameSet, 4L * 1024L * 1024L)
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceCalibrationEngine.calibrate(frameSet, wrong, reservation)
        }
    }

    @Test
    fun reservationRejectsInsufficientResidentBudget() {
        val frameSet = M5CalibrationTestFixtures.frameSet()
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationReservation.forFrameSet(frameSet, 1024L * 1024L)
        }
    }

    @Test
    fun calibratedFrameSetFreezesInputRaster() {
        val frameSet = M5CalibrationTestFixtures.frameSet()
        val profile = M5CalibrationTestFixtures.profile()
        val reservation = CalibrationReservation.forFrameSet(frameSet, 4L * 1024L * 1024L)
        val calibrated = ReferenceCalibrationEngine.calibrate(frameSet, profile, reservation)
        val copy = calibrated.frames[0].copyCanonicalRaster()
        copy[0] = 0
        assertEquals(64, calibrated.frames[0].sampleAt(0, 0).rawDn)
        assertEquals(
            calibrated.frames[0].sourceCanonicalSha256,
            calibrated.frames[0].copiedCanonicalSha256,
        )
    }
}
