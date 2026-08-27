package com.sahidcode404.camx.core.camera.bootstrap

import android.content.Context
import com.sahidcode404.camx.core.camera.diagnostics.RawCaptureRejected
import com.sahidcode404.camx.core.camera.diagnostics.RawPairTimeout
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.raw.AndroidDngWriter
import com.sahidcode404.camx.core.camera.raw.AndroidDngWriterMode
import com.sahidcode404.camx.core.camera.raw.Cp1EvidenceStore
import com.sahidcode404.camx.core.camera.raw.M4BurstLimits
import com.sahidcode404.camx.core.camera.raw.RawBurstCaptureIdentity
import com.sahidcode404.camx.core.camera.raw.RawBurstCaptureOutcome
import com.sahidcode404.camx.core.camera.raw.RawBurstCaptureReport
import com.sahidcode404.camx.core.camera.raw.RawBurstDiagnosticsHub
import com.sahidcode404.camx.core.camera.raw.RawBurstDiagnosticsSnapshot
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.camera.raw.RawSourceLayoutCertification
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.camera.session.CameraSessionController
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ComputationalRawProbeResult(
    val outcome: RawBurstCaptureOutcome,
    val report: RawBurstCaptureReport,
)

/**
 * CP1 application orchestration only. CameraSessionController still owns every Camera2 device,
 * session, ImageReader, capture request and Image. This class performs a probe-only source-layout
 * certification, then requests exactly eight already-supported M4 RAW_SENSOR frames.
 */
internal class Cp1CaptureCoordinator(
    context: Context,
    private val controller: CameraSessionController,
) {
    private val appContext = context.applicationContext
    private val layoutProbeWriter = AndroidDngWriter(appContext, AndroidDngWriterMode.LAYOUT_PROBE)
    private val evidenceStore = Cp1EvidenceStore(appContext)
    private val active = AtomicBoolean(false)

    suspend fun capture(displayRotation: DisplayRotation): ComputationalRawProbeResult {
        if (!active.compareAndSet(false, true)) {
            val report = RawBurstCaptureReport.rejected(
                requestedFrames = CP1_REQUESTED_FRAMES,
                detail = "CP1 RAW burst is already active",
            )
            return failedResult(
                RawBurstCaptureOutcome.Failed(RawCaptureRejected("CP1 RAW burst is already active")),
                report,
            )
        }

        try {
            val preflight = when (val probe = controller.captureRawDng(displayRotation, layoutProbeWriter)) {
                is RawCaptureOutcome.Probed -> probe.layout
                is RawCaptureOutcome.Failed -> {
                    return failedResult(
                        RawBurstCaptureOutcome.Failed(probe.failure),
                        RawBurstCaptureReport.rejected(
                            requestedFrames = CP1_REQUESTED_FRAMES,
                            detail = "CP1 RAW layout preflight failed: ${failureDetail(probe.failure)}",
                        ),
                    )
                }
                RawCaptureOutcome.Cancelled -> {
                    return failedResult(
                        RawBurstCaptureOutcome.Cancelled,
                        RawBurstCaptureReport.rejected(
                            requestedFrames = CP1_REQUESTED_FRAMES,
                            detail = "CP1 RAW layout preflight cancelled",
                            cancelled = true,
                        ),
                    )
                }
                is RawCaptureOutcome.Saved -> {
                    return failedResult(
                        RawBurstCaptureOutcome.Failed(
                            RawCaptureRejected("CP1 layout preflight unexpectedly wrote a photo"),
                        ),
                        RawBurstCaptureReport.rejected(
                            requestedFrames = CP1_REQUESTED_FRAMES,
                            detail = "CP1 layout preflight unexpectedly wrote a photo",
                        ),
                    )
                }
            }

            val restoredPreview = try {
                withTimeout(M4BurstLimits.DEFAULT_TIMEOUT_MILLIS) {
                    controller.state.filterIsInstance<CameraEngineState.Previewing>()
                        .first { it.firstFrameVerified }
                }
            } catch (_: TimeoutCancellationException) {
                val report = RawBurstCaptureReport.rejected(
                    requestedFrames = CP1_REQUESTED_FRAMES,
                    detail = "CP1 preview restoration timed out after RAW layout preflight",
                    preflight = preflight,
                ).copy(timedOut = true)
                return failedResult(
                    RawBurstCaptureOutcome.Failed(
                        RawCaptureRejected("CP1 preview restoration timed out after RAW layout preflight"),
                    ),
                    report,
                )
            }
            if (!preflight.matches(restoredPreview.selection)) {
                return failedResult(
                    RawBurstCaptureOutcome.Cancelled,
                    RawBurstCaptureReport.rejected(
                        requestedFrames = CP1_REQUESTED_FRAMES,
                        detail = "CP1 selection changed after RAW layout preflight",
                        preflight = preflight,
                        cancelled = true,
                    ),
                )
            }

            return runEightFrameBurst(displayRotation, preflight)
        } finally {
            active.set(false)
        }
    }

    private suspend fun runEightFrameBurst(
        displayRotation: DisplayRotation,
        preflight: RawSourceLayoutCertification,
    ): ComputationalRawProbeResult = coroutineScope {
        val diagnosticsSession = RawBurstDiagnosticsHub.begin()
        var observedIdentity: RawBurstCaptureIdentity? = null
        val identityObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { state ->
                if (observedIdentity == null) {
                    observedIdentity = captureIdentity(state, preflight, displayRotation)
                }
            }
        }

        var outcome: RawBurstCaptureOutcome? = null
        var diagnostics: RawBurstDiagnosticsSnapshot? = null
        try {
            outcome = controller.captureRawBurst(
                displayRotation = displayRotation,
                frameCount = CP1_REQUESTED_FRAMES,
                maxSourceBytesPerFrame = preflight.sourceRequiredBytes,
                maxResidentBytes = cp1ResidentBudgetBytes(preflight),
                timeoutMillis = M4BurstLimits.DEFAULT_TIMEOUT_MILLIS,
            )
        } catch (cancelled: CancellationException) {
            identityObserver.cancelAndJoin()
            diagnostics = RawBurstDiagnosticsHub.finish(diagnosticsSession)
            val report = buildReport(
                outcome = RawBurstCaptureOutcome.Cancelled,
                diagnostics = checkNotNull(diagnostics),
                preflight = preflight,
                observedIdentity = observedIdentity,
                displayRotation = displayRotation,
            ).copy(cancelled = true, failureDetail = "CP1 RAW burst coroutine cancelled")
            withContext(NonCancellable) { evidenceStore.persistFailure(report) }
            throw cancelled
        } finally {
            if (diagnostics == null) {
                identityObserver.cancelAndJoin()
                diagnostics = RawBurstDiagnosticsHub.finish(diagnosticsSession)
            }
        }

        val capturedOutcome = checkNotNull(outcome)
        var report = buildReport(
            outcome = capturedOutcome,
            diagnostics = checkNotNull(diagnostics),
            preflight = preflight,
            observedIdentity = observedIdentity,
            displayRotation = displayRotation,
        )

        if (capturedOutcome is RawBurstCaptureOutcome.Captured && !report.success) {
            val detail = "CP1 returned a frame set but its acquisition evidence was incomplete"
            report = report.copy(failureDetail = detail)
            return@coroutineScope failedResult(
                RawBurstCaptureOutcome.Failed(RawCaptureRejected(detail)),
                report,
            )
        }

        when (capturedOutcome) {
            is RawBurstCaptureOutcome.Captured -> {
                val persisted = evidenceStore.persistSuccess(capturedOutcome.frameSet, report)
                ComputationalRawProbeResult(
                    outcome = capturedOutcome,
                    report = report.withEvidencePersisted(persisted),
                )
            }
            is RawBurstCaptureOutcome.Failed,
            RawBurstCaptureOutcome.Cancelled,
            -> failedResult(capturedOutcome, report)
        }
    }

    private fun buildReport(
        outcome: RawBurstCaptureOutcome,
        diagnostics: RawBurstDiagnosticsSnapshot,
        preflight: RawSourceLayoutCertification,
        observedIdentity: RawBurstCaptureIdentity?,
        displayRotation: DisplayRotation,
    ): RawBurstCaptureReport {
        val identity = (outcome as? RawBurstCaptureOutcome.Captured)?.frameSet?.context
            ?.let(::identityFromContext)
            ?: observedIdentity
        val failed = outcome as? RawBurstCaptureOutcome.Failed
        val rejectionReason = (failed?.failure as? RawCaptureRejected)?.reason
        val timedOut = failed?.failure == RawPairTimeout
        val sequenceAborted = rejectionReason?.startsWith("RAW burst sequence ") == true &&
            rejectionReason.endsWith(" was aborted")
        val captureFailures = if (rejectionReason?.startsWith("RAW burst failed reason=") == true) 1 else 0
        val hasBurstEvidence = diagnostics.imagesReceived > 0 ||
            diagnostics.resultsReceived > 0 ||
            diagnostics.exactPairsCreated > 0
        val submitted = when {
            outcome is RawBurstCaptureOutcome.Captured -> CP1_REQUESTED_FRAMES
            hasBurstEvidence -> CP1_REQUESTED_FRAMES
            timedOut -> CP1_REQUESTED_FRAMES
            sequenceAborted || captureFailures > 0 -> CP1_REQUESTED_FRAMES
            else -> 0
        }
        val invalidImages = if (
            failed?.failure is RawCaptureRejected &&
            diagnostics.exactPairsCreated == CP1_REQUESTED_FRAMES &&
            diagnostics.framesAccepted < CP1_REQUESTED_FRAMES
        ) 1 else 0

        return RawBurstCaptureReport(
            identity = identity,
            preflight = preflight,
            requestedFrames = CP1_REQUESTED_FRAMES,
            preflightFrames = 1,
            captureRequestsSubmitted = submitted,
            imagesReceived = diagnostics.imagesReceived,
            resultsReceived = diagnostics.resultsReceived,
            exactPairsCreated = diagnostics.exactPairsCreated,
            framesCopied = diagnostics.framesCopied,
            framesAccepted = diagnostics.framesAccepted,
            duplicateImageTimestamps = diagnostics.duplicateImageTimestamps,
            duplicateResultTimestamps = diagnostics.duplicateResultTimestamps,
            duplicateOrdinals = diagnostics.duplicateOrdinals,
            unmatchedImages = diagnostics.unmatchedImages,
            unmatchedResults = diagnostics.unmatchedResults,
            invalidImages = invalidImages,
            captureFailures = captureFailures,
            sequenceAborted = sequenceAborted,
            timedOut = timedOut,
            cancelled = outcome == RawBurstCaptureOutcome.Cancelled,
            staleCallbacksAccepted = 0,
            imageObjectsStillOwned = 0,
            evidencePersisted = false,
            failureDetail = when (outcome) {
                is RawBurstCaptureOutcome.Captured -> null
                is RawBurstCaptureOutcome.Failed -> failureDetail(outcome.failure)
                RawBurstCaptureOutcome.Cancelled -> "CP1 RAW burst cancelled"
            },
        )
    }

    private suspend fun failedResult(
        outcome: RawBurstCaptureOutcome,
        report: RawBurstCaptureReport,
    ): ComputationalRawProbeResult {
        val persisted = evidenceStore.persistFailure(report)
        return ComputationalRawProbeResult(outcome, report.withEvidencePersisted(persisted))
    }

    private fun captureIdentity(
        state: CameraEngineState,
        preflight: RawSourceLayoutCertification,
        displayRotation: DisplayRotation,
    ): RawBurstCaptureIdentity? {
        val raw = when (state) {
            is CameraEngineState.ConfiguringRaw -> state.selection to state.token
            is CameraEngineState.CapturingRaw -> state.selection to state.token
            is CameraEngineState.PairingRaw -> state.selection to state.token
            else -> return null
        }
        val selection = raw.first
        return RawBurstCaptureIdentity(
            captureToken = raw.second,
            selectionGeneration = selection.selectionGeneration,
            sessionGeneration = selection.sessionGeneration,
            canonicalLensFingerprint = selection.canonicalLensFingerprint,
            cameraProfileFingerprint = selection.profileFingerprint,
            routeId = selection.routeId,
            rawSize = preflight.rawSize,
            displayRotationAtShutter = displayRotation,
        )
    }

    private fun identityFromContext(context: RawCaptureContext) = RawBurstCaptureIdentity(
        captureToken = context.captureToken,
        selectionGeneration = context.selectionGeneration,
        sessionGeneration = context.sessionGeneration,
        canonicalLensFingerprint = context.canonicalLensFingerprint,
        cameraProfileFingerprint = context.cameraProfileFingerprint,
        routeId = context.routeId,
        rawSize = context.rawSize,
        displayRotationAtShutter = context.displayRotationAtShutter,
    )

    /**
     * ImageReader's RAW buffers are camera/native allocations, while the canonical frame copies live
     * in the managed heap. The original CP1 probe compared the combined reservation only against
     * managed-heap headroom, which could reject an otherwise admissible burst before request submit
     * and report 0/8. Build a conservative composite ceiling: certified camera-buffer extent plus
     * currently available managed-heap headroom, still capped by the frozen one-GiB M4 bound.
     */
    private fun cp1ResidentBudgetBytes(preflight: RawSourceLayoutCertification): Long {
        val runtime = Runtime.getRuntime()
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val managedHeapHeadroom = (runtime.maxMemory() - usedHeap).coerceAtLeast(1L)
        val cameraBufferReservation = runCatching {
            Math.multiplyExact(CP1_REQUESTED_FRAMES.toLong(), preflight.sourceRequiredBytes)
        }.getOrElse { return M4BurstLimits.MAX_RESIDENT_BYTES }
        val composite = runCatching {
            Math.addExact(cameraBufferReservation, managedHeapHeadroom)
        }.getOrElse { Long.MAX_VALUE }
        return minOf(M4BurstLimits.MAX_RESIDENT_BYTES, composite)
    }

    private fun failureDetail(failure: com.sahidcode404.camx.core.camera.diagnostics.CameraFailure): String =
        (failure as? RawCaptureRejected)?.reason ?: failure.javaClass.simpleName

    private companion object {
        const val CP1_REQUESTED_FRAMES = 8
    }
}
