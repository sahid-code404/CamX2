package com.sahidcode404.camx.core.camera.acquisition

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration

internal fun sensorDescriptor(
    rowStride: Long = 6L,
    meaningfulRowBytes: Long = 4L,
    rowCount: Int = 2,
    fields: List<InterpretationField> = listOf(InterpretationField("blackLevelMode", "per-channel")),
): RepresentationDescriptor = RepresentationDescriptor(
    representation = MosaicSensorSamples,
    sourceFormat = PublicSourceFormat.RAW_SENSOR,
    packing = SamplePacking.UNPACKED_16_LE,
    storedBits = 16,
    effectiveBits = 12,
    size = IntSize(2, 2),
    activeArea = IntRect(0, 0, 2, 2),
    planeDescriptors = listOf(
        AcquisitionPlaneDescriptor(
            planeIndex = 0,
            offsetBytes = 0,
            rowStrideBytes = rowStride,
            meaningfulRowBytes = meaningfulRowBytes,
            rowCount = rowCount,
            pixelStrideBytes = 2,
        ),
    ),
    cfaPattern = CfaPattern.RGGB,
    sensorPixelMode = SensorPixelMode.DEFAULT,
    colorCalibrationIdentity = "unit-color",
    calibration = CalibrationEvidence("unit-cal", "v1", 1.0),
    sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
    interpretationFields = fields,
)

internal fun acquisitionIdentity(
    descriptor: RepresentationDescriptor = sensorDescriptor(),
    captureToken: Long = 7L,
    sessionGeneration: Long = 3L,
): AcquisitionIdentity = AcquisitionIdentity(
    canonicalLensFingerprint = CanonicalLensFingerprint("lens-a"),
    cameraProfileFingerprint = CameraProfileFingerprint("profile-a"),
    routeId = CameraRouteId("route-a"),
    physicalTarget = PhysicalCameraId("physical-a"),
    providerEpoch = 11L,
    selectionGeneration = SelectionGeneration(2L),
    sessionGeneration = SessionGeneration(sessionGeneration),
    captureToken = CaptureToken(captureToken),
    captureGeneration = 5L,
    surfaceGeneration = 9L,
    representation = descriptor,
    timebase = TimebaseEvidence(
        imageTimestampNs = 1_000L,
        captureResultTimestampNs = 1_000L,
        requestIssuedTimestampNs = 900L,
        declaredTimebase = AcquisitionTimebase.SENSOR,
        normalizedOffsetNs = null,
        mappingUncertaintyNs = null,
    ),
)
