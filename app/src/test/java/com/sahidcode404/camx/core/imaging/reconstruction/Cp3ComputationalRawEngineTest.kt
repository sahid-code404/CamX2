package com.sahidcode404.camx.core.imaging.reconstruction

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
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationAssembler
import com.sahidcode404.camx.core.camera.raw.Cp2DynamicCalibrationObservation
import com.sahidcode404.camx.core.camera.raw.Cp2NoiseCoefficient
import com.sahidcode404.camx.core.camera.raw.Cp2RectEvidence
import com.sahidcode404.camx.core.camera.raw.Cp2StaticCalibrationObservation
import com.sahidcode404.camx.core.camera.raw.ImmutableRawBurstFrame
import com.sahidcode404.camx.core.camera.raw.ImmutableRawFrameSet
import com.sahidcode404.camx.core.camera.raw.M4BurstLimits
import com.sahidcode404.camx.core.camera.raw.RawBurstFrameMetadata
import com.sahidcode404.camx.core.camera.raw.RawBurstReservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cp3ComputationalRawEngineTest {
    @Test
    fun translatedSameExposureFramesFuseInCfaDomain() {
        val frameSet = frameSet(candidateDx = 2)
        val calibration = calibration(frameSet)

        val outcome = Cp3ComputationalRawEngine.fuse(frameSet, calibration)
        assertTrue(outcome is Cp3FusionOutcome.Fused)
        val fused = outcome as Cp3FusionOutcome.Fused

        assertTrue(fused.report.success)
        assertEquals(2, fused.report.contributingFrames)
        assertEquals(listOf(0, 1), fused.report.includedOrdinals)
        assertEquals(64, fused.report.outputSha256?.length)
        assertEquals(Cp3FixedPatternNoiseMode.UNAVAILABLE_NOT_INVENTED, fused.report.fixedPatternNoiseMode)
        val candidate = fused.report.frameEvidence.single { it.ordinal == 1 }
        assertEquals(Cp3FrameDecision.INCLUDED, candidate.decision)
        assertEquals(2, candidate.dxPixels)
        assertEquals(0, candidate.dyPixels)
        assertTrue(fused.report.multiFramePixelCount > 0L)
        assertEquals(SIZE * SIZE, fused.fused.pixelCount)
    }

    @Test
    fun signalOnlyResidentBudgetAdmitsFourBytesPerPixel() {
        val frameSet = frameSet(candidateDx = 2)
        val calibration = calibration(frameSet)
        val pixels = SIZE.toLong() * SIZE.toLong()
        val exactV2Budget = frameSet.totalCanonicalBytes + pixels * 4L + 1024L * 1024L

        val outcome = Cp3ComputationalRawEngine.fuse(frameSet, calibration, maxResidentBytes = exactV2Budget)

        assertTrue(outcome is Cp3FusionOutcome.Fused)
    }

    @Test
    fun missingCamera2NoiseNeverBecomesInventedFusionNoise() {
        val frameSet = frameSet(candidateDx = 0)
        val calibration = calibration(frameSet, missingNoiseOrdinal = 1)

        val outcome = Cp3ComputationalRawEngine.fuse(frameSet, calibration)
        assertTrue(outcome is Cp3FusionOutcome.Failed)
        val failed = outcome as Cp3FusionOutcome.Failed

        assertFalse(failed.report.success)
        assertEquals(Cp3FixedPatternNoiseMode.UNAVAILABLE_NOT_INVENTED, failed.report.fixedPatternNoiseMode)
        assertTrue(failed.report.failureDetail?.contains("at least two") == true)
        assertEquals(null, failed.report.outputSha256)
    }

    @Test
    fun exposureMismatchCannotBeMergedAsIfFramesWereIdentical() {
        val frameSet = frameSet(candidateDx = 0, secondExposureNs = 20_000_000L)
        val calibration = calibration(frameSet)

        val outcome = Cp3ComputationalRawEngine.fuse(frameSet, calibration)
        assertTrue(outcome is Cp3FusionOutcome.Failed)
        val failed = outcome as Cp3FusionOutcome.Failed

        assertFalse(failed.report.success)
        assertTrue(failed.report.failureDetail?.contains("exact-exposure") == true)
    }

    @Test
    fun fusionOutputDigestIsDeterministicAndSensitiveToSensorEvidence() {
        val firstSet = frameSet(candidateDx = 2)
        val firstCalibration = calibration(firstSet)
        val first = Cp3ComputationalRawEngine.fuse(firstSet, firstCalibration) as Cp3FusionOutcome.Fused
        val repeated = Cp3ComputationalRawEngine.fuse(firstSet, firstCalibration) as Cp3FusionOutcome.Fused

        assertEquals(first.report.outputSha256, repeated.report.outputSha256)

        val changedSet = frameSet(candidateDx = 2, signalBias = 3)
        val changed = Cp3ComputationalRawEngine.fuse(changedSet, calibration(changedSet)) as Cp3FusionOutcome.Fused
        assertNotEquals(first.report.outputSha256, changed.report.outputSha256)
    }

    private fun frameSet(
        candidateDx: Int,
        secondExposureNs: Long = EXPOSURE_NS,
        signalBias: Int = 0,
    ): ImmutableRawFrameSet {
        val size = IntSize(SIZE, SIZE)
        val canonicalBytes = SIZE.toLong() * SIZE.toLong() * 2L
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 2,
            rawSize = size,
            maxSourceBytesPerFrame = canonicalBytes,
            maxResidentBytes = 4L * 1024L * 1024L,
        )
        val context = RawCaptureContext(
            captureToken = CaptureToken(41L),
            selectionGeneration = SelectionGeneration(2L),
            sessionGeneration = SessionGeneration(7L),
            canonicalLensFingerprint = CanonicalLensFingerprint("cp3-lens"),
            cameraProfileFingerprint = CameraProfileFingerprint("cp3-profile"),
            routeId = CameraRouteId("cp3-route"),
            displayRotationAtShutter = DisplayRotation.ROTATION_0,
            sensorOrientationDegrees = 90,
            lensFacing = LensFacing.BACK,
            rawSize = size,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        val reference = raster { x, y -> syntheticSignal(x, y) + signalBias }
        val candidate = raster { x, y ->
            val sourceX = x - candidateDx
            if (sourceX in 0 until SIZE) syntheticSignal(sourceX, y) + signalBias else syntheticSignal(x, y) + signalBias
        }
        return ImmutableRawFrameSet(
            context = context,
            reservation = reservation,
            frames = listOf(
                frame(0, 100L, EXPOSURE_NS, reference),
                frame(1, 200L, secondExposureNs, candidate),
            ),
        )
    }

    private fun calibration(
        frameSet: ImmutableRawFrameSet,
        missingNoiseOrdinal: Int? = null,
    ) = Cp2CalibrationAssembler.assemble(
        frameSet = frameSet,
        staticObservation = Cp2StaticCalibrationObservation(
            canonicalLensFingerprint = CanonicalLensFingerprint("cp3-lens"),
            cameraProfileFingerprint = CameraProfileFingerprint("cp3-profile"),
            routeId = CameraRouteId("cp3-route"),
            rawSize = IntSize(SIZE, SIZE),
            cfaArrangement = 0,
            activeArray = Cp2RectEvidence(0, 0, SIZE, SIZE),
            preCorrectionActiveArray = Cp2RectEvidence(0, 0, SIZE, SIZE),
            blackLevels = listOf(BLACK, BLACK, BLACK, BLACK),
            whiteLevel = WHITE,
            referenceIlluminant1 = null,
            referenceIlluminant2 = null,
            colorTransform1 = null,
            colorTransform2 = null,
            calibrationTransform1 = null,
            calibrationTransform2 = null,
            forwardMatrix1 = null,
            forwardMatrix2 = null,
        ),
        dynamicObservations = frameSet.frames.map { frame ->
            Cp2DynamicCalibrationObservation(
                ordinal = frame.ordinal,
                sensorTimestampNs = frame.metadata.sensorTimestampNs,
                dynamicBlackLevels = listOf(BLACK.toDouble(), BLACK.toDouble(), BLACK.toDouble(), BLACK.toDouble()),
                dynamicWhiteLevel = WHITE,
                noiseProfile = if (frame.ordinal == missingNoiseOrdinal) null else List(4) {
                    Cp2NoiseCoefficient(shotSlope = 0.0005, readVariance = 0.000001)
                },
            )
        },
    )

    private fun frame(
        ordinal: Int,
        timestamp: Long,
        exposureNs: Long,
        bytes: ByteArray,
    ) = ImmutableRawBurstFrame(
        ordinal = ordinal,
        rawSize = IntSize(SIZE, SIZE),
        sourceRowStrideBytes = SIZE * 2,
        sourcePixelStrideBytes = 2,
        sourceRequiredBytes = bytes.size.toLong(),
        canonicalRowBytes = SIZE * 2,
        metadata = RawBurstFrameMetadata(
            sensorTimestampNs = timestamp,
            frameNumber = ordinal.toLong(),
            exposureTimeNs = exposureNs,
            sensitivityIso = ISO,
            frameDurationNs = 33_333_333L,
        ),
        canonicalRaster = bytes,
    )

    private fun raster(valueAt: (Int, Int) -> Int): ByteArray {
        val bytes = ByteArray(SIZE * SIZE * 2)
        var offset = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val value = valueAt(x, y).coerceIn(BLACK + 1, WHITE - 1)
                bytes[offset++] = (value and 0xff).toByte()
                bytes[offset++] = ((value ushr 8) and 0xff).toByte()
            }
        }
        return bytes
    }

    private fun syntheticSignal(x: Int, y: Int): Int =
        500 + ((x * 17 + y * 31 + (x * y) % 97) % 2500)

    private companion object {
        const val SIZE = 256
        const val BLACK = 64
        const val WHITE = 4095
        const val ISO = 100
        const val EXPOSURE_NS = 10_000_000L
    }
}
