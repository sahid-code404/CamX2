package com.sahidcode404.camx.core.camera.runtime

import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration

data class CameraGenerationSnapshot(
    val selection: SelectionGeneration,
    val session: SessionGeneration,
    val capture: CaptureToken?,
)

class CameraGenerationGate {
    private var selection = 0L
    private var session = 0L
    private var captureSequence = 0L
    private var activeCapture = NO_CAPTURE

    @Synchronized
    fun snapshot(): CameraGenerationSnapshot = CameraGenerationSnapshot(
        selection = SelectionGeneration(selection),
        session = SessionGeneration(session),
        capture = activeCapture.takeIf { it != NO_CAPTURE }?.let(::CaptureToken),
    )

    @Synchronized
    fun advanceSelection(): CameraGenerationSnapshot {
        selection = nextGeneration(selection)
        session = nextGeneration(session)
        activeCapture = NO_CAPTURE
        return snapshot()
    }

    @Synchronized
    fun advanceSession(): CameraGenerationSnapshot {
        session = nextGeneration(session)
        activeCapture = NO_CAPTURE
        return snapshot()
    }

    @Synchronized
    fun beginCapture(): CaptureToken {
        check(activeCapture == NO_CAPTURE) { "A capture transaction is already active" }
        captureSequence = nextGeneration(captureSequence)
        val token = CaptureToken(captureSequence)
        activeCapture = token.value
        return token
    }

    @Synchronized
    fun endCapture(token: CaptureToken): Boolean {
        if (activeCapture != token.value) return false
        activeCapture = NO_CAPTURE
        return true
    }

    @Synchronized
    fun accepts(
        expectedSelection: SelectionGeneration,
        expectedSession: SessionGeneration,
    ): Boolean = selection == expectedSelection.value && session == expectedSession.value

    @Synchronized
    fun acceptsCapture(
        expectedSelection: SelectionGeneration,
        expectedSession: SessionGeneration,
        token: CaptureToken,
    ): Boolean = accepts(expectedSelection, expectedSession) && activeCapture == token.value

    private fun nextGeneration(current: Long): Long {
        check(current < Long.MAX_VALUE) { "Camera generation exhausted" }
        return current + 1L
    }

    private companion object {
        const val NO_CAPTURE = 0L
    }
}
