package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import kotlin.math.abs

/** Matches the existing bounded active-profile/cache capability contract without importing cache code. */
internal const val MAX_PREVIEW_FPS_RANGES = 64

/**
 * Pure advertised-range resolver. Nearest selection minimizes the sum of minimum/maximum endpoint
 * distances; ties prefer the closer maximum endpoint, then higher maximum, then higher minimum.
 * Known stream cadence filters ranges whose advertised maximum exceeds floor(1e9 / duration).
 */
object PreviewFpsResolver {
    fun resolve(
        request: PreviewFpsRequest,
        reportedRanges: List<CameraFpsCapability>,
        streamMinimumFrameDurationNs: Long?,
    ): PreviewFpsResolution {
        if (!request.overrideEnabled) return resolution(request, null, PreviewFpsFallbackReason.OVERRIDE_DISABLED)
        if (request.requestedMinimum <= 0 ||
            request.requestedMaximum <= 0 ||
            request.requestedMaximum < request.requestedMinimum ||
            reportedRanges.size > MAX_PREVIEW_FPS_RANGES
        ) {
            return resolution(request, null, PreviewFpsFallbackReason.INVALID_REQUEST)
        }
        if (reportedRanges.isEmpty()) {
            return resolution(request, null, PreviewFpsFallbackReason.NO_REPORTED_RANGES)
        }

        val uniqueRanges = reportedRanges.distinct().sortedWith(RANGE_ORDER)
        val maximumStreamFps = streamMaximumFps(streamMinimumFrameDurationNs)
        val cadenceCompatible = if (maximumStreamFps == null) {
            uniqueRanges
        } else {
            uniqueRanges.filter { it.maximum.toLong() <= maximumStreamFps }
        }
        if (cadenceCompatible.isEmpty()) {
            return resolution(request, null, PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT)
        }

        cadenceCompatible.firstOrNull {
            it.minimum == request.requestedMinimum && it.maximum == request.requestedMaximum
        }?.let { exact ->
            return resolution(request, exact, PreviewFpsFallbackReason.EXACT_MATCH)
        }

        val closestCompatible = nearest(request, cadenceCompatible)
        val closestWithoutCadenceLimit = if (maximumStreamFps == null) {
            closestCompatible
        } else {
            nearest(request, uniqueRanges)
        }
        val cadenceMateriallyChangedSelection = maximumStreamFps != null &&
            closestWithoutCadenceLimit !in cadenceCompatible
        return resolution(
            request,
            closestCompatible,
            if (cadenceMateriallyChangedSelection) {
                PreviewFpsFallbackReason.STREAM_CADENCE_LIMIT
            } else {
                PreviewFpsFallbackReason.NEAREST_SUPPORTED_RANGE
            },
        )
    }

    private fun streamMaximumFps(minimumFrameDurationNs: Long?): Long? =
        minimumFrameDurationNs
            ?.takeIf { it > 0L }
            ?.let { NANOSECONDS_PER_SECOND / it }

    private fun nearest(
        request: PreviewFpsRequest,
        ranges: List<CameraFpsCapability>,
    ): CameraFpsCapability = ranges.minWith(
        compareBy<CameraFpsCapability>(
            { endpointDistance(it, request) },
            { maximumDistance(it, request) },
            { -it.maximum.toLong() },
            { -it.minimum.toLong() },
        ),
    )

    private fun endpointDistance(range: CameraFpsCapability, request: PreviewFpsRequest): Long =
        abs(range.minimum.toLong() - request.requestedMinimum.toLong()) +
            abs(range.maximum.toLong() - request.requestedMaximum.toLong())

    private fun maximumDistance(range: CameraFpsCapability, request: PreviewFpsRequest): Long =
        abs(range.maximum.toLong() - request.requestedMaximum.toLong())

    private fun resolution(
        request: PreviewFpsRequest,
        range: CameraFpsCapability?,
        reason: PreviewFpsFallbackReason,
    ) = PreviewFpsResolution(request, range, reason)

    private val RANGE_ORDER = compareBy<CameraFpsCapability>({ it.minimum }, { it.maximum })
    private const val NANOSECONDS_PER_SECOND = 1_000_000_000L
}
