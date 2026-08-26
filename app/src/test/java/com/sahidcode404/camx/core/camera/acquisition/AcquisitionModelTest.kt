package com.sahidcode404.camx.core.camera.acquisition

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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AcquisitionModelTest {
    @Test
    fun descriptorDefensivelyCopiesCollections() {
        val planes = mutableListOf(
            AcquisitionPlaneDescriptor(0, 0, 6, 4, 2, 2),
        )
        val fields = mutableListOf(InterpretationField("z", "one"))
        val descriptor = RepresentationDescriptor(
            representation = MosaicSensorSamples,
            sourceFormat = PublicSourceFormat.RAW_SENSOR,
            packing = SamplePacking.UNPACKED_16_LE,
            storedBits = 16,
            effectiveBits = 12,
            size = IntSize(2, 2),
            activeArea = IntRect(0, 0, 2, 2),
            planeDescriptors = planes,
            cfaPattern = CfaPattern.RGGB,
            sensorPixelMode = SensorPixelMode.DEFAULT,
            colorCalibrationIdentity = null,
            calibration = CalibrationEvidence(null, null, 0.0),
            sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
            interpretationFields = fields,
        )
        planes.clear()
        fields += InterpretationField("a", "two")
        assertEquals(1, descriptor.planes.size)
        assertEquals(listOf("z"), descriptor.interpretationFields.map { it.key })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (descriptor.planes as MutableList<AcquisitionPlaneDescriptor>).clear()
        }
    }

    @Test
    fun opaquePrivateTransportCannotMasqueradeAsSensorSamples() {
        assertThrows(IllegalArgumentException::class.java) {
            RepresentationDescriptor(
                representation = RawPrivateToken,
                sourceFormat = PublicSourceFormat.RAW_SENSOR,
                packing = SamplePacking.UNPACKED_16_LE,
                storedBits = 16,
                effectiveBits = 12,
                size = IntSize(2, 2),
                activeArea = IntRect(0, 0, 2, 2),
                planeDescriptors = listOf(AcquisitionPlaneDescriptor(0, 0, 4, 4, 2, 2)),
                cfaPattern = null,
                sensorPixelMode = SensorPixelMode.DEFAULT,
                colorCalibrationIdentity = null,
                calibration = CalibrationEvidence(null, null, 0.0),
                sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
            )
        }
    }

    @Test
    fun planeExtentOverflowFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            AcquisitionPlaneDescriptor(
                planeIndex = 0,
                offsetBytes = Long.MAX_VALUE - 2,
                rowStrideBytes = Long.MAX_VALUE - 1,
                meaningfulRowBytes = 2,
                rowCount = 2,
                pixelStrideBytes = 1,
            )
        }
    }

    @Test
    fun activeAreaMustStayInsideSource() {
        assertThrows(IllegalArgumentException::class.java) {
            RepresentationDescriptor(
                representation = MosaicSensorSamples,
                sourceFormat = PublicSourceFormat.RAW_SENSOR,
                packing = SamplePacking.UNPACKED_16_LE,
                storedBits = 16,
                effectiveBits = 12,
                size = IntSize(2, 2),
                activeArea = IntRect(1, 0, 2, 2),
                planeDescriptors = listOf(AcquisitionPlaneDescriptor(0, 0, 4, 4, 2, 2)),
                cfaPattern = CfaPattern.RGGB,
                sensorPixelMode = SensorPixelMode.DEFAULT,
                colorCalibrationIdentity = null,
                calibration = CalibrationEvidence(null, null, 0.0),
                sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
            )
        }
    }

    @Test
    fun acceptedRawContextBridgesToHistoricalIdentityWithoutLiveLookup() {
        val raw = RawCaptureContext(
            captureToken = CaptureToken(19L),
            selectionGeneration = SelectionGeneration(4L),
            sessionGeneration = SessionGeneration(8L),
            canonicalLensFingerprint = CanonicalLensFingerprint("lens-raw"),
            cameraProfileFingerprint = CameraProfileFingerprint("profile-raw"),
            routeId = CameraRouteId("route-raw"),
            displayRotationAtShutter = DisplayRotation.ROTATION_90,
            sensorOrientationDegrees = 90,
            lensFacing = LensFacing.BACK,
            rawSize = IntSize(2, 2),
            timeoutMillis = 2_000L,
        )
        val descriptor = sensorDescriptor()
        val identity = raw.toAcquisitionIdentity(
            providerEpoch = 5L,
            representation = descriptor,
            timebase = acquisitionIdentity(descriptor).timebase,
        )
        assertEquals(raw.captureToken, identity.captureToken)
        assertEquals(raw.canonicalLensFingerprint, identity.canonicalLensFingerprint)
        assertEquals(raw.cameraProfileFingerprint, identity.cameraProfileFingerprint)
        assertEquals(raw.routeId, identity.routeId)
        assertNotSame(raw, identity)
        assertEquals(identity.permitIdentity(), raw.toAcquisitionPermitIdentity(providerEpoch = 5L))
    }
}
