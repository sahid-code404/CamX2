package com.sahidcode404.camx.core.settings

import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewStreamType

data class SettingsSnapshot(
    val revision: Long = 0L,
    val previewStreamType: PreviewStreamType = PreviewStreamType.AUTO,
    val highResolutionViewfinder: Boolean = false,
    val fpsRequest: PreviewFpsRequest = PreviewFpsRequest(
        overrideEnabled = false,
        requestedMinimum = 30,
        requestedMaximum = 30,
    ),
) {
    init { require(revision >= 0L) { "Settings revision cannot be negative" } }
}
