package com.sahidcode404.camx.core.camera.discovery

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.view.SurfaceHolder
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.LensFacing
import java.util.Collections

/** Public Camera2 metadata adapter for CAMX-102. No device/session ownership API is reachable here. */
class AndroidFirstInstallSeedDiscovery(
    cameraManager: CameraManager,
    environment: CameraEnvironmentFingerprint,
) {
    private val delegate = MinimalFirstInstallSeedDiscovery(
        source = AndroidPublicCameraSeedSource(cameraManager),
        environment = environment,
        elapsedRealtimeNs = SystemClock::elapsedRealtimeNanos,
    )

    suspend fun discover(): SeedDiscoveryResult = delegate.discover()
}

internal class AndroidPublicCameraSeedSource(
    private val cameraManager: CameraManager,
) : PublicCameraSeedMetadataSource {
    override fun advertisedCameraIds(): List<String> = cameraManager.cameraIdList.toList()

    override fun readSeedEvidence(transportId: CameraTransportId): SeedCameraEvidence {
        val characteristics = cameraManager.getCameraCharacteristics(transportId.value)
        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
            else -> LensFacing.UNKNOWN
        }
        val focalLengths = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.asSequence()
            ?.filter { it.isFinite() && it > 0f }
            ?.distinct()
            ?.sorted()
            ?.take(SEED_MAX_FOCAL_LENGTHS)
            ?.toList()
            .orEmpty()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val validPhysicalSize = physicalSize?.takeIf {
            it.width.isFinite() && it.width > 0f && it.height.isFinite() && it.height > 0f
        }
        val requestCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val backwardCompatible = requestCapabilities?.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
        }
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val hasPrivatePreviewOutput = streamMap
            ?.getOutputSizes(SurfaceHolder::class.java)
            ?.isNotEmpty() == true

        return SeedCameraEvidence(
            metadata = CameraMetadataEvidence(
                source = CameraRouteSource.JAVA_PUBLIC,
                transportId = transportId,
                facing = facing,
                focalLengthsMillimetres = immutableList(focalLengths),
                sensorPhysicalWidthMillimetres = validPhysicalSize?.width,
                sensorPhysicalHeightMillimetres = validPhysicalSize?.height,
                capabilities = CameraCapabilities(),
            ),
            privatePreviewOutputAdvertised = hasPrivatePreviewOutput,
            backwardCompatibleAdvertised = backwardCompatible,
        )
    }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
