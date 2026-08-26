package com.sahidcode404.camx.core.camera.model

@JvmInline
value class CameraRouteId(val value: String) {
    init { require(value.isNotBlank()) { "CameraRouteId cannot be blank" } }
}

@JvmInline
value class CameraTransportId(val value: String) {
    init { require(value.isNotBlank()) { "Camera transport ID cannot be blank" } }
}

@JvmInline
value class PhysicalCameraId(val value: String) {
    init { require(value.isNotBlank()) { "Physical camera ID cannot be blank" } }
}

@JvmInline
value class CanonicalLensFingerprint(val value: String) {
    init { require(value.isNotBlank()) { "Canonical fingerprint cannot be blank" } }
}

@JvmInline
value class CameraProfileFingerprint(val value: String) {
    init { require(value.isNotBlank()) { "Profile fingerprint cannot be blank" } }
}

@JvmInline
value class CameraEnvironmentFingerprint(val value: String) {
    init { require(value.isNotBlank()) { "Environment fingerprint cannot be blank" } }
}

@JvmInline
value class SelectionGeneration(val value: Long) {
    init { require(value >= 0L) { "Selection generation cannot be negative" } }
}

@JvmInline
value class SessionGeneration(val value: Long) {
    init { require(value >= 0L) { "Session generation cannot be negative" } }
}

@JvmInline
value class CaptureToken(val value: Long) {
    init { require(value > 0L) { "Capture token must be positive" } }
}
