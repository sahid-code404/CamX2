package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust

/** Whole-lens trust is aggregated across exact sibling profiles, never copied from one route. */
internal data class CanonicalLensTrustSummary(
    val metadataTrust: CameraTrust,
    val previewTrust: PreviewTrust,
    val rawTrust: RawTrust,
) {
    val structurallyUnavailable: Boolean =
        metadataTrust == CameraTrust.STRUCTURALLY_REJECTED ||
            previewTrust == PreviewTrust.STRUCTURALLY_REJECTED
}

internal object CanonicalLensTrustAggregator {
    fun aggregate(lens: CanonicalLens): CanonicalLensTrustSummary = CanonicalLensTrustSummary(
        metadataTrust = aggregateCamera(lens.profiles.map { it.route.metadataTrust }),
        previewTrust = aggregatePreview(lens.profiles.map { it.route.previewTrust }),
        rawTrust = aggregateRaw(lens.profiles.map { it.route.rawTrust }),
    )

    private fun aggregateCamera(values: List<CameraTrust>): CameraTrust = when {
        values.any { it == CameraTrust.VERIFIED } -> CameraTrust.VERIFIED
        values.all { it == CameraTrust.STRUCTURALLY_REJECTED } -> CameraTrust.STRUCTURALLY_REJECTED
        values.any { it == CameraTrust.ADVERTISED } -> CameraTrust.ADVERTISED
        values.any { it == CameraTrust.TEMPORARILY_UNAVAILABLE } -> CameraTrust.TEMPORARILY_UNAVAILABLE
        else -> CameraTrust.UNKNOWN
    }

    private fun aggregatePreview(values: List<PreviewTrust>): PreviewTrust = when {
        values.any { it == PreviewTrust.VERIFIED } -> PreviewTrust.VERIFIED
        values.all { it == PreviewTrust.STRUCTURALLY_REJECTED } -> PreviewTrust.STRUCTURALLY_REJECTED
        values.any { it == PreviewTrust.ADVERTISED } -> PreviewTrust.ADVERTISED
        values.any { it == PreviewTrust.TEMPORARILY_UNAVAILABLE } -> PreviewTrust.TEMPORARILY_UNAVAILABLE
        else -> PreviewTrust.UNKNOWN
    }

    private fun aggregateRaw(values: List<RawTrust>): RawTrust = when {
        values.any { it == RawTrust.VERIFIED } -> RawTrust.VERIFIED
        values.all { it == RawTrust.STRUCTURALLY_REJECTED } -> RawTrust.STRUCTURALLY_REJECTED
        values.any { it == RawTrust.ADVERTISED } -> RawTrust.ADVERTISED
        values.any { it == RawTrust.TEMPORARILY_UNAVAILABLE } -> RawTrust.TEMPORARILY_UNAVAILABLE
        else -> RawTrust.UNKNOWN
    }
}
