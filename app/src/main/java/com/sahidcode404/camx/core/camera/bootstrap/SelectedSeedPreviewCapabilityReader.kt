package com.sahidcode404.camx.core.camera.bootstrap

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.SurfaceHolder
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.util.Collections

internal const val VISIBLE_PREVIEW_MAX_STREAMS = 128
internal const val VISIBLE_PREVIEW_MAX_FPS_RANGES = 64

enum class SelectedSeedCapabilityFailure {
    CHARACTERISTICS_UNAVAILABLE,
    STREAM_MAP_UNAVAILABLE,
    PREVIEW_STREAM_LIMIT_EXCEEDED,
    FPS_RANGE_LIMIT_EXCEEDED,
    NO_PRIVATE_PREVIEW_STREAMS,
    SENSOR_ORIENTATION_UNAVAILABLE,
    INVALID_METADATA,
}

data class SelectedSeedPreviewCapabilities(
    val capabilities: CameraCapabilities,
    val sensorOrientationDegrees: Int,
    val lensFacing: LensFacing,
)

sealed interface SelectedSeedCapabilityResult {
    data class Available(val value: SelectedSeedPreviewCapabilities) : SelectedSeedCapabilityResult
    data class Unavailable(val reason: SelectedSeedCapabilityFailure) : SelectedSeedCapabilityResult
}

fun interface SelectedSeedPreviewCapabilitySource {
    fun read(route: CameraRoute): SelectedSeedCapabilityResult
}

/**
 * Bounded public Camera2 metadata reader for one already-selected seed route. It never enumerates
 * additional cameras and never acquires a camera resource. Full physical/AUX reconciliation remains CAMX-107.
 */
class AndroidSelectedSeedPreviewCapabilityReader(
    private val cameraManager: CameraManager,
) : SelectedSeedPreviewCapabilitySource {
    override fun read(route: CameraRoute): SelectedSeedCapabilityResult {
        val characteristics = try {
            cameraManager.getCameraCharacteristics(route.openCameraId.value)
        } catch (_: Exception) {
            return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.CHARACTERISTICS_UNAVAILABLE,
            )
        }

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.STREAM_MAP_UNAVAILABLE,
            )
        val sizes = try {
            streamMap.getOutputSizes(SurfaceHolder::class.java)
        } catch (_: Exception) {
            null
        } ?: return SelectedSeedCapabilityResult.Unavailable(
            SelectedSeedCapabilityFailure.STREAM_MAP_UNAVAILABLE,
        )
        if (sizes.size > VISIBLE_PREVIEW_MAX_STREAMS) {
            return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.PREVIEW_STREAM_LIMIT_EXCEEDED,
            )
        }

        val reportedRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        if (reportedRanges.size > VISIBLE_PREVIEW_MAX_FPS_RANGES) {
            return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.FPS_RANGE_LIMIT_EXCEEDED,
            )
        }

        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.SENSOR_ORIENTATION_UNAVAILABLE,
            )
        if (sensorOrientation !in 0..270 || sensorOrientation % 90 != 0) {
            return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.INVALID_METADATA,
            )
        }

        val streams = sizes.asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .map { size ->
                val minimumDuration = try {
                    streamMap.getOutputMinFrameDuration(SurfaceHolder::class.java, size)
                } catch (_: Exception) {
                    0L
                }
                CameraStreamCapability(
                    type = PreviewStreamType.CAMERA2_PRIVATE,
                    size = IntSize(size.width, size.height),
                    minimumFrameDurationNs = minimumDuration.takeIf { it > 0L },
                )
            }
            .distinctBy { Triple(it.size.width, it.size.height, it.minimumFrameDurationNs) }
            .sortedWith(compareBy({ it.size.area }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs ?: Long.MAX_VALUE }))
            .toList()
        if (streams.isEmpty()) {
            return SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.NO_PRIVATE_PREVIEW_STREAMS,
            )
        }

        val fpsRanges = reportedRanges.asSequence()
            .filter { it.lower > 0 && it.upper >= it.lower }
            .map { CameraFpsCapability(it.lower, it.upper) }
            .distinct()
            .sortedWith(compareBy(CameraFpsCapability::minimum, CameraFpsCapability::maximum))
            .toList()

        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
            else -> LensFacing.UNKNOWN
        }
        return SelectedSeedCapabilityResult.Available(
            SelectedSeedPreviewCapabilities(
                capabilities = CameraCapabilities(
                    previewStreams = immutableList(streams),
                    fpsRanges = immutableList(fpsRanges),
                ),
                sensorOrientationDegrees = sensorOrientation,
                lensFacing = facing,
            ),
        )
    }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
