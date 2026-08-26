package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import java.util.Collections

enum class CameraOutputRole {
    PREVIEW,
    RAW,
}

enum class CameraRequestLifetime {
    REPEATING,
    ONE_SHOT,
    BOUNDED_BURST,
    CONTINUOUS_SENSOR,
}

data class CameraOutputBinding(
    val role: CameraOutputRole,
    val lifetime: CameraRequestLifetime,
) {
    init {
        require(role != CameraOutputRole.RAW || lifetime != CameraRequestLifetime.REPEATING) {
            "RAW output must use an explicit sensor transaction lifetime, never the preview lifetime"
        }
        require(role != CameraOutputRole.PREVIEW || lifetime == CameraRequestLifetime.REPEATING) {
            "Preview output remains a repeating-session binding"
        }
    }
}

/** Typed output plan consumed by the sole session owner; intentionally has no arbitrary constructor. */
class CameraSessionOutputPlan private constructor(
    val previewSurfaceIdentity: PreviewSurfaceIdentity,
    val captureToken: CaptureToken?,
    bindings: List<CameraOutputBinding>,
) {
    val bindings: List<CameraOutputBinding> = Collections.unmodifiableList(ArrayList(bindings))

    companion object {
        fun previewOnly(previewSurfaceIdentity: PreviewSurfaceIdentity) = CameraSessionOutputPlan(
            previewSurfaceIdentity = previewSurfaceIdentity,
            captureToken = null,
            bindings = listOf(
                CameraOutputBinding(CameraOutputRole.PREVIEW, CameraRequestLifetime.REPEATING),
            ),
        )

        fun temporaryRaw(
            previewSurfaceIdentity: PreviewSurfaceIdentity,
            captureToken: CaptureToken,
        ) = CameraSessionOutputPlan(
            previewSurfaceIdentity = previewSurfaceIdentity,
            captureToken = captureToken,
            bindings = listOf(
                CameraOutputBinding(CameraOutputRole.PREVIEW, CameraRequestLifetime.REPEATING),
                CameraOutputBinding(CameraOutputRole.RAW, CameraRequestLifetime.ONE_SHOT),
            ),
        )

        fun temporaryRawBurst(
            previewSurfaceIdentity: PreviewSurfaceIdentity,
            captureToken: CaptureToken,
        ) = CameraSessionOutputPlan(
            previewSurfaceIdentity = previewSurfaceIdentity,
            captureToken = captureToken,
            bindings = listOf(
                CameraOutputBinding(CameraOutputRole.PREVIEW, CameraRequestLifetime.REPEATING),
                CameraOutputBinding(CameraOutputRole.RAW, CameraRequestLifetime.BOUNDED_BURST),
            ),
        )

        fun continuousRawVideo(
            previewSurfaceIdentity: PreviewSurfaceIdentity,
            captureToken: CaptureToken,
        ) = CameraSessionOutputPlan(
            previewSurfaceIdentity = previewSurfaceIdentity,
            captureToken = captureToken,
            bindings = listOf(
                CameraOutputBinding(CameraOutputRole.PREVIEW, CameraRequestLifetime.REPEATING),
                CameraOutputBinding(CameraOutputRole.RAW, CameraRequestLifetime.CONTINUOUS_SENSOR),
            ),
        )
    }
}
