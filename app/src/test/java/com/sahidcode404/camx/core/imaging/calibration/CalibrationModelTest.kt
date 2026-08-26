package com.sahidcode404.camx.core.imaging.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationModelTest {
    @Test
    fun profileRequiresExactBlackWhiteOrdering() {
        assertThrows(IllegalArgumentException::class.java) {
            M5CalibrationTestFixtures.profile(
                whiteLevels = CfaDoubleQuad(64.0, 1023.0, 1023.0, 1023.0),
            )
        }
    }

    @Test
    fun confidenceDimensionsRemainSeparate() {
        val confidence = CalibrationConfidenceVector(
            blackLevel = 0.91,
            whiteLevel = 0.92,
            cfaAndActiveArea = 0.93,
            shotNoise = 0.71,
            readNoise = 0.82,
            fixedPatternNoise = 0.64,
            colorCalibration = null,
        )
        assertEquals(0.71, confidence.shotNoise, 0.0)
        assertEquals(0.82, confidence.readNoise, 0.0)
        assertEquals(0.64, confidence.fixedPatternNoise, 0.0)
    }

    @Test
    fun colorMatrixRequiresIlluminantAndNonSingularMatrix() {
        assertThrows(IllegalArgumentException::class.java) {
            Matrix3x3(
                1.0, 2.0, 3.0,
                2.0, 4.0, 6.0,
                3.0, 6.0, 9.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceIlluminant(0, "invalid")
        }
    }

    @Test
    fun noiseVarianceIncludesShotReadAndFpnTerms() {
        val noise = NoiseParameters(0.5, 4.0, 0.01)
        assertEquals(55.0, noise.varianceForSignalDn(100.0), 1e-12)
    }

    @Test
    fun profileDigestIsDeterministicAndSensitive() {
        val first = M5CalibrationTestFixtures.profile()
        val second = M5CalibrationTestFixtures.profile()
        assertEquals(first.digestSha256(), second.digestSha256())
        val changed = M5CalibrationTestFixtures.profile(
            whiteLevels = CfaDoubleQuad(1022.0, 1023.0, 1023.0, 1023.0),
        )
        assertNotEquals(first.digestSha256(), changed.digestSha256())
    }
}
