package com.sahidcode404.camx.core.camera.model

object RawContractLimits {
    const val MINIMUM_TIMEOUT_MILLIS = 1L
    const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    const val MAXIMUM_TIMEOUT_MILLIS = 60_000L
}

data class RawCaptureContext(
    val captureToken: CaptureToken,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val displayRotationAtShutter: DisplayRotation,
    val sensorOrientationDegrees: Int,
    val lensFacing: LensFacing,
    val rawSize: IntSize,
    val timeoutMillis: Long,
) {
    init {
        require(timeoutMillis in RawContractLimits.MINIMUM_TIMEOUT_MILLIS..
            RawContractLimits.MAXIMUM_TIMEOUT_MILLIS
        ) {
            "RAW timeout must be between ${RawContractLimits.MINIMUM_TIMEOUT_MILLIS} and " +
                "${RawContractLimits.MAXIMUM_TIMEOUT_MILLIS} milliseconds"
        }
        require(sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0) {
            "Sensor orientation must be one of 0, 90, 180, or 270 degrees"
        }
    }
}

/**
 * One-time ownership handoff for a paired RAW image/result. The pair owns the image until
 * [takeImage] succeeds; otherwise [close] releases it. This type is intentionally non-copyable.
 */
class RawPair<I : AutoCloseable, R : Any>(
    val timestampNs: Long,
    image: I,
    val result: R,
) : AutoCloseable {
    private var ownedImage: I? = image

    init { require(timestampNs > 0L) { "Sensor timestamp must be positive" } }

    @Synchronized
    fun takeImage(): I = checkNotNull(ownedImage) { "RAW image ownership already transferred" }
        .also { ownedImage = null }

    @Synchronized
    override fun close() {
        val imageToClose = ownedImage ?: return
        ownedImage = null
        runCatching { imageToClose.close() }
    }
}
