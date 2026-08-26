package com.sahidcode404.camx.core.rawvideo.container

import com.sahidcode404.camx.core.camera.acquisition.AcquisitionIdentity
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionPlaneDescriptor
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionSourceApi
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionTimebase
import com.sahidcode404.camx.core.camera.acquisition.CalibrationEvidence
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.camera.acquisition.MosaicSensorSamples
import com.sahidcode404.camx.core.camera.acquisition.PublicSourceFormat
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor
import com.sahidcode404.camx.core.camera.acquisition.SamplePacking
import com.sahidcode404.camx.core.camera.acquisition.SensorPixelMode
import com.sahidcode404.camx.core.camera.acquisition.TimebaseEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import java.io.File

internal val TEST_PACKED_NONE_PAYLOAD = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

internal fun m2aDescriptor(): RepresentationDescriptor = RepresentationDescriptor(
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
            rowStrideBytes = 4,
            meaningfulRowBytes = 4,
            rowCount = 2,
            pixelStrideBytes = 2,
        ),
    ),
    cfaPattern = CfaPattern.RGGB,
    sensorPixelMode = SensorPixelMode.DEFAULT,
    colorCalibrationIdentity = "m2a-color",
    calibration = CalibrationEvidence("m2a-calibration", "1", 1.0),
    sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
)

internal fun m2aIdentity(
    frameToken: Long,
    profile: String = "profile-m2a",
): AcquisitionIdentity = AcquisitionIdentity(
    canonicalLensFingerprint = CanonicalLensFingerprint("lens-m2a"),
    cameraProfileFingerprint = CameraProfileFingerprint(profile),
    routeId = CameraRouteId("route-m2a"),
    physicalTarget = PhysicalCameraId("physical-m2a"),
    providerEpoch = 10L,
    selectionGeneration = SelectionGeneration(2L),
    sessionGeneration = SessionGeneration(3L),
    captureToken = CaptureToken(frameToken),
    captureGeneration = frameToken,
    surfaceGeneration = 4L,
    representation = m2aDescriptor(),
    timebase = TimebaseEvidence(
        imageTimestampNs = 1_000_000L + frameToken,
        captureResultTimestampNs = 1_000_000L + frameToken,
        requestIssuedTimestampNs = 999_000L + frameToken,
        declaredTimebase = AcquisitionTimebase.SENSOR,
        normalizedOffsetNs = 50L,
        mappingUncertaintyNs = 10L,
    ),
)

internal fun m2aFrame(
    ordinal: ULong,
    profile: String = "profile-m2a",
    discontinuityBefore: Boolean = false,
): PackedNoneFrame {
    val token = (ordinal % 1_000_000uL).toLong() + 1L
    return PackedNoneFrame(
        frameOrdinal = FrameOrdinal(ordinal),
        identity = m2aIdentity(frameToken = token, profile = profile),
        canonicalRaster = CanonicalRasterDigest(
            sha256 = sha256Hex(TEST_PACKED_NONE_PAYLOAD),
            byteCount = TEST_PACKED_NONE_PAYLOAD.size.toLong(),
        ),
        payload = TEST_PACKED_NONE_PAYLOAD,
        hostTimestampNs = 2_000_000L + (ordinal and 0xffffuL).toLong(),
        normalizedTimestampNs = 1_000_050L + (ordinal and 0xffffuL).toLong(),
        timebaseUncertaintyNs = 10L,
        metadata = listOf(
            RawVideoMetadataEntry("codec", "PACKED_NONE"),
            RawVideoMetadataEntry("source", "M1-canonical-raster"),
        ),
        discontinuityBefore = discontinuityBefore,
    )
}

internal fun m2aWriterConfig(
    maxFileBytes: Long = 8L * 1024L * 1024L * 1024L,
    maxSegmentRecords: Int = 16,
): CxrbWriterConfig = CxrbWriterConfig(
    storage = StorageCapabilityDeclaration(
        storageClass = "junit-reference-storage",
        maxFileBytes = maxFileBytes,
        declaredSustainedWriteBytesPerSecond = 64L * 1024L * 1024L,
        supportsDurableSync = true,
        supports64BitOffsets = true,
    ),
    maxFrameBytes = 1024L,
    maxMetadataBytes = 4096,
    maxSegmentRecords = maxSegmentRecords,
)

internal fun writeTwoCheckpointFile(file: File): Pair<CxrbCheckpoint, CxrbCheckpoint> {
    CxrbReferenceWriter(file, m2aWriterConfig()).use { writer ->
        writer.beginSegment(CxrbSegmentEpoch(0uL, 0uL, 0uL, FrameOrdinal(0uL)))
        writer.appendFrame(m2aFrame(0uL))
        val first = writer.commitSegment()
        writer.beginSegment(CxrbSegmentEpoch(1uL, 0uL, 0uL, FrameOrdinal(1uL)))
        writer.appendFrame(m2aFrame(1uL))
        val second = writer.commitSegment()
        return first to second
    }
}
