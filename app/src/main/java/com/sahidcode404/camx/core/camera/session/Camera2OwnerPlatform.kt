package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.settings.SettingsSnapshot

/** Opaque test seam; raw CameraDevice/CameraCaptureSession types never cross this file boundary. */
internal interface CameraDeviceHandle {
    fun close()
}

internal interface CameraCaptureSessionHandle {
    fun close()
}

internal interface PreparedPreviewRequest

internal interface CameraOpenCallbacks {
    fun onOpened(delivery: CloseOnceCameraResource<CameraDeviceHandle>)
    fun onDisconnected(delivery: CloseOnceCameraResource<CameraDeviceHandle>)
    fun onError(delivery: CloseOnceCameraResource<CameraDeviceHandle>, platformCode: Int)
}

internal interface CameraSessionCallbacks {
    fun onConfigured(
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
        request: PreparedPreviewRequest,
    )

    fun onConfigureFailed(delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>)
}

internal interface CameraOwnerPlatform {
    fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks)

    fun configurePreview(
        device: CameraDeviceHandle,
        surfaceToken: Any,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: CameraSessionCallbacks,
    )

    /**
     * Typed physical-output extension of the frozen CAMX-103 preview seam.
     * Existing direct-preview fakes remain source-compatible through the default implementation.
     */
    fun configurePreviewTargeted(
        device: CameraDeviceHandle,
        surfaceToken: Any,
        physicalCameraId: PhysicalCameraId?,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
        attempt: PreviewConfigurationAttemptKind,
        callbacks: CameraSessionCallbacks,
    ) = configurePreview(
        device = device,
        surfaceToken = surfaceToken,
        configuration = configuration,
        settings = settings,
        attempt = attempt,
        callbacks = callbacks,
    )

    fun startRepeating(
        session: CameraCaptureSessionHandle,
        request: PreparedPreviewRequest,
        onFrame: () -> Unit,
    )
}
