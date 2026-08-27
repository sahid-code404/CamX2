package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Cp2CalibrationEvidenceTest {
    @Test
    fun exactDynamicMetadataBindsToEveryImmutableRawFrame() {
        val frameSet = frameSet()
        val bundle = Cp2CalibrationAssembler.assemble(
            frameSet = frameSet,
            staticObservation = staticObservation(),
            dynamicObservations = listOf(dynamic(0, 100L), dynamic(1, 200L)),
        )

        assertTrue(bundle.report.success)
        assertTrue(bundle.report.fusionNoiseReady)
        assertFalse(bundle.report.directM5ProfileReady)
        assertEquals(2, bundle.report.exactDynamicBindings)
        assertEquals(2, bundle.report.noiseProfileFrames)
        assertTrue(bundle.bindings.all { it.exactResultBound })
        assertEquals(64, bundle.report.calibrationFingerprintSha256.length)
    }

    @Test
    fun timestampMismatchCannotBindByOrdinalAlone() {
        val bundle = Cp2CalibrationAssembler.assemble(
            frameSet = frameSet(),
            staticObservation = staticObservation(),
            dynamicObservations = listOf(dynamic(0, 100L), dynamic(1, 201L)),
        )

        assertFalse(bundle.report.success)
        assertEquals(1, bundle.report.exactDynamicBindings)
        assertEquals(listOf(1), bundle.report.unboundOrdinals)
    }

    @Test
    fun missingNoiseRemainsAbsentWithoutBreakingCoreCalibrationBinding() {
        val frameSet = frameSet()
        val noNoise = Cp2DynamicCalibrationObservation(
            ordinal = 1,
            sensorTimestampNs = 200L,
            dynamicBlackLevels = null,
            dynamicWhiteLevel = null,
            noiseProfile = null,
        )
        val bundle = Cp2CalibrationAssembler.assemble(
            frameSet = frameSet,
            staticObservation = staticObservation(),
            dynamicObservations = listOf(dynamic(0, 100L), noNoise),
        )

        assertTrue(bundle.report.success)
        assertFalse(bundle.report.fusionNoiseReady)
        assertEquals(1, bundle.report.noiseProfileFrames)
    }

    @Test
    fun staticCalibrationFromAnotherProfileIsRejected() {
        val other = staticObservation(
            profile = CameraProfileFingerprint("other-profile"),
        )
        val bundle = Cp2CalibrationAssembler.assemble(
            frameSet = frameSet(),
            staticObservation = other,
            dynamicObservations = listOf(dynamic(0, 100L), dynamic(1, 200L)),
        )

        assertFalse(bundle.report.success)
        assertFalse(bundle.report.staticIdentityMatches)
    }

    private fun frameSet(): ImmutableRawFrameSet {
        val size = IntSize(2, 2)
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 2,
            rawSize = size,
            maxSourceBytesPerFrame = 8L,
            maxResidentBytes = 2L * 1024L * 1024L,
        )
        val context = RawCaptureContext(
            captureToken = CaptureToken(9L),
            selectionGeneration = SelectionGeneration(2L),
            sessionGeneration = SessionGeneration(3L),
            canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
            cameraProfileFingerprint = CameraProfileFingerprint("profile"),
            routeId = CameraRouteId("route"),
            displayRotationAtShutter = DisplayRotation.ROTATION_0,
            sensorOrientationDegrees = 90,
            lensFacing = LensFacing.BACK,
            rawSize = size,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        return ImmutableRawFrameSet(
            context = context,
            reservation = reservation,
            frames = listOf(
                frame(0, 100L, byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)),
                frame(1, 200L, byteArrayOf(2, 0, 3, 0, 4, 0, 5, 0)),
            ),
        )
    }

    private fun frame(ordinal: Int, timestamp: Long, bytes: ByteArray) = ImmutableRawBurstFrame(
        ordinal = ordinal,
        rawSize = IntSize(2, 2),
        sourceRowStrideBytes = 4,
        sourcePixelStrideBytes = 2,
        sourceRequiredBytes = 8L,
        canonicalRowBytes = 4,
        metadata = RawBurstFrameMetadata(
            sensorTimestampNs = timestamp,
            frameNumber = ordinal.toLong(),
            exposureTimeNs = 10_000_000L,
            sensitivityIso = 100,
            frameDurationNs = 33_333_333L,
        ),
        canonicalRaster = bytes,
    )

    private fun staticObservation(
        profile: CameraProfileFingerprint = CameraProfileFingerprint("profile"),
    ) = Cp2StaticCalibrationObservation(
        canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
        cameraProfileFingerprint = profile,
        routeId = CameraRouteId("route"),
        rawSize = IntSize(2, 2),
        cfaArrangement = 0,
        activeArray = Cp2RectEvidence(0, 0, 2, 2),
        preCorrectionActiveArray = Cp2RectEvidence(0, 0, 2, 2),
        blackLevels = listOf(64, 64, 64, 64),
        whiteLevel = 1023,
        referenceIlluminant1 = null,
        referenceIlluminant2 = null,
        colorTransform1 = null,
        colorTransform2 = null,
        calibrationTransform1 = null,
        calibrationTransform2 = null,
        forwardMatrix1 = null,
        forwardMatrix2 = null,
    )

    private fun dynamic(ordinal: Int, timestamp: Long) = Cp2DynamicCalibrationObservation(
        ordinal = ordinal,
        sensorTimestampNs = timestamp,
        dynamicBlackLevels = listOf(64.0, 64.0, 64.0, 64.0),
        dynamicWhiteLevel = 1023,
        noiseProfile = List(4) { Cp2NoiseCoefficient(0.001, 2.0) },
    )
}
