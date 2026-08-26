package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust

internal val TEST_ENVIRONMENT = CameraEnvironmentFingerprint("environment:test")

internal fun hotSnapshot(
    verifiedAt: Long = 7L,
    environment: CameraEnvironmentFingerprint = TEST_ENVIRONMENT,
) = HotStartSnapshot(
    schema = CameraSchemaVersions.HOT_START,
    environment = environment,
    selectedCanonicalFingerprint = CanonicalLensFingerprint("lens:back"),
    selectedProfileFingerprint = CameraProfileFingerprint("profile:back"),
    routeId = CameraRouteId("route:back"),
    openCameraId = CameraTransportId("opaque-back"),
    physicalCameraId = PhysicalCameraId("physical-back"),
    previewConfiguration = PreviewConfiguration(
        streamType = PreviewStreamType.CAMERA2_PRIVATE,
        size = IntSize(1920, 1080),
        fps = PreviewFpsResolution(
            request = PreviewFpsRequest(true, 24, 30),
            resolvedRange = CameraFpsCapability(24, 30),
            reason = PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE,
        ),
        highResolutionViewfinder = true,
        signature = "preview:1920x1080:24-30",
    ),
    sensorOrientationDegrees = 90,
    facing = LensFacing.BACK,
    routeTrust = CameraTrust.VERIFIED,
    previewTrust = PreviewTrust.VERIFIED,
    lastVerifiedElapsedRealtimeNs = verifiedAt,
)

internal fun emptyTopology(
    environment: CameraEnvironmentFingerprint = TEST_ENVIRONMENT,
) = CameraTopologySnapshot(
    schema = CameraSchemaVersions.TOPOLOGY,
    environment = environment,
    routes = emptyList(),
    canonicalLenses = emptyList(),
    generatedAtElapsedRealtimeNs = 11L,
    evidence = emptyList(),
)

internal fun representativeTopology(
    environment: CameraEnvironmentFingerprint = TEST_ENVIRONMENT,
): CameraTopologySnapshot {
    val backCapabilities = CameraCapabilities(
        previewStreams = listOf(
            CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(1920, 1080), 33_333_333L),
            CameraStreamCapability(PreviewStreamType.CAMERA2_YUV_420_888, IntSize(1280, 720), 16_666_667L),
        ),
        fpsRanges = listOf(CameraFpsCapability(15, 30), CameraFpsCapability(30, 60)),
        rawSizes = listOf(IntSize(4000, 3000)),
    )
    val frontCapabilities = CameraCapabilities(
        previewStreams = listOf(
            CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(1280, 720), null),
        ),
        fpsRanges = listOf(CameraFpsCapability(30, 30)),
    )
    val backRoute = CameraRoute(
        id = CameraRouteId("route:back"),
        source = CameraRouteSource.JAVA_PHYSICAL,
        openCameraId = CameraTransportId("logical-back"),
        physicalCameraId = PhysicalCameraId("physical-back"),
        capabilities = backCapabilities,
        metadataTrust = CameraTrust.VERIFIED,
        previewTrust = PreviewTrust.VERIFIED,
        rawTrust = RawTrust.ADVERTISED,
        sources = linkedSetOf(CameraRouteSource.JAVA_PHYSICAL, CameraRouteSource.JAVA_PUBLIC),
    )
    val frontRoute = CameraRoute(
        id = CameraRouteId("route:front"),
        source = CameraRouteSource.JAVA_PUBLIC,
        openCameraId = CameraTransportId("opaque-front"),
        capabilities = frontCapabilities,
        metadataTrust = CameraTrust.ADVERTISED,
        previewTrust = PreviewTrust.ADVERTISED,
        rawTrust = RawTrust.UNKNOWN,
    )
    val backFingerprint = CanonicalLensFingerprint("lens:back")
    val frontFingerprint = CanonicalLensFingerprint("lens:front")
    return CameraTopologySnapshot(
        schema = CameraSchemaVersions.TOPOLOGY,
        environment = environment,
        routes = listOf(backRoute, frontRoute),
        canonicalLenses = listOf(
            CanonicalLens(
                backFingerprint,
                LensFacing.BACK,
                listOf(CameraProfile(CameraProfileFingerprint("profile:back"), backFingerprint, backRoute)),
            ),
            CanonicalLens(
                frontFingerprint,
                LensFacing.FRONT,
                listOf(CameraProfile(CameraProfileFingerprint("profile:front"), frontFingerprint, frontRoute)),
            ),
        ),
        generatedAtElapsedRealtimeNs = 99L,
        evidence = listOf(
            CameraMetadataEvidence(
                source = CameraRouteSource.JAVA_PHYSICAL,
                transportId = CameraTransportId("logical-back"),
                physicalId = PhysicalCameraId("physical-back"),
                logicalParentId = CameraTransportId("logical-back"),
                facing = LensFacing.BACK,
                focalLengthsMillimetres = listOf(4.25f, 6.5f),
                sensorPhysicalWidthMillimetres = 5.6f,
                sensorPhysicalHeightMillimetres = 4.2f,
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4032, 3024),
                sensorOrientationDegrees = 90,
                apertureValues = listOf(1.8f, 2.4f),
                colorFilterArrangement = 1,
                capabilities = backCapabilities,
            ),
        ),
    )
}
