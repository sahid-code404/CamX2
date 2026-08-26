package com.sahidcode404.camx.core.imaging.alignment

import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
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
import com.sahidcode404.camx.core.camera.raw.ImmutableRawBurstFrame
import com.sahidcode404.camx.core.camera.raw.ImmutableRawFrameSet
import com.sahidcode404.camx.core.camera.raw.M4BurstLimits
import com.sahidcode404.camx.core.camera.raw.RawBurstFrameMetadata
import com.sahidcode404.camx.core.camera.raw.RawBurstReservation
import com.sahidcode404.camx.core.imaging.calibration.CalibrationConfidenceVector
import com.sahidcode404.camx.core.imaging.calibration.CalibrationOrigin
import com.sahidcode404.camx.core.imaging.calibration.CalibrationReservation
import com.sahidcode404.camx.core.imaging.calibration.CfaDoubleQuad
import com.sahidcode404.camx.core.imaging.calibration.CfaNoiseModel
import com.sahidcode404.camx.core.imaging.calibration.M5CalibrationProfile
import com.sahidcode404.camx.core.imaging.calibration.NoiseParameters
import com.sahidcode404.camx.core.imaging.calibration.ReferenceCalibrationEngine

internal object M6AlignmentTestFixtures {
    private val size = IntSize(12, 12)
    private val lens = CanonicalLensFingerprint("m6-lens")
    private val profileFingerprint = CameraProfileFingerprint("m6-profile")

    fun translatedMeasurements(dx: Int = 2, dy: Int = 0, occludeReferenceX: Int? = null) =
        measurements(listOf(basePattern(), translated(basePattern(), dx, dy, occludeReferenceX)))

    fun constantMeasurements() = measurements(
        listOf(IntArray(size.width * size.height) { 1000 }, IntArray(size.width * size.height) { 1000 }),
    )

    fun mixedMeasurements() = measurements(
        listOf(basePattern(), translated(basePattern(), 2, 0, null), IntArray(size.width * size.height) { 3000 }),
    )

    fun rollingMeasurements() = measurements(listOf(basePattern(), rollingShift(basePattern())))

    fun request(
        searchRadius: Int = 4,
        minVisibility: Double = 0.50,
        minUsable: Double = 0.50,
        minInlier: Double = 0.80,
        maxResidual: Double = 10.0,
        minSeparation: Double = 0.01,
        minAcceptedFrames: Int = 1,
    ) = AlignmentRequest(
        referenceOrdinal = 0,
        searchRadiusPixels = searchRadius,
        sampleStepPixels = 2,
        inlierSigmaThreshold = 3.0,
        minimumVisibilityFraction = minVisibility,
        minimumUsableFraction = minUsable,
        minimumInlierFraction = minInlier,
        maximumMeanNormalizedSquaredResidual = maxResidual,
        minimumCostSeparation = minSeparation,
        minimumAcceptedFrames = minAcceptedFrames,
    )

    fun align(measurements: com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet, request: AlignmentRequest) =
        ReferenceAlignmentEngine.align(
            measurements,
            request,
            AlignmentReservation.forMeasurements(measurements, request),
        )

    private fun measurements(rasters: List<IntArray>): com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet {
        val canonicalBytes = size.width.toLong() * size.height.toLong() * 2L
        val burstReservation = RawBurstReservation.forRawSensor(
            frameCount = rasters.size,
            rawSize = size,
            maxSourceBytesPerFrame = canonicalBytes,
            maxResidentBytes = 8L * 1024L * 1024L,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        val context = RawCaptureContext(
            captureToken = CaptureToken(61L),
            selectionGeneration = SelectionGeneration(11L),
            sessionGeneration = SessionGeneration(13L),
            canonicalLensFingerprint = lens,
            cameraProfileFingerprint = profileFingerprint,
            routeId = CameraRouteId("m6-route"),
            displayRotationAtShutter = DisplayRotation.ROTATION_0,
            sensorOrientationDegrees = 90,
            lensFacing = LensFacing.BACK,
            rawSize = size,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        val frames = rasters.mapIndexed { ordinal, values ->
            ImmutableRawBurstFrame(
                ordinal = ordinal,
                rawSize = size,
                sourceRowStrideBytes = size.width * 2,
                sourcePixelStrideBytes = 2,
                sourceRequiredBytes = canonicalBytes,
                canonicalRowBytes = size.width * 2,
                metadata = RawBurstFrameMetadata(
                    sensorTimestampNs = 10_000L + ordinal * 1_000L,
                    frameNumber = 100L + ordinal,
                    exposureTimeNs = 5_000_000L,
                    sensitivityIso = 100,
                    frameDurationNs = 33_333_333L,
                ),
                canonicalRaster = littleEndianRaw16(values),
            )
        }
        val frameSet = ImmutableRawFrameSet(context, burstReservation, frames)
        val noise = NoiseParameters(shotVarianceSlopeDn2PerDn = 0.01, readVarianceDn2 = 0.25, fixedPatternFractionSigma = 0.0)
        val profile = M5CalibrationProfile(
            profileId = "m6-reference",
            version = "1",
            canonicalLensFingerprint = lens,
            cameraProfileFingerprint = profileFingerprint,
            rawSize = size,
            activeArea = IntRect(0, 0, size.width, size.height),
            cfaPattern = CfaPattern.RGGB,
            blackLevelsDn = CfaDoubleQuad(0.0, 0.0, 0.0, 0.0),
            whiteLevelsDn = CfaDoubleQuad(4095.0, 4095.0, 4095.0, 4095.0),
            noiseModel = CfaNoiseModel(noise, noise, noise, noise),
            colorCalibration = null,
            confidence = CalibrationConfidenceVector(
                blackLevel = 1.0,
                whiteLevel = 1.0,
                cfaAndActiveArea = 1.0,
                shotNoise = 1.0,
                readNoise = 1.0,
                fixedPatternNoise = 1.0,
                colorCalibration = null,
            ),
            origin = CalibrationOrigin.PROFILED_CORPUS,
        )
        val calibrationReservation = CalibrationReservation.forFrameSet(frameSet, 8L * 1024L * 1024L)
        return ReferenceCalibrationEngine.calibrate(frameSet, profile, calibrationReservation)
    }

    private fun basePattern(): IntArray = IntArray(size.width * size.height) { index ->
        val x = index % size.width
        val y = index / size.width
        500 + x * 71 + y * 113 + ((x * y * 29 + x * x * 7 + y * y * 11) % 700)
    }

    private fun translated(base: IntArray, dx: Int, dy: Int, occludeReferenceX: Int?): IntArray {
        require(dx % 2 == 0 && dy % 2 == 0)
        val output = IntArray(base.size) { 2100 }
        for (y in 0 until size.height) {
            for (x in 0 until size.width) {
                val targetX = x + dx
                val targetY = y + dy
                if (targetX in 0 until size.width && targetY in 0 until size.height) {
                    output[targetY * size.width + targetX] = base[y * size.width + x]
                }
            }
        }
        if (occludeReferenceX != null) {
            val referenceY = 4
            val targetX = occludeReferenceX + dx
            if (targetX in 0 until size.width) output[referenceY * size.width + targetX] = 3500
        }
        return output
    }

    private fun rollingShift(base: IntArray): IntArray {
        val output = IntArray(base.size) { 2100 }
        for (y in 0 until size.height) {
            val dx = if (y < size.height / 2) 2 else -2
            for (x in 0 until size.width) {
                val targetX = x + dx
                if (targetX in 0 until size.width) output[y * size.width + targetX] = base[y * size.width + x]
            }
        }
        return output
    }

    private fun littleEndianRaw16(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            require(value in 0..4095)
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return bytes
    }
}
