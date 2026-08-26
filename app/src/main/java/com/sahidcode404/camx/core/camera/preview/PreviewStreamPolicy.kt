package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.model.PreviewGeometryInput
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

internal const val MAX_PREVIEW_POLICY_CANDIDATES = 128

/** Pure CAMX-104 request. All dimensions and capabilities are immutable evidence supplied by callers. */
data class PreviewPolicyInput(
    val capabilities: CameraCapabilities,
    val viewSize: IntSize,
    val sensorOrientationDegrees: Int,
    val displayRotation: DisplayRotation,
    val lensFacing: LensFacing,
    val mirrorFrontPreview: Boolean,
    val requestedStreamType: PreviewStreamType,
    val highResolutionViewfinder: Boolean,
    val fpsRequest: PreviewFpsRequest,
)

enum class PreviewUnsupportedReason {
    INVALID_SENSOR_ORIENTATION,
    CAPABILITY_LIMIT_EXCEEDED,
    NO_ADVERTISED_STREAMS,
    REQUESTED_STREAM_TYPE_UNAVAILABLE,
    INVALID_FPS_REQUEST,
    NO_REPORTED_FPS_RANGES,
    NO_CADENCE_COMPATIBLE_STREAM,
}

enum class PreviewStreamSelectionReason {
    RESPONSIVE,
    HIGH_RESOLUTION_TARGET,
    HIGH_RESOLUTION_BEST_AVAILABLE,
}

sealed interface PreviewPolicyResult {
    data class Supported(
        val configuration: PreviewConfiguration,
        val geometry: PreviewGeometry,
        val selectionReason: PreviewStreamSelectionReason,
    ) : PreviewPolicyResult

    data class Unsupported(val reason: PreviewUnsupportedReason) : PreviewPolicyResult
}

object PreviewStreamPolicy {
    fun resolve(input: PreviewPolicyInput): PreviewPolicyResult {
        if (!isOrthogonalOrientation(input.sensorOrientationDegrees)) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.INVALID_SENSOR_ORIENTATION)
        }
        val streams = input.capabilities.previewStreams
        if (streams.size > MAX_PREVIEW_POLICY_CANDIDATES) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.CAPABILITY_LIMIT_EXCEEDED)
        }
        if (streams.isEmpty()) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.NO_ADVERTISED_STREAMS)
        }
        if (input.fpsRequest.overrideEnabled &&
            (input.fpsRequest.requestedMinimum <= 0 ||
                input.fpsRequest.requestedMaximum < input.fpsRequest.requestedMinimum)
        ) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.INVALID_FPS_REQUEST)
        }
        if (input.fpsRequest.overrideEnabled && input.capabilities.fpsRanges.isEmpty()) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.NO_REPORTED_FPS_RANGES)
        }

        val typeFiltered = streams.filter { candidate ->
            input.requestedStreamType == PreviewStreamType.AUTO || candidate.type == input.requestedStreamType
        }
        if (typeFiltered.isEmpty()) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.REQUESTED_STREAM_TYPE_UNAVAILABLE)
        }

        val rotation = PreviewGeometryCalculator.rotationDegrees(
            input.sensorOrientationDegrees,
            input.displayRotation,
            input.lensFacing,
        )
        val scored = ArrayList<ScoredCandidate>(typeFiltered.size)
        for (candidate in typeFiltered) {
            val fps = PreviewFpsResolver.resolve(
                input.fpsRequest,
                input.capabilities.fpsRanges,
                candidate.minimumFrameDurationNs,
            )
            if (input.fpsRequest.overrideEnabled && fps.resolvedRange == null) continue
            scored += scoreCandidate(input, candidate, fps, rotation)
        }
        if (scored.isEmpty()) {
            return PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.NO_CADENCE_COMPATIBLE_STREAM)
        }

        val selected = scored.minWith(candidateComparator(input))
        val configuration = PreviewConfiguration(
            streamType = selected.capability.type,
            size = selected.capability.size,
            fps = selected.fps,
            highResolutionViewfinder = input.highResolutionViewfinder,
            signature = signature(input, selected.capability, selected.fps),
        )
        val geometry = PreviewGeometryCalculator.calculate(
            PreviewGeometryInput(
                viewSize = input.viewSize,
                streamSize = selected.capability.size,
                sensorOrientationDegrees = input.sensorOrientationDegrees,
                displayRotation = input.displayRotation,
                lensFacing = input.lensFacing,
                mirrorFrontPreview = input.mirrorFrontPreview,
            ),
        )
        return PreviewPolicyResult.Supported(
            configuration = configuration,
            geometry = geometry,
            selectionReason = selectionReason(input, selected),
        )
    }

    private fun scoreCandidate(
        input: PreviewPolicyInput,
        capability: CameraStreamCapability,
        fps: PreviewFpsResolution,
        rotation: Int,
    ): ScoredCandidate {
        val swapAxes = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapAxes) capability.size.height else capability.size.width
        val rotatedHeight = if (swapAxes) capability.size.width else capability.size.height
        val fillScale = max(
            input.viewSize.width.toDouble() / rotatedWidth.toDouble(),
            input.viewSize.height.toDouble() / rotatedHeight.toDouble(),
        )
        val effectiveSourceRatio = 1.0 / (fillScale * fillScale)
        val targetRatio = if (input.highResolutionViewfinder) HIGH_RES_EFFECTIVE_PIXEL_RATIO else 1.0
        val targetDistance = abs(ln(effectiveSourceRatio / targetRatio))
        val candidateArea = rotatedWidth.toDouble() * rotatedHeight.toDouble()
        val effectiveArea = input.viewSize.area.toDouble() * effectiveSourceRatio
        val cropRetention = (effectiveArea / candidateArea).coerceIn(0.0, 1.0)
        return ScoredCandidate(
            capability = capability,
            fps = fps,
            cadenceRank = cadenceRank(input.fpsRequest, capability.minimumFrameDurationNs, fps),
            typeRank = typeRank(input.requestedStreamType, capability.type),
            targetDistance = targetDistance,
            cropLoss = 1.0 - cropRetention,
            effectiveSourceRatio = effectiveSourceRatio,
        )
    }

    private fun candidateComparator(input: PreviewPolicyInput): Comparator<ScoredCandidate> =
        compareBy<ScoredCandidate>(
            { it.cadenceRank },
            { it.typeRank },
            { it.targetDistance },
            { it.cropLoss },
            { if (input.highResolutionViewfinder) -it.effectiveSourceRatio else it.effectiveSourceRatio },
            { it.capability.size.area },
            { it.capability.size.width },
            { it.capability.size.height },
            { it.capability.type.ordinal },
            { it.capability.minimumFrameDurationNs ?: Long.MAX_VALUE },
        )

    private fun cadenceRank(
        request: PreviewFpsRequest,
        minimumFrameDurationNs: Long?,
        fps: PreviewFpsResolution,
    ): Int {
        if (!request.overrideEnabled) return 0
        val knownMaximum = minimumFrameDurationNs
            ?.takeIf { it > 0L }
            ?.let { 1_000_000_000L / it }
        val durationRank = when {
            knownMaximum == null -> 1
            knownMaximum >= request.requestedMaximum.toLong() -> 0
            else -> 2
        }
        val resolutionRank = when (fps.reason) {
            PreviewFpsFallbackReason.EXACT_MATCH -> 0
            PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE -> 2
            PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT -> 4
            PreviewFpsFallbackReason.OVERRIDE_DISABLED -> 0
            PreviewFpsFallbackReason.INVALID_REQUEST,
            PreviewFpsFallbackReason.NO_REPORTED_RANGES,
            -> 6
        }
        return resolutionRank + durationRank
    }

    private fun typeRank(requested: PreviewStreamType, candidate: PreviewStreamType): Int {
        if (requested != PreviewStreamType.AUTO) return 0
        return when (candidate) {
            PreviewStreamType.CAMERA2_PRIVATE -> 0
            PreviewStreamType.CAMERA2_YUV_420_888 -> 1
            PreviewStreamType.AUTO -> 2
        }
    }

    private fun selectionReason(
        input: PreviewPolicyInput,
        selected: ScoredCandidate,
    ): PreviewStreamSelectionReason {
        if (!input.highResolutionViewfinder) return PreviewStreamSelectionReason.RESPONSIVE
        return if (selected.effectiveSourceRatio >= HIGH_RES_MINIMUM_EFFECTIVE_PIXEL_RATIO) {
            PreviewStreamSelectionReason.HIGH_RESOLUTION_TARGET
        } else {
            PreviewStreamSelectionReason.HIGH_RESOLUTION_BEST_AVAILABLE
        }
    }

    private fun signature(
        input: PreviewPolicyInput,
        capability: CameraStreamCapability,
        fps: PreviewFpsResolution,
    ): String {
        val resolved = fps.resolvedRange?.let { "${it.minimum}-${it.maximum}" } ?: "none"
        val request = input.fpsRequest
        return buildString(192) {
            append("pv1")
            append(";requestedType=").append(input.requestedStreamType.name)
            append(";type=").append(capability.type.name)
            append(";size=").append(capability.size.width).append('x').append(capability.size.height)
            append(";highRes=").append(if (input.highResolutionViewfinder) 1 else 0)
            append(";fpsRequest=").append(if (request.overrideEnabled) 1 else 0)
                .append(',').append(request.requestedMinimum).append(',').append(request.requestedMaximum)
            append(";fpsResolved=").append(resolved)
            append(";fpsReason=").append(fps.reason.name)
        }
    }

    private fun isOrthogonalOrientation(value: Int): Boolean = value in 0..270 && value % 90 == 0

    private data class ScoredCandidate(
        val capability: CameraStreamCapability,
        val fps: PreviewFpsResolution,
        val cadenceRank: Int,
        val typeRank: Int,
        val targetDistance: Double,
        val cropLoss: Double,
        val effectiveSourceRatio: Double,
    )

    private const val HIGH_RES_EFFECTIVE_PIXEL_RATIO = 4.0
    private const val HIGH_RES_MINIMUM_EFFECTIVE_PIXEL_RATIO = 1.0
}
