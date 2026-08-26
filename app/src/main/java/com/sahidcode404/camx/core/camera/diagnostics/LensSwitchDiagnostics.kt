package com.sahidcode404.camx.core.camera.diagnostics

/** Bounded, sanitized switch timing/counter snapshot. All durations are relative to the latest user tap. */
data class LensSwitchDiagnostics(
    val tapToAcceptedMs: Long? = null,
    val tapToCleanupCompleteMs: Long? = null,
    val tapToOpenRequestedMs: Long? = null,
    val tapToCameraOpenedMs: Long? = null,
    val tapToSessionConfiguredMs: Long? = null,
    val tapToFirstFrameMs: Long? = null,
    val tapToPreviewVerifiedMs: Long? = null,
    val supersededIntentCount: Long = 0L,
    val actualOpenCount: Long = 0L,
    val transientRetryCount: Long = 0L,
    val fallbackToLastVerifiedCount: Long = 0L,
)
