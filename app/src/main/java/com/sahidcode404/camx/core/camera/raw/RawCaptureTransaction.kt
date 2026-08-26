package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.model.RawCaptureContext

sealed interface RawCaptureOutcome {
    data class Saved(val contentUri: String, val byteCount: Long) : RawCaptureOutcome
    data class Failed(val failure: CameraFailure) : RawCaptureOutcome
    data object Cancelled : RawCaptureOutcome
}

interface RawSessionLease : AutoCloseable {
    val context: RawCaptureContext
}

/**
 * Session owner creates and revokes the lease. Implementations may not open CameraDevice and must
 * destroy every transaction resource before completing.
 */
fun interface RawCaptureTransaction {
    suspend fun execute(lease: RawSessionLease): RawCaptureOutcome
}
