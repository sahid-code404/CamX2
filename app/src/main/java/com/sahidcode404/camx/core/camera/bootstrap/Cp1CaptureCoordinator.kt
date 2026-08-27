package com.sahidcode404.camx.core.camera.bootstrap

import android.content.Context
import com.sahidcode404.camx.core.camera.diagnostics.RawCaptureRejected
import com.sahidcode404.camx.core.camera.diagnostics.RawPairTimeout
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.raw.AndroidDngWriter
import com.sahidcode404.camx.core.camera.raw.AndroidDngWriterMode
import com.sahidcode404.camx.core.camera.raw.Cp1EvidenceStore
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationBundle
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationObservationHub
import com.sahidcode404.camx.core.camera.raw.Cp2CalibrationReport
import com.sahidcode404.camx.core.camera.raw.Cp2EvidenceStore
import com.sahidcode404.camx.core.camera.raw.Cp3EvidenceStore
import com.sahidcode404.camx.core.camera.raw.Cp4ComputationalDngStore
import com.sahidcode404.camx.core.camera.raw.Cp4SaveReport
import com.sahidcode404.camx.core.camera.raw.ImmutableRawFrameSet
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
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3ComputationalRawEngine
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FixedPatternNoiseMode
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusionOutcome
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusionReport
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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

enum class ComputationalRawAcquisitionStatus {
    CAPTURED,
    FAILED,
    CANCELLED,
}

data class ComputationalRawProbeResult(
    val acquisitionStatus: ComputationalRawAcquisitionStatus,
    val report: RawBurstCaptureReport,
    val cp2Report: Cp2CalibrationReport? = null,
    val cp3Report: Cp3FusionReport? = null,
    val cp4Report: Cp4SaveReport? = null,
)

/**
 * CP1 acquisition, CP2 calibration, CP3 sensor-domain fusion and CP4 computational-DNG save.
 * CameraSessionController still owns every Camera2 device, session, ImageReader, capture request and
 * Image. CP2 observes exact metadata; CP3/CP4 operate only after Android Image ownership has ended.
 */
internal class Cp1CaptureCoordinator(
    context: Context,
    private val controller: CameraSessionController,
) {
    private val appContext = context.applicationContext
    private val layoutProbeWriter = AndroidDngWriter(appContext, AndroidDngWriterMode.LAYOUT_PROBE)
    private val evidenceStore = Cp1EvidenceStore(appContext)
    private val cp2EvidenceStore = Cp2EvidenceStore(appContext)
    private val cp3EvidenceStore = Cp3EvidenceStore(appContext)
    private val cp4Store = Cp4ComputationalDngStore(appContext)
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

            // CP2 is observational. If its diagnostic seam cannot be armed, CP1 still runs exactly
            // as before and returns truthful 8-frame acquisition evidence with cp2Report == null.
            val cp2Observation = runCatching {
                Cp2CalibrationObservationHub.beginBurst(CP1_REQUESTED_FRAMES)
            }.getOrNull()
            return runEightFrameBurst(displayRotation, preflight, cp2Observation)
        } finally {
            active.set(false)
        }
    }

    private suspend fun runEightFrameBurst(
        displayRotation: DisplayRotation,
        preflight: RawSourceLayoutCertification,
        cp2Observation: Cp2CalibrationObservationHub.Cp2BurstObservationLease?,
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
            cp2Observation?.close()
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

        // captureRawBurst starts preview restoration before it returns, but a new shutter transaction
        // must not be admitted until the restored stream has produced a verified frame. This closes
        // the real-device retry race that could turn a successful 8/8 burst into a following 0/8.
        val restoredAfterBurst = try {
            withTimeout(M4BurstLimits.DEFAULT_TIMEOUT_MILLIS) {
                controller.state.filterIsInstance<CameraEngineState.Previewing>()
                    .first { it.firstFrameVerified }
            }
        } catch (_: TimeoutCancellationException) {
            cp2Observation?.close()
            val detail = "CP1 preview restoration timed out after RAW burst"
            report = report.copy(timedOut = true, failureDetail = detail)
            return@coroutineScope failedResult(
                RawBurstCaptureOutcome.Failed(RawCaptureRejected(detail)),
                report,
            )
        }
        if (!preflight.matches(restoredAfterBurst.selection)) {
            cp2Observation?.close()
            val detail = "CP1 selection changed while restoring preview after RAW burst"
            report = report.copy(cancelled = true, failureDetail = detail)
            return@coroutineScope failedResult(RawBurstCaptureOutcome.Cancelled, report)
        }

        if (capturedOutcome is RawBurstCaptureOutcome.Captured && !report.success) {
            cp2Observation?.close()
            val detail = "CP1 returned a frame set but its acquisition evidence was incomplete"
            report = report.copy(failureDetail = detail)
            return@coroutineScope failedResult(
                RawBurstCaptureOutcome.Failed(RawCaptureRejected(detail)),
                report,
            )
        }

        when (capturedOutcome) {
            is RawBurstCaptureOutcome.Captured -> {
                val frameSet = capturedOutcome.frameSet
                val persisted = evidenceStore.persistSuccess(frameSet, report)
                val cp2Bundle = cp2Observation?.let { observation ->
                    runCatching { observation.finish(frameSet) }
                        .onFailure { observation.close() }
                        .getOrNull()
                }
                val cp2Report = cp2Bundle?.let { bundle ->
                    val cp2Persisted = cp2EvidenceStore.persist(bundle)
                    bundle.report.copy(evidencePersisted = cp2Persisted)
                }
                val cp3Outcome = cp2Bundle?.let { bundle ->
                    try {
                        withContext(Dispatchers.Default) {
                            Cp3ComputationalRawEngine.fuse(
                                frameSet = frameSet,
                                calibration = bundle,
                                maxResidentBytes = cp3ResidentBudgetBytes(),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Cp3FusionOutcome.Failed(cp3ExecutionFailure(frameSet, bundle, failure))
                    }
                }
                val cp3Report = cp3Outcome?.let { fusion ->
                    val fusionReport = when (fusion) {
                        is Cp3FusionOutcome.Fused -> fusion.report
                        is Cp3FusionOutcome.Failed -> fusion.report
                    }
                    val cp3Persisted = cp3EvidenceStore.persist(fusionReport)
                    fusionReport.withEvidencePersisted(cp3Persisted)
                }
                val cp4Report = if (cp2Bundle != null && cp3Outcome is Cp3FusionOutcome.Fused) {
                    cp4Store.save(
                        captureContext = frameSet.context,
                        fused = cp3Outcome.fused,
                        fusionReport = cp3Report ?: cp3Outcome.report,
                        calibration = cp2Bundle,
                    )
                } else {
                    null
                }
                ComputationalRawProbeResult(
                    acquisitionStatus = ComputationalRawAcquisitionStatus.CAPTURED,
                    report = report.withEvidencePersisted(persisted),
                    cp2Report = cp2Report,
                    cp3Report = cp3Report,
                    cp4Report = cp4Report,
                )
            }
            is RawBurstCaptureOutcome.Failed,
            RawBurstCaptureOutcome.Cancelled,
            -> {
                cp2Observation?.close()
                failedResult(capturedOutcome, report)
            }
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
        val status = when (outcome) {
            is RawBurstCaptureOutcome.Captured -> ComputationalRawAcquisitionStatus.CAPTURED
            is RawBurstCaptureOutcome.Failed -> ComputationalRawAcquisitionStatus.FAILED
            RawBurstCaptureOutcome.Cancelled -> ComputationalRawAcquisitionStatus.CANCELLED
        }
        return ComputationalRawProbeResult(status, report.withEvidencePersisted(persisted))
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
     * ImageReader RAW buffers are camera/native allocations while canonical copies live in the managed
     * heap. Runtime.freeMemory() is not an admission authority for a retry because ART may retain dead
     * arrays until allocation pressure triggers GC. Use the bounded heap capacity minus an explicit app
     * reserve; actual canonical allocations still remain inside the VM heap limit.
     */
    private fun cp1ResidentBudgetBytes(preflight: RawSourceLayoutCertification): Long {
        val managedHeapCeiling = (Runtime.getRuntime().maxMemory() - CP1_MANAGED_HEAP_RESERVE_BYTES)
            .coerceAtLeast(1L)
        val cameraBufferReservation = runCatching {
            Math.multiplyExact(CP1_REQUESTED_FRAMES.toLong(), preflight.sourceRequiredBytes)
        }.getOrElse { return M4BurstLimits.MAX_RESIDENT_BYTES }
        val composite = runCatching {
            Math.addExact(cameraBufferReservation, managedHeapCeiling)
        }.getOrElse { Long.MAX_VALUE }
        return minOf(M4BurstLimits.MAX_RESIDENT_BYTES, composite)
    }

    /**
     * CP3 runs immediately after an eight-frame burst. Runtime.freeMemory() only reports currently
     * committed free heap and does not include reclaimable dead burst/preflight allocations, so using
     * it as an admission authority can falsely reject fusion before the FloatArray is even attempted.
     * The immutable FrameSet is already resident; admit against the VM heap capacity with an explicit
     * application reserve. ART remains free to collect dead transient allocations under pressure.
     */
    private fun cp3ResidentBudgetBytes(): Long {
        // CP3's own proof already includes every retained RAW byte, the complete fused U16 raster,
        // and its explicit safety margin. Keep only a small VM reserve here; the previous 48 MiB
        // second reserve rejected otherwise-admissible 8-frame bursts on constrained Android heaps.
        val managedHeapCeiling = (Runtime.getRuntime().maxMemory() - CP3_MANAGED_HEAP_RESERVE_BYTES)
            .coerceAtLeast(1L)
        return minOf(CP3_MAX_RESIDENT_BYTES, managedHeapCeiling)
    }

    private fun cp3ExecutionFailure(
        frameSet: ImmutableRawFrameSet,
        calibration: Cp2CalibrationBundle,
        failure: Throwable,
    ): Cp3FusionReport = Cp3FusionReport(
        success = false,
        algorithmId = Cp3ComputationalRawEngine.ALGORITHM_ID,
        algorithmVersion = Cp3ComputationalRawEngine.ALGORITHM_VERSION,
        requestedFrames = frameSet.frames.size,
        referenceOrdinal = null,
        exposureIdentityFrames = 0,
        alignedFrames = 0,
        contributingFrames = 0,
        activePixelCount = 0L,
        multiFramePixelCount = 0L,
        referenceOnlyPixelCount = 0L,
        censoredPixelCount = 0L,
        rejectedPixelMeasurements = 0L,
        calibrationFingerprintSha256 = calibration.report.calibrationFingerprintSha256,
        sourceCanonicalSha256 = frameSet.frames.map { it.canonicalSha256 },
        includedOrdinals = emptyList(),
        frameEvidence = emptyList(),
        outputSha256 = null,
        fixedPatternNoiseMode = Cp3FixedPatternNoiseMode.UNAVAILABLE_NOT_INVENTED,
        evidencePersisted = false,
        failureDetail = "CP3 execution failed: ${failure.javaClass.simpleName}",
    )

    private fun failureDetail(failure: com.sahidcode404.camx.core.camera.diagnostics.CameraFailure): String =
        (failure as? RawCaptureRejected)?.reason ?: failure.javaClass.simpleName

    private companion object {
        const val CP1_REQUESTED_FRAMES = 8
        const val CP1_MANAGED_HEAP_RESERVE_BYTES = 48L * 1024L * 1024L
        const val CP3_MANAGED_HEAP_RESERVE_BYTES = 8L * 1024L * 1024L
        const val CP3_MAX_RESIDENT_BYTES = 1024L * 1024L * 1024L
    }
}
