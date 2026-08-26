package com.sahidcode404.camx.core.camera.lens

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.PreviewTrust

/** Deterministic CameX-parity trust-first ranking for profiles that already passed eligibility. */
internal object LensProfileRanker {
    fun rank(
        candidates: Collection<LensSelectionTarget>,
        activeSelection: ActiveCameraSelection?,
        activeFirstFrameVerified: Boolean,
    ): List<LensSelectionTarget> = candidates.sortedWith(
        compareBy<LensSelectionTarget>(
            { target -> trustRank(target, activeSelection, activeFirstFrameVerified) },
            { target -> sourceRank(target) },
            { target -> target.profileFingerprint.value },
            { target -> target.routeId.value },
        ),
    )

    private fun trustRank(
        target: LensSelectionTarget,
        activeSelection: ActiveCameraSelection?,
        activeFirstFrameVerified: Boolean,
    ): Int = when {
        activeFirstFrameVerified && activeSelection?.profileFingerprint == target.profileFingerprint -> 0
        target.route.previewTrust == PreviewTrust.VERIFIED -> 1
        target.route.metadataTrust == CameraTrust.STRUCTURALLY_REJECTED ||
            target.route.previewTrust == PreviewTrust.STRUCTURALLY_REJECTED -> 4
        target.route.metadataTrust == CameraTrust.TEMPORARILY_UNAVAILABLE ||
            target.route.previewTrust == PreviewTrust.TEMPORARILY_UNAVAILABLE -> 3
        else -> 2
    }

    private fun sourceRank(target: LensSelectionTarget): Int = when {
        target.route.physicalCameraId == null && CameraRouteSource.JAVA_PUBLIC in target.route.sources -> 0
        target.route.physicalCameraId != null && CameraRouteSource.JAVA_PHYSICAL in target.route.sources -> 1
        target.route.physicalCameraId == null && CameraRouteSource.JAVA_DEEP_PROBED in target.route.sources -> 2
        else -> 3
    }
}
