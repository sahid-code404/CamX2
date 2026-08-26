package com.sahidcode404.camx.core.camera.model

enum class PreviewStreamType {
    AUTO,
    CAMERA2_PRIVATE,
    CAMERA2_YUV_420_888,
}

data class PreviewFpsRequest(
    val overrideEnabled: Boolean,
    val requestedMinimum: Int,
    val requestedMaximum: Int,
)

enum class PreviewFpsFallbackReason {
    OVERRIDE_DISABLED,
    INVALID_REQUEST,
    NO_REPORTED_RANGES,
    EXACT_MATCH,
    NEAREST_SUPPORTED_RANGE,
    STREAM_CADENCE_LIMIT,
}

data class PreviewFpsResolution(
    val request: PreviewFpsRequest,
    val resolvedRange: CameraFpsCapability?,
    val reason: PreviewFpsFallbackReason,
)

enum class PreviewConfigurationAttemptKind {
    REQUESTED,
    SAFE_BASELINE,
}

data class PreviewConfiguration(
    val streamType: PreviewStreamType,
    val size: IntSize,
    val fps: PreviewFpsResolution,
    val highResolutionViewfinder: Boolean,
    val signature: String,
) {
    init {
        require(streamType != PreviewStreamType.AUTO) {
            "Resolved preview configuration must use a concrete stream type"
        }
        require(signature.isNotBlank()) { "Preview configuration signature cannot be blank" }
    }
}

enum class DisplayRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270),
}

data class PreviewGeometryInput(
    val viewSize: IntSize,
    val streamSize: IntSize,
    val sensorOrientationDegrees: Int,
    val displayRotation: DisplayRotation,
    val lensFacing: LensFacing,
    val mirrorFrontPreview: Boolean,
)

data class PreviewGeometry(
    val clockwiseRotationDegrees: Int,
    val scale: Float,
    val translatedX: Float,
    val translatedY: Float,
    val mirrorHorizontally: Boolean,
)
