package com.sahidcode404.camx.core.imaging.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceAlignmentEngineTest {
    @Test
    fun recoversKnownEvenTranslationDeterministically() {
        val measurements = M6AlignmentTestFixtures.translatedMeasurements(dx = 2, dy = 0)
        val request = M6AlignmentTestFixtures.request()
        val first = M6AlignmentTestFixtures.align(measurements, request)
        val second = M6AlignmentTestFixtures.align(measurements, request)
        assertEquals(2, first.frames[1].translation.dxPixels)
        assertEquals(0, first.frames[1].translation.dyPixels)
        assertEquals(FrameAlignmentDecision.ACCEPTED, first.frames[1].decision)
        assertEquals(first.frames, second.frames)
        assertEquals(AlignmentFallbackKind.FULL_SET, first.fallbackKind)
    }

    @Test
    fun ambiguousTextureFallsBackToReference() {
        val measurements = M6AlignmentTestFixtures.constantMeasurements()
        val request = M6AlignmentTestFixtures.request(minAcceptedFrames = 2)
        val result = M6AlignmentTestFixtures.align(measurements, request)
        assertEquals(FrameAlignmentDecision.EXCLUDED_AMBIGUOUS, result.frames[1].decision)
        assertEquals(listOf(0), result.reconstructionOrdinals)
        assertEquals(AlignmentFallbackKind.REFERENCE_ONLY, result.fallbackKind)
    }

    @Test
    fun mixedQualitySetFallsBackToSmallerSubset() {
        val measurements = M6AlignmentTestFixtures.mixedMeasurements()
        val request = M6AlignmentTestFixtures.request(minAcceptedFrames = 2)
        val result = M6AlignmentTestFixtures.align(measurements, request)
        assertEquals(FrameAlignmentDecision.ACCEPTED, result.frames[1].decision)
        assertTrue(result.frames[2].decision != FrameAlignmentDecision.ACCEPTED)
        assertEquals(listOf(0, 1), result.reconstructionOrdinals)
        assertEquals(AlignmentFallbackKind.SMALLER_SUBSET, result.fallbackKind)
    }

    @Test
    fun perPixelSupportMarksOcclusionWithoutInventingEvidence() {
        val measurements = M6AlignmentTestFixtures.translatedMeasurements(dx = 2, dy = 0, occludeReferenceX = 4)
        val request = M6AlignmentTestFixtures.request(minInlier = 0.70)
        val result = M6AlignmentTestFixtures.align(measurements, request)
        val support = result.supportAt(frameOrdinal = 1, x = 4, y = 4)
        assertTrue(support.visible)
        assertFalse(support.censored)
        assertFalse(support.inlier)
        assertTrue(support.occluded)
        assertTrue(checkNotNull(support.normalizedResidualSigma) > request.inlierSigmaThreshold)
    }

    @Test
    fun rollingShutterEvidenceSeparatesTopAndBottomMotion() {
        val measurements = M6AlignmentTestFixtures.rollingMeasurements()
        val request = M6AlignmentTestFixtures.request(minInlier = 0.30, maxResidual = 1_000_000.0)
        val result = M6AlignmentTestFixtures.align(measurements, request)
        val rolling = result.frames[1].rollingShutter
        assertEquals(2, checkNotNull(rolling.topBandTranslation).dxPixels)
        assertEquals(-2, checkNotNull(rolling.bottomBandTranslation).dxPixels)
        assertEquals(4.0, checkNotNull(rolling.bandDisagreementPixels), 0.0)
    }
}
