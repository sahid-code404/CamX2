package com.sahidcode404.camx.core.camera.acquisition

import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.RawCaptureContext

/**
 * Pure bridge from the accepted CAMX-108 one-shot RAW context into the M1 immutable identity model.
 * It snapshots historical capture identity and performs no live topology or UI lookup.
 */
fun RawCaptureContext.toAcquisitionPermitIdentity(
    providerEpoch: Long,
    physicalTarget: PhysicalCameraId? = null,
    captureGeneration: Long? = null,
    surfaceGeneration: Long? = null,
): AcquisitionPermitIdentity = AcquisitionPermitIdentity(
    canonicalLensFingerprint = canonicalLensFingerprint,
    cameraProfileFingerprint = cameraProfileFingerprint,
    routeId = routeId,
    physicalTarget = physicalTarget,
    providerEpoch = providerEpoch,
    selectionGeneration = selectionGeneration,
    sessionGeneration = sessionGeneration,
    captureToken = captureToken,
    captureGeneration = captureGeneration,
    surfaceGeneration = surfaceGeneration,
)

fun RawCaptureContext.toAcquisitionIdentity(
    providerEpoch: Long,
    representation: RepresentationDescriptor,
    timebase: TimebaseEvidence,
    physicalTarget: PhysicalCameraId? = null,
    captureGeneration: Long? = null,
    surfaceGeneration: Long? = null,
): AcquisitionIdentity = AcquisitionIdentity(
    canonicalLensFingerprint = canonicalLensFingerprint,
    cameraProfileFingerprint = cameraProfileFingerprint,
    routeId = routeId,
    physicalTarget = physicalTarget,
    providerEpoch = providerEpoch,
    selectionGeneration = selectionGeneration,
    sessionGeneration = sessionGeneration,
    captureToken = captureToken,
    captureGeneration = captureGeneration,
    surfaceGeneration = surfaceGeneration,
    representation = representation,
    timebase = timebase,
)
