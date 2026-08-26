package com.sahidcode404.camx.core.imaging.graph

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
import com.sahidcode404.camx.core.camera.acquisition.SourceManifestRecord
import com.sahidcode404.camx.core.camera.acquisition.TimebaseEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration

data class M3SourceEvidence(
    val record: SourceManifestRecord,
    val payload: ByteArray,
    val sourceValue: GraphValue,
)

internal fun m3SourceEvidence(
    valueId: Int = 0,
    calibrationConfidence: Double = 1.0,
    captureToken: Long = 7L,
    payload: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
): M3SourceEvidence {
    require(payload.size == 8)
    val descriptor = RepresentationDescriptor(
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
                offsetBytes = 0L,
                rowStrideBytes = 4L,
                meaningfulRowBytes = 4L,
                rowCount = 2,
                pixelStrideBytes = 2,
            ),
        ),
        cfaPattern = CfaPattern.RGGB,
        sensorPixelMode = SensorPixelMode.DEFAULT,
        colorCalibrationIdentity = if (calibrationConfidence > 0.0) "m3-color" else null,
        calibration = if (calibrationConfidence > 0.0) {
            CalibrationEvidence("m3-calibration", "v1", calibrationConfidence)
        } else {
            CalibrationEvidence(null, null, 0.0)
        },
        sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
    )
    val identity = AcquisitionIdentity(
        canonicalLensFingerprint = CanonicalLensFingerprint("m3-lens"),
        cameraProfileFingerprint = CameraProfileFingerprint("m3-profile"),
        routeId = CameraRouteId("m3-route"),
        physicalTarget = PhysicalCameraId("m3-physical"),
        providerEpoch = 1L,
        selectionGeneration = SelectionGeneration(1L),
        sessionGeneration = SessionGeneration(1L),
        captureToken = CaptureToken(captureToken),
        captureGeneration = 1L,
        surfaceGeneration = 1L,
        representation = descriptor,
        timebase = TimebaseEvidence(
            imageTimestampNs = 1_000L + captureToken,
            captureResultTimestampNs = 1_000L + captureToken,
            requestIssuedTimestampNs = 900L + captureToken,
            declaredTimebase = AcquisitionTimebase.SENSOR,
            normalizedOffsetNs = null,
            mappingUncertaintyNs = null,
        ),
    )
    val raster = CanonicalRasterDigest(sha256(payload), payload.size.toLong())
    val record = SourceManifestRecord.create(identity, raster)
    return M3SourceEvidence(
        record = record,
        payload = payload.copyOf(),
        sourceValue = GraphValue.source(GraphValueId(valueId), record),
    )
}

internal fun m3Budget(
    maxResidentBytes: Long = 1L * 1024L * 1024L,
    maxWorkspaceBytes: Long = 64L * 1024L,
    safetyMarginBytes: Long = 128L,
): GraphResourceBudget = GraphResourceBudget(
    maxResidentBytes = maxResidentBytes,
    maxWorkspaceBytes = maxWorkspaceBytes,
    safetyMarginBytes = safetyMarginBytes,
)

internal fun m3CopyNode(
    nodeId: Int,
    inputId: Int,
    outputId: Int,
    algorithmId: AlgorithmId = M3ReferenceAlgorithms.EXACT_COPY,
    parameters: List<NodeParameter> = emptyList(),
): GraphNodeInvocation = GraphNodeInvocation(
    id = GraphNodeId(nodeId),
    algorithmId = algorithmId,
    algorithmVersion = M3ReferenceAlgorithms.VERSION,
    parameterSchemaVersion = M3ReferenceAlgorithms.PARAMETER_SCHEMA_VERSION,
    inputs = listOf(GraphValueId(inputId)),
    outputs = listOf(GraphValueId(outputId)),
    parameters = parameters,
)

internal fun m3ForkNode(
    nodeId: Int,
    inputId: Int,
    outputIds: List<Int>,
): GraphNodeInvocation = GraphNodeInvocation(
    id = GraphNodeId(nodeId),
    algorithmId = M3ReferenceAlgorithms.EXACT_FORK,
    algorithmVersion = M3ReferenceAlgorithms.VERSION,
    parameterSchemaVersion = M3ReferenceAlgorithms.PARAMETER_SCHEMA_VERSION,
    inputs = listOf(GraphValueId(inputId)),
    outputs = outputIds.map(::GraphValueId),
)
