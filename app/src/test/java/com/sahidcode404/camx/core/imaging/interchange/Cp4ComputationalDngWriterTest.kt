package com.sahidcode404.camx.core.imaging.interchange

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
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationAssembler
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationBundle
import com.sahidcode404.camx.core.camera.raw.Cp2DynamicCalibrationObservation
import com.sahidcode404.camx.core.camera.raw.Cp2Matrix3x3Evidence
import com.sahidcode404.camx.core.camera.raw.Cp2RationalEvidence
import com.sahidcode404.camx.core.camera.raw.Cp2RectEvidence
import com.sahidcode404.camx.core.camera.raw.Cp2StaticCalibrationObservation
import com.sahidcode404.camx.core.camera.raw.ImmutableRawBurstFrame
import com.sahidcode404.camx.core.camera.raw.ImmutableRawFrameSet
import com.sahidcode404.camx.core.camera.raw.M4BurstLimits
import com.sahidcode404.camx.core.camera.raw.RawBurstFrameMetadata
import com.sahidcode404.camx.core.camera.raw.RawBurstReservation
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FixedPatternNoiseMode
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusedCfa
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusionReport
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Cp4ComputationalDngWriterTest {
    @Test
    fun deterministicFusedCfaWritesOneDngWithBoundHashes() {
        val fixture = fixture(activeLeft = 0, activeTop = 0, includeColor = true)
        val first = ByteArrayOutputStream()
        val firstReceipt = Cp4ComputationalDngWriter().write(
            fused = fixture.fused,
            fusionReport = fixture.report,
            calibration = fixture.calibration,
            uniqueCameraModel = "CamX2 Test Sensor",
            orientation = 6,
            output = first,
            maxOutputBytes = 2L * 1024L * 1024L,
        )
        val second = ByteArrayOutputStream()
        val secondReceipt = Cp4ComputationalDngWriter().write(
            fused = fixture.fused,
            fusionReport = fixture.report,
            calibration = fixture.calibration,
            uniqueCameraModel = "CamX2 Test Sensor",
            orientation = 6,
            output = second,
            maxOutputBytes = 2L * 1024L * 1024L,
        )

        assertArrayEquals(first.toByteArray(), second.toByteArray())
        assertEquals(firstReceipt.sha256, secondReceipt.sha256)
        assertEquals(fixture.fused.outputSha256, firstReceipt.cp3OutputSha256)
        assertEquals(fixture.calibration.report.calibrationFingerprintSha256, firstReceipt.calibrationFingerprintSha256)
        assertEquals(first.size().toLong(), firstReceipt.byteCount)
        assertEquals('I'.code, first.toByteArray()[0].toInt())
        assertEquals('I'.code, first.toByteArray()[1].toInt())
        assertEquals(42, uint16(first.toByteArray(), 2))
    }

    @Test
    fun oddActiveOriginShiftsExportedCfaPhaseWithoutRemosaic() {
        val fixture = fixture(activeLeft = 1, activeTop = 1, includeColor = true)
        val output = ByteArrayOutputStream()
        Cp4ComputationalDngWriter().write(
            fused = fixture.fused,
            fusionReport = fixture.report,
            calibration = fixture.calibration,
            uniqueCameraModel = "CamX2 CFA Phase Test",
            orientation = 1,
            output = output,
            maxOutputBytes = 2L * 1024L * 1024L,
        )

        val pattern = tagPayload(output.toByteArray(), 33422)
        assertArrayEquals(byteArrayOf(2, 1, 1, 0), pattern)
    }

    @Test
    fun missingCamera2ColorAuthorityFailsClosed() {
        val fixture = fixture(activeLeft = 0, activeTop = 0, includeColor = false)
        assertThrows(IllegalArgumentException::class.java) {
            Cp4ComputationalDngWriter().write(
                fused = fixture.fused,
                fusionReport = fixture.report,
                calibration = fixture.calibration,
                uniqueCameraModel = "CamX2 No Fake Color",
                orientation = 1,
                output = ByteArrayOutputStream(),
                maxOutputBytes = 2L * 1024L * 1024L,
            )
        }
    }

    @Test
    fun foreignCalibrationFingerprintCannotWriteCp3Output() {
        val fixture = fixture(activeLeft = 0, activeTop = 0, includeColor = true)
        val foreign = fixture.report.copyForCalibration("f".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            Cp4ComputationalDngWriter().write(
                fused = fixture.fused,
                fusionReport = foreign,
                calibration = fixture.calibration,
                uniqueCameraModel = "CamX2 Binding Test",
                orientation = 1,
                output = ByteArrayOutputStream(),
                maxOutputBytes = 2L * 1024L * 1024L,
            )
        }
    }

    private fun fixture(activeLeft: Int, activeTop: Int, includeColor: Boolean): Fixture {
        val frameSet = frameSet()
        val color = if (includeColor) identityMatrix() else null
        val static = Cp2StaticCalibrationObservation(
            canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
            cameraProfileFingerprint = CameraProfileFingerprint("profile"),
            routeId = CameraRouteId("route"),
            rawSize = IntSize(4, 4),
            cfaArrangement = 0,
            activeArray = Cp2RectEvidence(activeLeft, activeTop, activeLeft + 2, activeTop + 2),
            preCorrectionActiveArray = Cp2RectEvidence(0, 0, 4, 4),
            blackLevels = listOf(64, 64, 64, 64),
            whiteLevel = 1023,
            referenceIlluminant1 = if (includeColor) 21 else null,
            referenceIlluminant2 = null,
            colorTransform1 = color,
            colorTransform2 = null,
            calibrationTransform1 = null,
            calibrationTransform2 = null,
            forwardMatrix1 = null,
            forwardMatrix2 = null,
        )
        val calibration = Cp2CalibrationAssembler.assemble(
            frameSet = frameSet,
            staticObservation = static,
            dynamicObservations = frameSet.frames.map { frame ->
                Cp2DynamicCalibrationObservation(
                    ordinal = frame.ordinal,
                    sensorTimestampNs = frame.metadata.sensorTimestampNs,
                    dynamicBlackLevels = null,
                    dynamicWhiteLevel = null,
                    noiseProfile = null,
                )
            },
        )
        assertTrue(calibration.report.success)
        val outputSha = "a".repeat(64)
        val fused = Cp3FusedCfa(
            activeArea = IntRect(activeLeft, activeTop, 2, 2),
            cfaPattern = CfaPattern.RGGB,
            signalDn = floatArrayOf(100f, 200f, 300f, 400f),
            outputSha256 = outputSha,
        )
        val report = Cp3FusionReport(
            success = true,
            algorithmId = "cp3-test",
            algorithmVersion = 1,
            requestedFrames = 2,
            referenceOrdinal = 0,
            exposureIdentityFrames = 2,
            alignedFrames = 2,
            contributingFrames = 2,
            activePixelCount = 4,
            multiFramePixelCount = 4,
            referenceOnlyPixelCount = 0,
            censoredPixelCount = 0,
            rejectedPixelMeasurements = 0,
            calibrationFingerprintSha256 = calibration.report.calibrationFingerprintSha256,
            sourceCanonicalSha256 = frameSet.frames.map { it.canonicalSha256 },
            includedOrdinals = listOf(0, 1),
            frameEvidence = emptyList(),
            outputSha256 = outputSha,
            fixedPatternNoiseMode = Cp3FixedPatternNoiseMode.UNAVAILABLE_NOT_INVENTED,
            evidencePersisted = true,
            failureDetail = null,
        )
        return Fixture(calibration, fused, report)
    }

    private fun frameSet(): ImmutableRawFrameSet {
        val size = IntSize(4, 4)
        val reservation = RawBurstReservation.forRawSensor(
            frameCount = 2,
            rawSize = size,
            maxSourceBytesPerFrame = 32,
            maxResidentBytes = 2L * 1024L * 1024L,
        )
        val context = RawCaptureContext(
            captureToken = CaptureToken(1),
            selectionGeneration = SelectionGeneration(1),
            sessionGeneration = SessionGeneration(1),
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
            frames = listOf(frame(0, 100), frame(1, 200)),
        )
    }

    private fun frame(ordinal: Int, timestamp: Long): ImmutableRawBurstFrame = ImmutableRawBurstFrame(
        ordinal = ordinal,
        rawSize = IntSize(4, 4),
        sourceRowStrideBytes = 8,
        sourcePixelStrideBytes = 2,
        sourceRequiredBytes = 32,
        canonicalRowBytes = 8,
        metadata = RawBurstFrameMetadata(
            sensorTimestampNs = timestamp,
            frameNumber = ordinal.toLong(),
            exposureTimeNs = 10_000_000,
            sensitivityIso = 100,
            frameDurationNs = 33_333_333,
        ),
        canonicalRaster = ByteArray(32) { index -> (index + ordinal).toByte() },
    )

    private fun identityMatrix() = Cp2Matrix3x3Evidence(
        listOf(
            rational(1), rational(0), rational(0),
            rational(0), rational(1), rational(0),
            rational(0), rational(0), rational(1),
        ),
    )

    private fun rational(value: Int) = Cp2RationalEvidence(value, 1)

    private fun Cp3FusionReport.copyForCalibration(fingerprint: String) = Cp3FusionReport(
        success = success,
        algorithmId = algorithmId,
        algorithmVersion = algorithmVersion,
        requestedFrames = requestedFrames,
        referenceOrdinal = referenceOrdinal,
        exposureIdentityFrames = exposureIdentityFrames,
        alignedFrames = alignedFrames,
        contributingFrames = contributingFrames,
        activePixelCount = activePixelCount,
        multiFramePixelCount = multiFramePixelCount,
        referenceOnlyPixelCount = referenceOnlyPixelCount,
        censoredPixelCount = censoredPixelCount,
        rejectedPixelMeasurements = rejectedPixelMeasurements,
        calibrationFingerprintSha256 = fingerprint,
        sourceCanonicalSha256 = sourceCanonicalSha256,
        includedOrdinals = includedOrdinals,
        frameEvidence = frameEvidence,
        outputSha256 = outputSha256,
        fixedPatternNoiseMode = fixedPatternNoiseMode,
        evidencePersisted = evidencePersisted,
        failureDetail = failureDetail,
    )

    private fun tagPayload(bytes: ByteArray, wantedTag: Int): ByteArray {
        val ifdOffset = uint32(bytes, 4).toInt()
        val count = uint16(bytes, ifdOffset)
        repeat(count) { index ->
            val offset = ifdOffset + 2 + index * 12
            if (uint16(bytes, offset) == wantedTag) {
                val type = uint16(bytes, offset + 2)
                val elementCount = uint32(bytes, offset + 4).toInt()
                val byteCount = typeSize(type) * elementCount
                val payloadOffset = if (byteCount <= 4) offset + 8 else uint32(bytes, offset + 8).toInt()
                return bytes.copyOfRange(payloadOffset, payloadOffset + byteCount)
            }
        }
        error("Missing TIFF tag $wantedTag")
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2 -> 1
        3 -> 2
        4 -> 4
        10 -> 8
        else -> error("Unexpected TIFF type $type")
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private data class Fixture(
        val calibration: Cp2CalibrationBundle,
        val fused: Cp3FusedCfa,
        val report: Cp3FusionReport,
    )
}
