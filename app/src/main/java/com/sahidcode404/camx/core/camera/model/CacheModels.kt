package com.sahidcode404.camx.core.camera.model

object CameraSchemaVersions {
    const val HOT_START = 1
    // PARITY-4: v1 topology was produced by pre-CameX-parity canonicalization and must never seed UI.
    const val TOPOLOGY = 2
    const val DEEP_DISCOVERY = 1
    const val LENS_REFERENCE = 1
}

data class StableLensReferenceSnapshot(
    val schema: Int,
    val environment: CameraEnvironmentFingerprint,
    val canonicalFingerprint: CanonicalLensFingerprint,
) {
    init { require(schema > 0) { "Lens-reference schema must be positive" } }
}

data class HotStartSnapshot(
    val schema: Int,
    val environment: CameraEnvironmentFingerprint,
    val selectedCanonicalFingerprint: CanonicalLensFingerprint,
    val selectedProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val openCameraId: CameraTransportId,
    val physicalCameraId: PhysicalCameraId?,
    val previewConfiguration: PreviewConfiguration,
    val sensorOrientationDegrees: Int?,
    val facing: LensFacing,
    val routeTrust: CameraTrust,
    val previewTrust: PreviewTrust,
    val lastVerifiedElapsedRealtimeNs: Long,
) {
    init {
        require(schema > 0) { "Hot cache schema must be positive" }
        require(lastVerifiedElapsedRealtimeNs >= 0L) { "Verification timestamp cannot be negative" }
        require(sensorOrientationDegrees == null ||
            sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0
        ) { "Sensor orientation must be null or one of 0, 90, 180, or 270 degrees" }
    }
}
