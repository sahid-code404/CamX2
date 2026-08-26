package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.SessionGeneration

sealed interface CameraEngineState {
    data object Closed : CameraEngineState

    data class WaitingForSurface(val selection: ActiveCameraSelection?) : CameraEngineState

    data class Opening(
        val selection: ActiveCameraSelection,
        val sessionGeneration: SessionGeneration,
    ) : CameraEngineState {
        init {
            require(selection.sessionGeneration == sessionGeneration) {
                "Opening selection/session generations must agree"
            }
        }
    }

    data class ConfiguringPreview(
        val selection: ActiveCameraSelection,
        val attempt: PreviewConfigurationAttemptKind,
    ) : CameraEngineState

    data class Previewing(val selection: ActiveCameraSelection, val firstFrameVerified: Boolean) : CameraEngineState

    data class Switching(val from: CameraRouteId?, val to: ActiveCameraSelection) : CameraEngineState

    data class ConfiguringRaw(val selection: ActiveCameraSelection, val token: CaptureToken) : CameraEngineState

    data class CapturingRaw(val selection: ActiveCameraSelection, val token: CaptureToken) : CameraEngineState

    data class PairingRaw(val selection: ActiveCameraSelection, val token: CaptureToken) : CameraEngineState

    data class WritingDng(val selection: ActiveCameraSelection, val token: CaptureToken) : CameraEngineState

    data class RestoringPreview(val selection: ActiveCameraSelection, val token: CaptureToken) : CameraEngineState

    data class Pausing(val selection: ActiveCameraSelection?) : CameraEngineState

    data class RecoverableError(
        val selection: ActiveCameraSelection?,
        val failure: CameraFailure,
    ) : CameraEngineState {
        init {
            require(!failure.policy.structural) {
                "Structural camera failures cannot enter RecoverableError"
            }
            require(!failure.policy.fallbackPermitted) {
                "Fallback-eligible configuration failures must proceed directly to the safe baseline"
            }
        }
    }

    data class StructuralError(
        val selection: ActiveCameraSelection,
        val failure: CameraFailure,
    ) : CameraEngineState {
        init {
            require(failure.policy.structural) {
                "Only structural camera failures can enter StructuralError"
            }
        }
    }
}

enum class CameraEnginePhase {
    CLOSED,
    WAITING_FOR_SURFACE,
    OPENING,
    CONFIGURING_PREVIEW,
    PREVIEWING,
    SWITCHING,
    CONFIGURING_RAW,
    CAPTURING_RAW,
    PAIRING_RAW,
    WRITING_DNG,
    RESTORING_PREVIEW,
    PAUSING,
    RECOVERABLE_ERROR,
    STRUCTURAL_ERROR,
}

fun CameraEngineState.phase(): CameraEnginePhase = when (this) {
    CameraEngineState.Closed -> CameraEnginePhase.CLOSED
    is CameraEngineState.WaitingForSurface -> CameraEnginePhase.WAITING_FOR_SURFACE
    is CameraEngineState.Opening -> CameraEnginePhase.OPENING
    is CameraEngineState.ConfiguringPreview -> CameraEnginePhase.CONFIGURING_PREVIEW
    is CameraEngineState.Previewing -> CameraEnginePhase.PREVIEWING
    is CameraEngineState.Switching -> CameraEnginePhase.SWITCHING
    is CameraEngineState.ConfiguringRaw -> CameraEnginePhase.CONFIGURING_RAW
    is CameraEngineState.CapturingRaw -> CameraEnginePhase.CAPTURING_RAW
    is CameraEngineState.PairingRaw -> CameraEnginePhase.PAIRING_RAW
    is CameraEngineState.WritingDng -> CameraEnginePhase.WRITING_DNG
    is CameraEngineState.RestoringPreview -> CameraEnginePhase.RESTORING_PREVIEW
    is CameraEngineState.Pausing -> CameraEnginePhase.PAUSING
    is CameraEngineState.RecoverableError -> CameraEnginePhase.RECOVERABLE_ERROR
    is CameraEngineState.StructuralError -> CameraEnginePhase.STRUCTURAL_ERROR
}

object CameraStateTransitions {
    private val allowed = mapOf(
        CameraEnginePhase.WAITING_FOR_SURFACE to setOf(
            CameraEnginePhase.WAITING_FOR_SURFACE,
            CameraEnginePhase.OPENING,
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.CLOSED,
            CameraEnginePhase.RECOVERABLE_ERROR,
        ),
        CameraEnginePhase.OPENING to setOf(
            CameraEnginePhase.CONFIGURING_PREVIEW,
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.RECOVERABLE_ERROR,
            CameraEnginePhase.STRUCTURAL_ERROR,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.CONFIGURING_PREVIEW to setOf(
            CameraEnginePhase.CONFIGURING_PREVIEW,
            CameraEnginePhase.PREVIEWING,
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.RECOVERABLE_ERROR,
            CameraEnginePhase.STRUCTURAL_ERROR,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.PREVIEWING to setOf(
            CameraEnginePhase.PREVIEWING,
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.CONFIGURING_RAW,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.RECOVERABLE_ERROR,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.SWITCHING to setOf(
            CameraEnginePhase.OPENING,
            CameraEnginePhase.WAITING_FOR_SURFACE,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.RECOVERABLE_ERROR,
            CameraEnginePhase.STRUCTURAL_ERROR,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.CONFIGURING_RAW to rawInFlightDestinations(CameraEnginePhase.CAPTURING_RAW),
        CameraEnginePhase.CAPTURING_RAW to rawInFlightDestinations(CameraEnginePhase.PAIRING_RAW),
        CameraEnginePhase.PAIRING_RAW to rawInFlightDestinations(CameraEnginePhase.WRITING_DNG),
        CameraEnginePhase.WRITING_DNG to rawInFlightDestinations(CameraEnginePhase.RESTORING_PREVIEW),
        CameraEnginePhase.RESTORING_PREVIEW to setOf(
            CameraEnginePhase.PREVIEWING,
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.RECOVERABLE_ERROR,
            CameraEnginePhase.STRUCTURAL_ERROR,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.PAUSING to setOf(CameraEnginePhase.WAITING_FOR_SURFACE, CameraEnginePhase.CLOSED),
        CameraEnginePhase.RECOVERABLE_ERROR to setOf(
            CameraEnginePhase.WAITING_FOR_SURFACE,
            CameraEnginePhase.OPENING,
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.STRUCTURAL_ERROR to setOf(
            CameraEnginePhase.SWITCHING,
            CameraEnginePhase.PAUSING,
            CameraEnginePhase.CLOSED,
        ),
        CameraEnginePhase.CLOSED to emptySet(),
    )

    fun requirePhaseAllowed(from: CameraEngineState, to: CameraEnginePhase) {
        val fromPhase = from.phase()
        require(to in allowed.getValue(fromPhase)) {
            "Illegal camera state transition: $fromPhase -> $to"
        }
    }

    fun requireAllowed(from: CameraEngineState, to: CameraEngineState) {
        requirePhaseAllowed(from, to.phase())
        requireIdentityContinuity(from, to)
    }

    private fun requireIdentityContinuity(from: CameraEngineState, to: CameraEngineState) {
        if (from is CameraEngineState.Previewing && to is CameraEngineState.Previewing) {
            require(!from.firstFrameVerified || to.firstFrameVerified) {
                "First-frame verification cannot regress inside one preview session"
            }
        }
        when {
            from is CameraEngineState.ConfiguringPreview &&
                to is CameraEngineState.ConfiguringPreview -> {
                require(
                    from.attempt == PreviewConfigurationAttemptKind.REQUESTED &&
                        to.attempt == PreviewConfigurationAttemptKind.SAFE_BASELINE,
                ) { "Preview fallback must move once from requested options to the safe baseline" }
                requireSameSelectionIntent(
                    from.selection,
                    to.selection,
                    sessionContinuity = SessionContinuity.STRICTLY_ADVANCED,
                    message = "Preview fallback changed selection intent or reused a session generation",
                )
            }
            to is CameraEngineState.Switching -> {
                val activeSelection = from.selectionOrNull()
                require(to.from == from.selectionOrNull()?.routeId) {
                    "Switching source route does not match active selection"
                }
                require(
                    if (activeSelection == null) {
                        to.to.selectionGeneration.value > 0L &&
                            to.to.sessionGeneration.value > 0L
                    } else {
                        to.to.selectionGeneration.value > activeSelection.selectionGeneration.value &&
                            to.to.sessionGeneration.value > activeSelection.sessionGeneration.value
                    },
                ) { "Switching target must strictly advance selection and session generations" }
            }
            from is CameraEngineState.Switching && to is CameraEngineState.Opening ->
                require(from.to == to.selection) { "Switch/open selection identity changed" }
            from is CameraEngineState.Switching && to is CameraEngineState.WaitingForSurface ->
                require(from.to == to.selection) { "Switch/wait selection identity changed" }
            from is CameraEngineState.Pausing && to is CameraEngineState.WaitingForSurface ->
                require(from.selection == to.selection) { "Pause lost active selection identity" }
        }

        val edge = from.phase() to to.phase()
        val exactSessionEdges = setOf(
            CameraEnginePhase.WAITING_FOR_SURFACE to CameraEnginePhase.WAITING_FOR_SURFACE,
            CameraEnginePhase.WAITING_FOR_SURFACE to CameraEnginePhase.OPENING,
            CameraEnginePhase.OPENING to CameraEnginePhase.CONFIGURING_PREVIEW,
            CameraEnginePhase.CONFIGURING_PREVIEW to CameraEnginePhase.PREVIEWING,
            CameraEnginePhase.PREVIEWING to CameraEnginePhase.PREVIEWING,
            CameraEnginePhase.CONFIGURING_RAW to CameraEnginePhase.CAPTURING_RAW,
            CameraEnginePhase.CAPTURING_RAW to CameraEnginePhase.PAIRING_RAW,
            CameraEnginePhase.PAIRING_RAW to CameraEnginePhase.WRITING_DNG,
            CameraEnginePhase.RESTORING_PREVIEW to CameraEnginePhase.PREVIEWING,
            CameraEnginePhase.PAUSING to CameraEnginePhase.WAITING_FOR_SURFACE,
        )
        val rawPhases = setOf(
            CameraEnginePhase.CONFIGURING_RAW,
            CameraEnginePhase.CAPTURING_RAW,
            CameraEnginePhase.PAIRING_RAW,
            CameraEnginePhase.WRITING_DNG,
        )
        val sessionContinuity = when {
            from is CameraEngineState.Switching &&
                (to is CameraEngineState.Opening || to is CameraEngineState.WaitingForSurface) -> null
            to is CameraEngineState.Pausing -> SessionContinuity.STRICTLY_ADVANCED
            to is CameraEngineState.RecoverableError || to is CameraEngineState.StructuralError ->
                SessionContinuity.MONOTONIC
            edge == (CameraEnginePhase.RECOVERABLE_ERROR to CameraEnginePhase.OPENING) ->
                SessionContinuity.STRICTLY_ADVANCED
            edge == (CameraEnginePhase.RECOVERABLE_ERROR to CameraEnginePhase.WAITING_FOR_SURFACE) ->
                SessionContinuity.MONOTONIC
            edge == (CameraEnginePhase.PREVIEWING to CameraEnginePhase.CONFIGURING_RAW) ->
                SessionContinuity.STRICTLY_ADVANCED
            from.phase() in rawPhases && to.phase() == CameraEnginePhase.RESTORING_PREVIEW ->
                SessionContinuity.STRICTLY_ADVANCED
            edge in exactSessionEdges -> SessionContinuity.EXACT
            else -> null
        }
        if (sessionContinuity != null) {
            requireSameSelectionIntent(
                from.selectionOrNull(),
                to.selectionOrNull(),
                sessionContinuity = sessionContinuity,
                message = "Camera selection intent changed across ${from.phase()} -> ${to.phase()}",
            )
        }

        if (from.captureTokenOrNull() != null && to.captureTokenOrNull() != null) {
            require(from.captureTokenOrNull() == to.captureTokenOrNull()) {
                "Capture token changed inside one RAW transaction"
            }
        }
    }

    private fun rawInFlightDestinations(next: CameraEnginePhase) = setOf(
        next,
        CameraEnginePhase.RESTORING_PREVIEW,
        CameraEnginePhase.SWITCHING,
        CameraEnginePhase.PAUSING,
        CameraEnginePhase.RECOVERABLE_ERROR,
        CameraEnginePhase.STRUCTURAL_ERROR,
        CameraEnginePhase.CLOSED,
    )

    private fun requireSameSelectionIntent(
        from: ActiveCameraSelection?,
        to: ActiveCameraSelection?,
        sessionContinuity: SessionContinuity,
        message: String,
    ) {
        if (from == null || to == null) {
            require(from == to) { message }
            return
        }
        require(
            from.canonicalLensFingerprint == to.canonicalLensFingerprint &&
                from.profileFingerprint == to.profileFingerprint &&
                from.routeId == to.routeId &&
                from.selectionGeneration == to.selectionGeneration &&
                when (sessionContinuity) {
                    SessionContinuity.EXACT -> to.sessionGeneration == from.sessionGeneration
                    SessionContinuity.MONOTONIC ->
                        to.sessionGeneration.value >= from.sessionGeneration.value
                    SessionContinuity.STRICTLY_ADVANCED ->
                        to.sessionGeneration.value > from.sessionGeneration.value
                },
        ) { message }
    }

    private enum class SessionContinuity {
        EXACT,
        MONOTONIC,
        STRICTLY_ADVANCED,
    }
}

fun CameraEngineState.selectionOrNull(): ActiveCameraSelection? = when (this) {
    CameraEngineState.Closed -> null
    is CameraEngineState.WaitingForSurface -> selection
    is CameraEngineState.Opening -> selection
    is CameraEngineState.ConfiguringPreview -> selection
    is CameraEngineState.Previewing -> selection
    is CameraEngineState.Switching -> to
    is CameraEngineState.ConfiguringRaw -> selection
    is CameraEngineState.CapturingRaw -> selection
    is CameraEngineState.PairingRaw -> selection
    is CameraEngineState.WritingDng -> selection
    is CameraEngineState.RestoringPreview -> selection
    is CameraEngineState.Pausing -> selection
    is CameraEngineState.RecoverableError -> selection
    is CameraEngineState.StructuralError -> selection
}

private fun CameraEngineState.captureTokenOrNull(): CaptureToken? = when (this) {
    is CameraEngineState.ConfiguringRaw -> token
    is CameraEngineState.CapturingRaw -> token
    is CameraEngineState.PairingRaw -> token
    is CameraEngineState.WritingDng -> token
    is CameraEngineState.RestoringPreview -> token
    else -> null
}
