package com.sahidcode404.camx.feature.camera

import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewRenderSpec
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewUiState

/**
 * Allows the verified outgoing presentation to remain visible while a switch is only releasing or
 * preparing. Once target presentation is applied, Opening/first-frame states stay covered so a stale
 * frame can never be exposed under target rotation, mirror, crop, or buffer geometry.
 */
internal fun shouldRevealPreviewSurface(
    state: VisiblePreviewUiState,
    render: VisiblePreviewRenderSpec?,
): Boolean {
    if (render == null) return false
    return when (state) {
        VisiblePreviewUiState.Starting,
        VisiblePreviewUiState.WaitingForSurface,
        -> true
        is VisiblePreviewUiState.Previewing -> state.firstFrameVerified
        is VisiblePreviewUiState.Opening,
        is VisiblePreviewUiState.Unavailable,
        is VisiblePreviewUiState.Error,
        VisiblePreviewUiState.WaitingForPermission,
        -> false
    }
}
