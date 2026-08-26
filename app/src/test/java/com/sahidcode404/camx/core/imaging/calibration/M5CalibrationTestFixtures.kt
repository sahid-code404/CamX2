package com.sahidcode404.camx.core.imaging.calibration

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

internal object M5CalibrationTestFixtures {
    val rawSize = IntSize(2, 2)
    val lens = CanonicalLensFingerprint("m5-lens")
    val profileFingerprint = CameraProfileFingerprint("m5-profile")

    fun frameSet(): ImmutableRawFrameSet {
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 2,
            rawSize = rawSize,
            maxSourceBytesPerFrame = 8L,
            maxResidentBytes = 2L * 1024L * 1024L,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        val context = RawCaptureContext(
            captureToken = CaptureToken(41L),
            selectionGeneration = SelectionGeneration(7L),
            sessionGeneration = SessionGeneration(9L),
            canonicalLensFingerprint = lens,
            cameraProfileFingerprint = profileFingerprint,
            routeId = CameraRouteId("m5-route"),
            displayRotationAtShutter = DisplayRotation.ROTATION_0,
            sensorOrientationDegrees = 90,
            lensFacing = LensFacing.BACK,
            rawSize = rawSize,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        val frames = listOf(
            rawFrame(0, 10_000L, 100L, intArrayOf(64, 128, 512, 1023)),
            rawFrame(1, 20_000L, 101L, intArrayOf(65, 129, 513, 1022)),
        )
        return ImmutableRawFrameSet(context, reservation, frames)
    }

    fun profile(
        cameraProfileFingerprint: CameraProfileFingerprint = profileFingerprint,
        confidence: CalibrationConfidenceVector = confidence(),
        whiteLevels: CfaDoubleQuad = CfaDoubleQuad(1023.0, 1023.0, 1023.0, 1023.0),
        colorCalibration: ColorMatrixCalibration? = colorCalibration(),
    ): M5CalibrationProfile = M5CalibrationProfile(
        profileId = "m5-reference",
        version = "1",
        canonicalLensFingerprint = lens,
        cameraProfileFingerprint = cameraProfileFingerprint,
        rawSize = rawSize,
        activeArea = IntRect(0, 0, 2, 2),
        cfaPattern = CfaPattern.RGGB,
        blackLevelsDn = CfaDoubleQuad(64.0, 64.0, 64.0, 64.0),
        whiteLevelsDn = whiteLevels,
        noiseModel = noiseModel(),
        colorCalibration = colorCalibration,
        confidence = confidence,
        origin = CalibrationOrigin.COMBINED,
    )

    fun confidence(
        shotNoise: Double = 0.95,
        colorCalibration: Double? = 0.95,
    ) = CalibrationConfidenceVector(
        blackLevel = 0.95,
        whiteLevel = 0.96,
        cfaAndActiveArea = 0.99,
        shotNoise = shotNoise,
        readNoise = 0.94,
        fixedPatternNoise = 0.93,
        colorCalibration = colorCalibration,
    )

    fun noiseModel(): CfaNoiseModel {
        val p = NoiseParameters(
            shotVarianceSlopeDn2PerDn = 0.5,
            readVarianceDn2 = 4.0,
            fixedPatternFractionSigma = 0.01,
        )
        return CfaNoiseModel(p, p, p, p)
    }

    fun colorCalibration(): ColorMatrixCalibration = ColorMatrixCalibration(
        listOf(
            ColorMatrixEntry(
                ReferenceIlluminant(21, "D65"),
                Matrix3x3(
                    1.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                    0.0, 0.0, 1.0,
                ),
            ),
        ),
    )

    fun validationCorpus(): CalibrationValidationCorpus {
        val dark = mutableListOf<DarkResidualSample>()
        val flat = mutableListOf<FlatResidualSample>()
        val linearity = mutableListOf<LinearitySample>()
        for (site in 0..3) {
            dark += DarkResidualSample(site, 64.0)
            dark += DarkResidualSample(site, 65.0)
            flat += FlatResidualSample(site, 1.0)
            flat += FlatResidualSample(site, 1.01)
            flat += FlatResidualSample(site, 0.99)
            linearity += LinearitySample(site, 1.0, 100.0)
            linearity += LinearitySample(site, 2.0, 200.0)
            linearity += LinearitySample(site, 4.0, 400.0)
        }
        return CalibrationValidationCorpus(dark, flat, linearity)
    }

    fun thresholds() = CalibrationValidationThresholds(
        maxDarkRmseDn = 2.0,
        maxFlatNormalizedRmse = 0.02,
        maxLinearityNormalizedRmse = 0.001,
        minBlackLevelConfidence = 0.9,
        minWhiteLevelConfidence = 0.9,
        minCfaAndActiveAreaConfidence = 0.9,
        minShotNoiseConfidence = 0.9,
        minReadNoiseConfidence = 0.9,
        minFixedPatternNoiseConfidence = 0.9,
        minColorCalibrationConfidenceWhenPresent = 0.9,
    )

    private fun rawFrame(
        ordinal: Int,
        timestampNs: Long,
        frameNumber: Long,
        values: IntArray,
    ) = ImmutableRawBurstFrame(
        ordinal = ordinal,
        rawSize = rawSize,
        sourceRowStrideBytes = 4,
        sourcePixelStrideBytes = 2,
        sourceRequiredBytes = 8L,
        canonicalRowBytes = 4,
        metadata = RawBurstFrameMetadata(
            sensorTimestampNs = timestampNs,
            frameNumber = frameNumber,
            exposureTimeNs = 5_000_000L,
            sensitivityIso = 100,
            frameDurationNs = 33_333_333L,
        ),
        canonicalRaster = littleEndianRaw16(values),
    )

    private fun littleEndianRaw16(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            require(value in 0..65535)
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return bytes
    }
}
