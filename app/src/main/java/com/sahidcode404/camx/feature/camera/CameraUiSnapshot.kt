package com.sahidcode404.camx.feature.camera

import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint

data class CameraUiSnapshot(
    val selectedLens: CanonicalLensFingerprint? = null,
    val visibleLensCount: Int = 0,
    val captureEnabled: Boolean = false,
    val captureInProgress: Boolean = false,
    val currentErrorLabel: String? = null,
    val updateAvailable: Boolean = false,
)
