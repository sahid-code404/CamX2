package com.sahidcode404.camx.core.imaging.reconstruction

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
import com.sahidcode404.camx.core.imaging.alignment.AlignmentEvidenceSet
import com.sahidcode404.camx.core.imaging.alignment.AlignmentRequest
import com.sahidcode404.camx.core.imaging.alignment.AlignmentReservation
import com.sahidcode404.camx.core.imaging.alignment.ReferenceAlignmentEngine
import com.sahidcode404.camx.core.imaging.calibration.CalibratedMeasurementFrameSet
import com.sahidcode404.camx.core.imaging.calibration.CalibrationConfidenceVector
import com.sahidcode404.camx.core.imaging.calibration.CalibrationOrigin
import com.sahidcode404.camx.core.imaging.calibration.CalibrationReservation
import com.sahidcode404.camx.core.imaging.calibration.CfaDoubleQuad
import com.sahidcode404.camx.core.imaging.calibration.CfaNoiseModel
import com.sahidcode404.camx.core.imaging.calibration.ColorMatrixCalibration
import com.sahidcode404.camx.core.imaging.calibration.ColorMatrixEntry
import com.sahidcode404.camx.core.imaging.calibration.M5CalibrationProfile
import com.sahidcode404.camx.core.imaging.calibration.Matrix3x3
import com.sahidcode404.camx.core.imaging.calibration.NoiseParameters
import com.sahidcode404.camx.core.imaging.calibration.ReferenceCalibrationEngine
import com.sahidcode404.camx.core.imaging.calibration.ReferenceIlluminant

internal object M7ReconstructionTestFixtures {
    val size = IntSize(8, 8)
    private val lens = CanonicalLensFingerprint("m7-lens")
    private val profileFingerprint = CameraProfileFingerprint("m7-profile")

    fun identityColorCalibration(): ColorMatrixCalibration = ColorMatrixCalibration(
        listOf(
            ColorMatrixEntry(
                illuminant = ReferenceIlluminant(21, "D65"),
                sensorToXyz = Matrix3x3(
                    1.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                    0.0, 0.0, 1.0,
                ),
            ),
        ),
    )

    fun truthValues(): IntArray = IntArray(size.width * size.height) { index ->
        val x = index % size.width
        val y = index / size.width
        700 + x * 31 + y * 47 + ((x * y * 13 + x * x * 3 + y * y * 5) % 300)
    }

    fun measurementsFromOffsets(
        offsets: List<Int>,
        exposureTimesNs: List<Long?> = List(offsets.size) { 5_000_000L },
        sensitivityIso: List<Int?> = List(offsets.size) { 100 },
        mutations: Map<Pair<Int, Int>, Int> = emptyMap(),
        colorCalibration: ColorMatrixCalibration? = null,
    ): CalibratedMeasurementFrameSet {
        require(offsets.size >= 2)
        require(exposureTimesNs.size == offsets.size && sensitivityIso.size == offsets.size)
        val truth = truthValues()
        val rasters = offsets.mapIndexed { ordinal, offset ->
            IntArray(truth.size) { index ->
                mutations[ordinal to index] ?: (truth[index] + offset)
            }
        }
        return measurements(rasters, exposureTimesNs, sensitivityIso, colorCalibration)
    }

    fun measurementsFromRasters(
        rasters: List<IntArray>,
        exposureTimesNs: List<Long?> = List(rasters.size) { 5_000_000L },
        sensitivityIso: List<Int?> = List(rasters.size) { 100 },
        colorCalibration: ColorMatrixCalibration? = null,
    ): CalibratedMeasurementFrameSet = measurements(rasters, exposureTimesNs, sensitivityIso, colorCalibration)

    fun align(measurements: CalibratedMeasurementFrameSet): AlignmentEvidenceSet {
        val request = AlignmentRequest(
            referenceOrdinal = 0,
            searchRadiusPixels = 0,
            sampleStepPixels = 2,
            inlierSigmaThreshold = 5.0,
            minimumVisibilityFraction = 1.0,
            minimumUsableFraction = 0.75,
            minimumInlierFraction = 0.75,
            maximumMeanNormalizedSquaredResidual = 10_000.0,
            minimumCostSeparation = 0.0,
            minimumAcceptedFrames = 1,
        )
        return ReferenceAlignmentEngine.align(
            measurements,
            request,
            AlignmentReservation.forMeasurements(measurements, request),
        )
    }

    fun request(minimumFrames: Int = 2) = ReconstructionRequest(
        minimumContributingFrames = minimumFrames,
        maximumAlignmentSigmaPixels = 128.0,
        maximumRollingShutterDisagreementPixels = 1.0,
        maximumPerPixelResidualSigma = 5.0,
    )

    fun reconstruct(
        measurements: CalibratedMeasurementFrameSet,
        alignment: AlignmentEvidenceSet = align(measurements),
        request: ReconstructionRequest = request(),
    ): FusedCfaRadiance {
        val reservation = ReconstructionReservation.forInputs(
            measurements = measurements,
            alignment = alignment,
            request = request,
            maxResidentBytes = 8L * 1024L * 1024L,
        )
        return ReferenceReconstructionEngine.reconstruct(
            measurements = measurements,
            alignment = alignment,
            request = request,
            reservation = reservation,
            provenanceContext = ReconstructionProvenanceContext("test-build"),
        )
    }

    fun truthRaster(): ReconstructionTruthRaster {
        val truth = truthValues()
        return ReconstructionTruthRaster(
            originX = 0,
            originY = 0,
            width = size.width,
            height = size.height,
            expectedRadianceDn = DoubleArray(truth.size) { truth[it].toDouble() },
        )
    }

    private fun measurements(
        rasters: List<IntArray>,
        exposureTimesNs: List<Long?>,
        sensitivityIso: List<Int?>,
        colorCalibration: ColorMatrixCalibration?,
    ): CalibratedMeasurementFrameSet {
        require(rasters.size in M4BurstLimits.MIN_FRAMES..M4BurstLimits.MAX_FRAMES)
        require(rasters.all { it.size == size.width * size.height })
        val canonicalBytes = size.width.toLong() * size.height.toLong() * 2L
        val burstReservation = RawBurstReservation.forRawSensor(
            frameCount = rasters.size,
            rawSize = size,
            maxSourceBytesPerFrame = canonicalBytes,
            maxResidentBytes = 8L * 1024L * 1024L,
            timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
        )
        val context = RawCaptureContext(
            captureToken = CaptureToken(71L),
            selectionGeneration = SelectionGeneration(17L),
            sessionGeneration = SessionGeneration(19L),
            canonicalLensFingerprint = lens,
            cameraProfileFingerprint = profileFingerprint,
            routeId = CameraRouteId("m7-route"),
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
                    sensorTimestampNs = 20_000L + ordinal * 1_000L,
                    frameNumber = 200L + ordinal,
                    exposureTimeNs = exposureTimesNs[ordinal],
                    sensitivityIso = sensitivityIso[ordinal],
                    frameDurationNs = 33_333_333L,
                ),
                canonicalRaster = littleEndianRaw16(values),
            )
        }
        val frameSet = ImmutableRawFrameSet(context, burstReservation, frames)
        val noise = NoiseParameters(
            shotVarianceSlopeDn2PerDn = 0.0,
            readVarianceDn2 = 100.0,
            fixedPatternFractionSigma = 0.0,
        )
        val profile = M5CalibrationProfile(
            profileId = "m7-reference",
            version = "1",
            canonicalLensFingerprint = lens,
            cameraProfileFingerprint = profileFingerprint,
            rawSize = size,
            activeArea = IntRect(0, 0, size.width, size.height),
            cfaPattern = CfaPattern.RGGB,
            blackLevelsDn = CfaDoubleQuad(0.0, 0.0, 0.0, 0.0),
            whiteLevelsDn = CfaDoubleQuad(4095.0, 4095.0, 4095.0, 4095.0),
            noiseModel = CfaNoiseModel(noise, noise, noise, noise),
            colorCalibration = colorCalibration,
            confidence = CalibrationConfidenceVector(
                blackLevel = 1.0,
                whiteLevel = 1.0,
                cfaAndActiveArea = 1.0,
                shotNoise = 1.0,
                readNoise = 1.0,
                fixedPatternNoise = 1.0,
                colorCalibration = colorCalibration?.let { 1.0 },
            ),
            origin = CalibrationOrigin.PROFILED_CORPUS,
        )
        val calibrationReservation = CalibrationReservation.forFrameSet(frameSet, 8L * 1024L * 1024L)
        return ReferenceCalibrationEngine.calibrate(frameSet, profile, calibrationReservation)
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
