package com.sahidcode404.camx.core.camera.session

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.sahidcode404.camx.core.camera.diagnostics.CameraDeviceError
import com.sahidcode404.camx.core.camera.diagnostics.CameraDisabled
import com.sahidcode404.camx.core.camera.diagnostics.CameraDisconnected
import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.diagnostics.CameraInUse
import com.sahidcode404.camx.core.camera.diagnostics.DngWriteFailure
import com.sahidcode404.camx.core.camera.diagnostics.MaximumCamerasInUse
import com.sahidcode404.camx.core.camera.diagnostics.PermissionDenied
import com.sahidcode404.camx.core.camera.diagnostics.RawCaptureRejected
import com.sahidcode404.camx.core.camera.diagnostics.RawPairTimeout
import com.sahidcode404.camx.core.camera.diagnostics.RawSessionRejected
import com.sahidcode404.camx.core.camera.diagnostics.RawUnsupported
import com.sahidcode404.camx.core.camera.diagnostics.RequestedConfigurationKind
import com.sahidcode404.camx.core.camera.diagnostics.RequestedConfigurationRejected
import com.sahidcode404.camx.core.camera.diagnostics.SafeBaselineConfigurationRejected
import com.sahidcode404.camx.core.camera.diagnostics.StaleSession
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraResourceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraStartupMilestone
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.camera.model.RawContractLimits
import com.sahidcode404.camx.core.camera.model.RawPair
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceLease
import com.sahidcode404.camx.core.camera.raw.AndroidDngWriter
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.camera.raw.RawTimestampPairer
import com.sahidcode404.camx.core.camera.runtime.CameraGenerationGate
import com.sahidcode404.camx.core.camera.trace.BoundedCameraStartupTrace
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Sole Camera2 device/session/repeating-preview and one-shot RAW transaction owner. */
class CameraSessionController private constructor(
    private val runtime: ControllerRuntime,
    private val elapsedRealtimeNs: () -> Long,
) {
    private val callbackDispatcher: CoroutineDispatcher = runtime.dispatcher
    private val mutationGate = CameraStateMutationGate(callbackDispatcher)
    private val asyncOwnership = CameraAsyncOwnership()
    private val generations = CameraGenerationGate()
    private val callbackScope = CoroutineScope(SupervisorJob() + callbackDispatcher)
    private val shutdownRequested = AtomicBoolean(false)
    private val shutdownComplete = CompletableDeferred<Unit>()
    private val trace = BoundedCameraStartupTrace()
    private val mutableState = MutableStateFlow<CameraEngineState>(
        CameraEngineState.WaitingForSurface(selection = null),
    )
    private val mutableResources = MutableStateFlow(
        CameraResourceSnapshot(cameraWorkers = runtime.workerCount),
    )
    private val mutableRawPhotoAvailable = MutableStateFlow(false)

    private var activeSurface: ActiveSurface? = null
    private var activeDevice: ActiveDevice? = null
    private var activeSession: ActiveSession? = null
    private var activeRawReader: ActiveRawReader? = null
    private var activeRawImage: ActiveRawImage? = null
    private var currentPreview: PreviewIntent? = null
    private var nextCaptureToken = 0L

    constructor(cameraManager: CameraManager) : this(
        runtime = createAndroidRuntime(cameraManager),
        elapsedRealtimeNs = SystemClock::elapsedRealtimeNanos,
    )

    internal constructor(
        platform: CameraOwnerPlatform,
        dispatcher: CoroutineDispatcher,
        elapsedRealtimeNs: () -> Long = { 0L },
        shutdownWorker: suspend () -> Unit = {},
        workerCount: Int = 1,
    ) : this(
        ControllerRuntime(platform, dispatcher, shutdownWorker, workerCount),
        elapsedRealtimeNs,
    )

    val state: StateFlow<CameraEngineState> = mutableState.asStateFlow()
    val resources: StateFlow<CameraResourceSnapshot> = mutableResources.asStateFlow()
    val rawPhotoAvailable: StateFlow<Boolean> = mutableRawPhotoAvailable.asStateFlow()

    fun traceSnapshot() = trace.snapshot()

    suspend fun select(selection: ActiveCameraSelection) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        var cleanup: CameraCleanupPlan? = null
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            val current = mutableState.value
            CameraStateTransitions.requirePhaseAllowed(current, CameraEnginePhase.SWITCHING)
            val next = generations.advanceSelection()
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            currentPreview = null
            val effective = selection.copy(
                selectionGeneration = next.selection,
                sessionGeneration = next.session,
            )
            transition(CameraEngineState.Switching(current.selectionOrNull()?.routeId, effective))
            transition(CameraEngineState.WaitingForSurface(effective))
        }
        closePlan(cleanup)
    }

    suspend fun startPreview(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        surfaceLease: PreviewSurfaceLease,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) {
        val binding = surfaceLease.binding
        startPreviewInternal(
            selection,
            route,
            SurfaceInput(binding.identity, binding.surface, surfaceLease::close),
            configuration,
            settings,
        )
    }

    internal suspend fun startPreviewForTest(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        surfaceIdentity: PreviewSurfaceIdentity,
        surfaceToken: Any,
        closeSurface: () -> Unit,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) = startPreviewInternal(
        selection,
        route,
        SurfaceInput(surfaceIdentity, surfaceToken, closeSurface),
        configuration,
        settings,
    )

    /**
     * Executes one real sensor-RAW/DNG shutter transaction without opening another CameraDevice.
     * Physical-target RAW remains disabled until exact physical DNG metadata semantics are certified.
     */
    internal suspend fun captureRawDng(
        displayRotation: DisplayRotation,
        writer: AndroidDngWriter,
    ): RawCaptureOutcome {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        val platform = runtime.platform as? AndroidCameraOwnerPlatform
            ?: return RawCaptureOutcome.Failed(RawUnsupported)

        var candidate: PreviewIntent? = null
        mutationGate.mutate {
            val previewing = mutableState.value as? CameraEngineState.Previewing
            val preview = currentPreview
            if (
                previewing?.firstFrameVerified == true &&
                preview != null &&
                preview.selection == previewing.selection &&
                preview.route.physicalCameraId == null &&
                activeDevice != null &&
                activeSession != null
            ) {
                candidate = preview
            }
        }
        val initialPreview = candidate ?: return RawCaptureOutcome.Failed(StaleSession)
        val rawDescriptor = try {
            platform.resolveRawDescriptor(initialPreview.route)
        } catch (_: Throwable) {
            null
        } ?: return RawCaptureOutcome.Failed(RawUnsupported)

        var command: RawCaptureCommand? = null
        var previewSessionCleanup: CameraCleanupPlan? = null
        var prepareFailure: RawCaptureOutcome? = null
        mutationGate.mutate {
            val previewing = mutableState.value as? CameraEngineState.Previewing
            val preview = currentPreview
            val device = activeDevice
            if (
                previewing?.firstFrameVerified != true ||
                preview == null ||
                preview != initialPreview ||
                preview.selection != previewing.selection ||
                preview.route.physicalCameraId != null ||
                device == null ||
                activeSession == null
            ) {
                prepareFailure = RawCaptureOutcome.Failed(StaleSession)
                return@mutate
            }
            check(nextCaptureToken < Long.MAX_VALUE) { "RAW capture token space exhausted" }
            val captureToken = CaptureToken(++nextCaptureToken)
            val next = generations.advanceSession()
            val rawSelection = preview.selection.copy(sessionGeneration = next.session)
            val rawPreview = preview.copy(selection = rawSelection)
            val outputPlan = CameraSessionOutputPlan.temporaryRaw(
                previewSurfaceIdentity = preview.surface.identity,
                captureToken = captureToken,
            )
            currentPreview = rawPreview
            asyncOwnership.publishIntent(
                CameraOperationIdentity(
                    selection = rawSelection,
                    surface = rawPreview.surface.identity,
                    previewAttempt = rawPreview.attempt,
                    captureToken = captureToken,
                ),
            )
            val deviceEventPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
            device.eventPermit = deviceEventPermit
            device.openCommand.deviceEventPermit.set(deviceEventPermit)
            previewSessionCleanup = detachSessionLocked()
            transition(CameraEngineState.ConfiguringRaw(rawSelection, captureToken))
            mark(CameraStartupMilestone.SHUTTER_PRESS, rawSelection)
            val context = RawCaptureContext(
                captureToken = captureToken,
                selectionGeneration = rawSelection.selectionGeneration,
                sessionGeneration = rawSelection.sessionGeneration,
                canonicalLensFingerprint = rawSelection.canonicalLensFingerprint,
                cameraProfileFingerprint = rawSelection.profileFingerprint,
                routeId = rawSelection.routeId,
                displayRotationAtShutter = displayRotation,
                sensorOrientationDegrees = rawDescriptor.sensorOrientationDegrees,
                lensFacing = rawDescriptor.lensFacing,
                rawSize = rawDescriptor.rawSize,
                timeoutMillis = RawContractLimits.DEFAULT_TIMEOUT_MILLIS,
            )
            command = RawCaptureCommand(
                preview = rawPreview,
                outputPlan = outputPlan,
                context = context,
                device = device.handle,
                characteristics = rawDescriptor.characteristics,
            )
        }
        closePlan(previewSessionCleanup)
        prepareFailure?.let { return it }
        return executeRawCapture(platform, checkNotNull(command), writer)
    }

    suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        var cleanup: CameraCleanupPlan? = null
        var permit: PendingCameraOperationPermit? = null
        mutationGate.mutate {
            val surface = activeSurface
            if (surface == null || surface.identity != identity || mutableState.value == CameraEngineState.Closed) {
                return@mutate
            }
            val previous = currentPreview
            val next = generations.advanceSession()
            val selection = mutableState.value.selectionOrNull()?.copy(sessionGeneration = next.session)
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            currentPreview = null
            transition(CameraEngineState.Pausing(selection))
            if (selection != null && previous != null) {
                asyncOwnership.publishIntent(
                    CameraOperationIdentity(selection, identity, previous.attempt),
                )
                permit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
            } else {
                transition(CameraEngineState.WaitingForSurface(selection))
            }
        }
        closePlan(cleanup)
        completePauseCleanup(permit)
    }

    suspend fun pause() {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        var cleanup: CameraCleanupPlan? = null
        var permit: PendingCameraOperationPermit? = null
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            if (mutableState.value == CameraEngineState.Closed) return@mutate
            val previousSurface = activeSurface
            val previousPreview = currentPreview
            val next = generations.advanceSession()
            val selection = mutableState.value.selectionOrNull()?.copy(sessionGeneration = next.session)
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            currentPreview = null
            transition(CameraEngineState.Pausing(selection))
            if (selection != null && previousSurface != null && previousPreview != null) {
                asyncOwnership.publishIntent(
                    CameraOperationIdentity(selection, previousSurface.identity, previousPreview.attempt),
                )
                permit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
            } else {
                transition(CameraEngineState.WaitingForSurface(selection))
            }
        }
        closePlan(cleanup)
        completePauseCleanup(permit)
    }

    suspend fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            withContext(NonCancellable) { shutdownComplete.await() }
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            var cleanup: CameraCleanupPlan? = null
            try {
                mutationGate.mutate {
                    if (mutableState.value != CameraEngineState.Closed) {
                        val next = generations.advanceSession()
                        val selection = mutableState.value.selectionOrNull()?.copy(sessionGeneration = next.session)
                        asyncOwnership.shutdown()
                        cleanup = detachAllLocked()
                        currentPreview = null
                        if (mutableState.value !is CameraEngineState.Pausing) {
                            transition(CameraEngineState.Pausing(selection))
                        }
                    }
                }
                closePlan(cleanup)
                mutationGate.mutate {
                    if (mutableState.value != CameraEngineState.Closed) transition(CameraEngineState.Closed)
                    mutableResources.value = CameraResourceSnapshot()
                    mutableRawPhotoAvailable.value = false
                }
            } catch (error: Throwable) {
                failure = error
            }
            callbackScope.cancel()
            try {
                runtime.shutdownWorker()
            } catch (error: Throwable) {
                val primary = failure
                if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
            } finally {
                val terminal = failure
                if (terminal == null) shutdownComplete.complete(Unit)
                else shutdownComplete.completeExceptionally(terminal)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun executeRawCapture(
        platform: AndroidCameraOwnerPlatform,
        command: RawCaptureCommand,
        writer: AndroidDngWriter,
    ): RawCaptureOutcome {
        val resources = try {
            platform.configureRawSession(
                device = command.device,
                previewSurfaceToken = command.preview.surface.token,
                rawSize = command.context.rawSize,
                timeoutMillis = command.context.timeoutMillis,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                restoreAfterRaw(command, RawCaptureOutcome.Cancelled)
            }
            throw cancelled
        } catch (_: Throwable) {
            return restoreAfterRaw(command, RawCaptureOutcome.Failed(RawSessionRejected))
        }

        var adopted = false
        mutationGate.mutate {
            val state = mutableState.value as? CameraEngineState.ConfiguringRaw
            if (
                state?.token == command.context.captureToken &&
                state.selection == command.preview.selection &&
                currentPreview == command.preview &&
                activeDevice?.handle === command.device &&
                activeSession == null
            ) {
                activeSession = ActiveSession(resources.session, resources.sessionCleanup)
                activeRawReader = ActiveRawReader(resources.readerCleanup)
                updateResourcesLocked()
                transition(CameraEngineState.CapturingRaw(state.selection, state.token))
                mark(CameraStartupMilestone.RAW_SESSION_READY, state.selection)
                mark(CameraStartupMilestone.RAW_REQUEST, state.selection)
                adopted = true
            }
        }
        if (!adopted) {
            closeRawResources(resources)
            return RawCaptureOutcome.Cancelled
        }

        val pair = try {
            platform.captureRawPair(
                device = command.device,
                resources = resources,
                timeoutMillis = command.context.timeoutMillis,
                onImage = { mark(CameraStartupMilestone.RAW_IMAGE, command.preview.selection) },
                onResult = { mark(CameraStartupMilestone.RAW_RESULT, command.preview.selection) },
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                restoreAfterRaw(command, RawCaptureOutcome.Cancelled)
            }
            throw cancelled
        } catch (_: TimeoutCancellationException) {
            return restoreAfterRaw(command, RawCaptureOutcome.Failed(RawPairTimeout))
        } catch (failure: RawCapturePlatformException) {
            return restoreAfterRaw(
                command,
                RawCaptureOutcome.Failed(RawCaptureRejected(failure.message ?: "RAW capture rejected")),
            )
        } catch (failure: Throwable) {
            return restoreAfterRaw(
                command,
                RawCaptureOutcome.Failed(RawCaptureRejected(failure.message ?: "RAW capture failed")),
            )
        }

        var image: Image? = null
        var acceptedForWrite = false
        mutationGate.mutate {
            val state = mutableState.value as? CameraEngineState.CapturingRaw
            if (
                state?.token == command.context.captureToken &&
                state.selection == command.preview.selection &&
                currentPreview == command.preview
            ) {
                transition(CameraEngineState.PairingRaw(state.selection, state.token))
                mark(CameraStartupMilestone.RAW_PAIR, state.selection)
                val movedImage = pair.takeImage()
                activeRawImage = ActiveRawImage(CameraResourceCleanup(movedImage::close))
                updateResourcesLocked()
                transition(CameraEngineState.WritingDng(state.selection, state.token))
                mark(CameraStartupMilestone.DNG_WRITE_START, state.selection)
                image = movedImage
                acceptedForWrite = true
            }
        }
        if (!acceptedForWrite) {
            pair.close()
            return RawCaptureOutcome.Cancelled
        }
        pair.close()

        val writeOutcome = try {
            writer.write(
                context = command.context,
                characteristics = command.characteristics,
                result = pair.result,
                image = checkNotNull(image),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                restoreAfterRaw(command, RawCaptureOutcome.Cancelled)
            }
            throw cancelled
        } catch (failure: Throwable) {
            RawCaptureOutcome.Failed(DngWriteFailure(failure.message ?: "DNG write failed"))
        }

        mutationGate.mutate {
            val state = mutableState.value as? CameraEngineState.WritingDng
            if (state?.token == command.context.captureToken && state.selection == command.preview.selection) {
                mark(CameraStartupMilestone.DNG_WRITE_END, state.selection)
            }
        }
        return restoreAfterRaw(command, writeOutcome)
    }

    private suspend fun restoreAfterRaw(
        command: RawCaptureCommand,
        outcome: RawCaptureOutcome,
    ): RawCaptureOutcome {
        var cleanup: CameraCleanupPlan? = null
        var configure: ConfigureCommand? = null
        var result = outcome
        mutationGate.mutate {
            val state = mutableState.value
            val rawIdentityMatches = when (state) {
                is CameraEngineState.ConfiguringRaw -> state.token == command.context.captureToken
                is CameraEngineState.CapturingRaw -> state.token == command.context.captureToken
                is CameraEngineState.PairingRaw -> state.token == command.context.captureToken
                is CameraEngineState.WritingDng -> state.token == command.context.captureToken
                else -> false
            }
            if (!rawIdentityMatches) {
                result = RawCaptureOutcome.Cancelled
                return@mutate
            }

            val selection = checkNotNull(state.selectionOrNull())
            cleanup = detachRawTransactionLocked()
            val device = activeDevice
            val preview = currentPreview
            if (device == null || preview == null || preview.selection != selection) {
                asyncOwnership.invalidatePending()
                currentPreview = null
                transition(CameraEngineState.RecoverableError(selection, StaleSession))
                result = RawCaptureOutcome.Failed(StaleSession)
                return@mutate
            }

            val next = generations.advanceSession()
            val restoredSelection = preview.selection.copy(sessionGeneration = next.session)
            val restoredPreview = preview.copy(
                selection = restoredSelection,
                attempt = PreviewConfigurationAttemptKind.SAFE_BASELINE,
            )
            currentPreview = restoredPreview
            asyncOwnership.publishIntent(restoredPreview.identity())
            val deviceEventPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
            device.eventPermit = deviceEventPermit
            device.openCommand.deviceEventPermit.set(deviceEventPermit)
            transition(
                CameraEngineState.RestoringPreview(
                    selection = restoredSelection,
                    token = command.context.captureToken,
                ),
            )
            mark(CameraStartupMilestone.SESSION_CONFIG_REQUESTED, restoredSelection)
            configure = ConfigureCommand(
                preview = restoredPreview,
                configurationPermit = asyncOwnership.begin(PendingCameraStage.PREVIEW_CONFIGURATION),
                device = device.handle,
            )
        }
        closePlan(cleanup)
        configure?.let(::issueConfigure)
        return result
    }

    private suspend fun startPreviewInternal(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        surface: SurfaceInput,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) {
        check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
        require(route.id == selection.routeId) { "Active selection route must match CameraRoute" }
        var cleanup: CameraCleanupPlan? = null
        var switch: SwitchCommand? = null
        mutationGate.mutate {
            check(!shutdownRequested.get()) { "CameraSessionController is shut down" }
            val current = mutableState.value
            CameraStateTransitions.requirePhaseAllowed(current, CameraEnginePhase.SWITCHING)
            val next = generations.advanceSelection()
            asyncOwnership.invalidatePending()
            cleanup = detachAllLocked()
            val effective = selection.copy(
                selectionGeneration = next.selection,
                sessionGeneration = next.session,
            )
            val active = ActiveSurface(
                surface.identity,
                surface.token,
                CameraResourceCleanup(surface.close),
            )
            activeSurface = active
            val preview = PreviewIntent(
                effective,
                route,
                active,
                configuration,
                settings,
                PreviewConfigurationAttemptKind.REQUESTED,
            )
            currentPreview = preview
            updateResourcesLocked()
            asyncOwnership.publishIntent(preview.identity())
            val cleanupPermit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
            transition(CameraEngineState.Switching(current.selectionOrNull()?.routeId, effective))
            switch = SwitchCommand(preview, cleanupPermit)
        }
        closePlan(cleanup)
        val command = switch ?: return
        var open: OpenCommand? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.cleanupPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val preview = currentPreview ?: return@mutate
            if (preview.identity() != command.preview.identity()) return@mutate
            val openPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
            transition(CameraEngineState.Opening(preview.selection, preview.selection.sessionGeneration))
            mark(CameraStartupMilestone.OPEN_REQUESTED, preview.selection)
            open = OpenCommand(preview, openPermit)
        }
        open?.let(::issueOpen)
    }

    private fun issueOpen(command: OpenCommand) {
        try {
            runtime.platform.open(
                command.preview.route.openCameraId,
                object : CameraOpenCallbacks {
                    override fun onOpened(delivery: CloseOnceCameraResource<CameraDeviceHandle>) {
                        dispatchDelivered(delivery) { handleOpened(command, delivery) }
                    }

                    override fun onDisconnected(delivery: CloseOnceCameraResource<CameraDeviceHandle>) {
                        dispatchDelivered(delivery) {
                            handleDeviceTerminal(command, delivery, CameraDisconnected)
                        }
                    }

                    override fun onError(
                        delivery: CloseOnceCameraResource<CameraDeviceHandle>,
                        platformCode: Int,
                    ) {
                        dispatchDelivered(delivery) {
                            handleDeviceTerminal(command, delivery, platformDeviceFailure(platformCode))
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleOpenInvocationFailure(command, mapOpenInvocationFailure(error)) }
            }
        }
    }

    private suspend fun handleOpened(
        command: OpenCommand,
        delivery: CloseOnceCameraResource<CameraDeviceHandle>,
    ) {
        var staleCleanup: CameraResourceCleanup? = null
        var configure: ConfigureCommand? = null
        mutationGate.mutate {
            when (val adoption = asyncOwnership.resolveResource(command.openPermit, delivery)) {
                is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
                is ResourceAdoption.Adopted -> {
                    val preview = currentPreview
                    if (preview == null || preview.identity() != command.preview.identity()) {
                        staleCleanup = CameraResourceCleanup(adoption.resource::close)
                        return@mutate
                    }
                    val deviceEventPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
                    command.deviceEventPermit.set(deviceEventPermit)
                    activeDevice = ActiveDevice(
                        adoption.resource,
                        CameraResourceCleanup(adoption.resource::close),
                        deviceEventPermit,
                        command,
                    )
                    updateResourcesLocked()
                    transition(CameraEngineState.ConfiguringPreview(preview.selection, preview.attempt))
                    mark(CameraStartupMilestone.CAMERA_OPENED, preview.selection)
                    val configPermit = asyncOwnership.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
                    mark(CameraStartupMilestone.SESSION_CONFIG_REQUESTED, preview.selection)
                    configure = ConfigureCommand(preview, configPermit, adoption.resource)
                }
            }
        }
        closeCleanup(staleCleanup)
        configure?.let(::issueConfigure)
    }

    private fun issueConfigure(command: ConfigureCommand) {
        try {
            runtime.platform.configurePreviewTargeted(
                command.device,
                command.preview.surface.token,
                command.preview.route.physicalCameraId,
                command.preview.configuration,
                command.preview.settings,
                command.preview.attempt,
                object : CameraSessionCallbacks {
                    override fun onConfigured(
                        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                        request: PreparedPreviewRequest,
                    ) {
                        dispatchDelivered(delivery) { handleConfigured(command, delivery, request) }
                    }

                    override fun onConfigureFailed(
                        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
                    ) {
                        dispatchDelivered(delivery) { handleConfigureFailed(command, delivery) }
                    }
                },
            )
        } catch (_: Throwable) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleConfigureInvocationFailure(command) }
            }
        }
    }

    private suspend fun handleConfigured(
        command: ConfigureCommand,
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
        request: PreparedPreviewRequest,
    ) {
        var staleCleanup: CameraResourceCleanup? = null
        var repeating: RepeatingCommand? = null
        mutationGate.mutate {
            when (val adoption = asyncOwnership.resolveResource(command.configurationPermit, delivery)) {
                is ResourceAdoption.Stale -> staleCleanup = adoption.cleanup
                is ResourceAdoption.Adopted -> {
                    val preview = currentPreview
                    if (preview == null || preview.identity() != command.preview.identity()) {
                        staleCleanup = CameraResourceCleanup(adoption.resource::close)
                        return@mutate
                    }
                    activeSession = ActiveSession(
                        adoption.resource,
                        CameraResourceCleanup(adoption.resource::close),
                    )
                    updateResourcesLocked()
                    mark(CameraStartupMilestone.SESSION_CONFIGURED, preview.selection)
                    val repeatingPermit = asyncOwnership.begin(PendingCameraStage.CLEANUP)
                    val firstFramePermit = asyncOwnership.begin(PendingCameraStage.FIRST_FRAME)
                    repeating = RepeatingCommand(
                        preview,
                        repeatingPermit,
                        firstFramePermit,
                        adoption.resource,
                        request,
                    )
                }
            }
        }
        closeCleanup(staleCleanup)
        repeating?.let(::issueRepeating)
    }

    private fun issueRepeating(command: RepeatingCommand) {
        try {
            runtime.platform.startRepeating(command.session, command.request) {
                if (!shutdownRequested.get()) {
                    callbackScope.launch { handleFirstFrame(command.firstFramePermit) }
                }
            }
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleRepeatingStarted(command) }
            }
        } catch (_: Throwable) {
            if (!shutdownRequested.get()) {
                callbackScope.launch { handleRepeatingRejected(command) }
            }
        }
    }

    private suspend fun handleRepeatingStarted(command: RepeatingCommand) {
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.repeatingPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val preview = currentPreview ?: return@mutate
            if (preview.identity() != command.preview.identity()) return@mutate
            transition(CameraEngineState.Previewing(preview.selection, firstFrameVerified = false))
        }
    }

    private suspend fun handleFirstFrame(permit: PendingCameraOperationPermit) {
        mutationGate.mutate {
            val previewing = mutableState.value as? CameraEngineState.Previewing ?: return@mutate
            if (previewing.firstFrameVerified || permit.intent.selection != previewing.selection) return@mutate
            if (asyncOwnership.completeSignal(permit) != CameraCallbackDecision.ACCEPTED) return@mutate
            mark(CameraStartupMilestone.FIRST_CAPTURE_RESULT, previewing.selection)
            mark(CameraStartupMilestone.FIRST_PREVIEW_FRAME, previewing.selection)
            if (permit.intent.captureToken != null) {
                mark(CameraStartupMilestone.PREVIEW_RESTORED, previewing.selection)
            }
            transition(previewing.copy(firstFrameVerified = true))
        }
    }

    private suspend fun handleConfigureFailed(
        command: ConfigureCommand,
        delivery: CloseOnceCameraResource<CameraCaptureSessionHandle>,
    ) {
        var deliveredCleanup: CameraResourceCleanup? = null
        var rejection: PreviewRejection? = null
        mutationGate.mutate {
            when (val resolution = asyncOwnership.resolveResource(command.configurationPermit, delivery)) {
                is ResourceAdoption.Stale -> deliveredCleanup = resolution.cleanup
                is ResourceAdoption.Adopted -> {
                    deliveredCleanup = CameraResourceCleanup(resolution.resource::close)
                    rejection = rejectPreviewAttemptLocked(command.preview)
                }
            }
        }
        closeCleanup(deliveredCleanup)
        val outcome = rejection
        closePlan(outcome?.cleanup)
        outcome?.next?.let(::issueConfigure)
    }

    private suspend fun handleConfigureInvocationFailure(command: ConfigureCommand) {
        var rejection: PreviewRejection? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.configurationPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            rejection = rejectPreviewAttemptLocked(command.preview)
        }
        val outcome = rejection
        closePlan(outcome?.cleanup)
        outcome?.next?.let(::issueConfigure)
    }

    private suspend fun handleRepeatingRejected(command: RepeatingCommand) {
        var rejection: PreviewRejection? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.repeatingPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val preview = currentPreview
            if (preview == null || preview.identity() != command.preview.identity()) return@mutate
            val sessionCleanup = detachSessionLocked()
            val rejected = rejectPreviewAttemptLocked(preview)
            rejection = PreviewRejection(
                rejected.next,
                combineCleanup(sessionCleanup, rejected.cleanup),
            )
        }
        val outcome = rejection
        closePlan(outcome?.cleanup)
        outcome?.next?.let(::issueConfigure)
    }

    private fun rejectPreviewAttemptLocked(rejected: PreviewIntent): PreviewRejection {
        if (currentPreview?.identity() != rejected.identity()) return PreviewRejection()
        if (rejected.attempt == PreviewConfigurationAttemptKind.SAFE_BASELINE) {
            asyncOwnership.invalidatePending()
            currentPreview = null
            val cleanup = detachAllLocked()
            transition(
                CameraEngineState.StructuralError(
                    rejected.selection,
                    SafeBaselineConfigurationRejected,
                ),
            )
            return PreviewRejection(cleanup = cleanup)
        }
        val state = mutableState.value
        if (state !is CameraEngineState.ConfiguringPreview ||
            state.attempt != PreviewConfigurationAttemptKind.REQUESTED
        ) return PreviewRejection()
        val device = activeDevice ?: return PreviewRejection()
        val requestedFailure = RequestedConfigurationRejected(requestedFailureKind(rejected))
        check(requestedFailure.policy.fallbackPermitted && !requestedFailure.policy.structural)
        val next = generations.advanceSession()
        val baseline = rejected.copy(
            selection = rejected.selection.copy(sessionGeneration = next.session),
            attempt = PreviewConfigurationAttemptKind.SAFE_BASELINE,
        )
        asyncOwnership.publishIntent(baseline.identity())
        val deviceEventPermit = asyncOwnership.begin(PendingCameraStage.OPEN)
        device.eventPermit = deviceEventPermit
        device.openCommand.deviceEventPermit.set(deviceEventPermit)
        currentPreview = baseline
        transition(CameraEngineState.ConfiguringPreview(baseline.selection, baseline.attempt))
        val configPermit = asyncOwnership.begin(PendingCameraStage.PREVIEW_CONFIGURATION)
        mark(CameraStartupMilestone.SESSION_CONFIG_REQUESTED, baseline.selection)
        return PreviewRejection(
            next = ConfigureCommand(baseline, configPermit, device.handle),
        )
    }

    private fun requestedFailureKind(preview: PreviewIntent): RequestedConfigurationKind = when {
        preview.settings.fpsRequest.overrideEnabled -> RequestedConfigurationKind.EXACT_FPS_RANGE
        preview.configuration.highResolutionViewfinder -> RequestedConfigurationKind.HIGH_RESOLUTION_PREVIEW
        else -> RequestedConfigurationKind.ENHANCEMENT
    }

    private suspend fun handleOpenInvocationFailure(command: OpenCommand, failure: CameraFailure) {
        var cleanup: CameraCleanupPlan? = null
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(command.openPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
            cleanup = failCurrentLocked(command.preview, failure)
        }
        closePlan(cleanup)
    }

    private suspend fun handleDeviceTerminal(
        command: OpenCommand,
        delivery: CloseOnceCameraResource<CameraDeviceHandle>,
        failure: CameraFailure,
    ) {
        var cleanup: CameraCleanupPlan? = null
        var deliveredCleanup: CameraResourceCleanup? = null
        mutationGate.mutate {
            val eventPermit = command.deviceEventPermit.get()
            if (eventPermit != null) {
                if (asyncOwnership.completeSignal(eventPermit) != CameraCallbackDecision.ACCEPTED) return@mutate
                cleanup = failCurrentLocked(currentPreview ?: command.preview, failure)
                return@mutate
            }
            when (val resolution = asyncOwnership.resolveResource(command.openPermit, delivery)) {
                is ResourceAdoption.Stale -> deliveredCleanup = resolution.cleanup
                is ResourceAdoption.Adopted -> {
                    deliveredCleanup = CameraResourceCleanup(resolution.resource::close)
                    cleanup = failCurrentLocked(command.preview, failure)
                }
            }
        }
        closeCleanup(deliveredCleanup)
        closePlan(cleanup)
    }

    private fun failCurrentLocked(preview: PreviewIntent, failure: CameraFailure): CameraCleanupPlan? {
        val next = generations.advanceSession()
        val failed = preview.selection.copy(sessionGeneration = next.session)
        asyncOwnership.invalidatePending()
        currentPreview = null
        val cleanup = detachAllLocked()
        if (failure.policy.structural) transition(CameraEngineState.StructuralError(failed, failure))
        else transition(CameraEngineState.RecoverableError(failed, failure))
        return cleanup
    }

    private suspend fun completePauseCleanup(permit: PendingCameraOperationPermit?) {
        if (permit == null) return
        mutationGate.mutate {
            if (asyncOwnership.completeSignal(permit) != CameraCallbackDecision.ACCEPTED) return@mutate
            val pausing = mutableState.value as? CameraEngineState.Pausing ?: return@mutate
            asyncOwnership.invalidatePending()
            transition(CameraEngineState.WaitingForSurface(pausing.selection))
        }
    }

    private fun detachAllLocked(): CameraCleanupPlan? {
        val cleanups = ArrayList<CameraResourceCleanup>(5)
        activeRawImage?.let { cleanups += it.cleanup }
        activeRawReader?.let { cleanups += it.cleanup }
        activeSession?.let { cleanups += it.cleanup }
        activeDevice?.let { cleanups += it.cleanup }
        activeSurface?.let { cleanups += it.cleanup }
        activeRawImage = null
        activeRawReader = null
        activeSession = null
        activeDevice = null
        activeSurface = null
        updateResourcesLocked()
        return if (cleanups.isEmpty()) null else CameraCleanupPlan(cleanups)
    }

    private fun detachRawTransactionLocked(): CameraCleanupPlan? {
        val cleanups = ArrayList<CameraResourceCleanup>(3)
        activeRawImage?.let { cleanups += it.cleanup }
        activeRawReader?.let { cleanups += it.cleanup }
        activeSession?.let { cleanups += it.cleanup }
        activeRawImage = null
        activeRawReader = null
        activeSession = null
        updateResourcesLocked()
        return if (cleanups.isEmpty()) null else CameraCleanupPlan(cleanups)
    }

    private fun detachSessionLocked(): CameraCleanupPlan? {
        val session = activeSession ?: return null
        activeSession = null
        updateResourcesLocked()
        return CameraCleanupPlan(listOf(session.cleanup))
    }

    private fun combineCleanup(first: CameraCleanupPlan?, second: CameraCleanupPlan?): CameraCleanupPlan? = when {
        first == null -> second
        second == null -> first
        else -> CameraCleanupPlan(
            listOf(
                CameraResourceCleanup { first.closeAllOnce() },
                CameraResourceCleanup { second.closeAllOnce() },
            ),
        )
    }

    private fun updateResourcesLocked() {
        mutableResources.value = CameraResourceSnapshot(
            cameraDevices = if (activeDevice == null) 0 else 1,
            captureSessions = if (activeSession == null) 0 else 1,
            ownedSurfaces = if (activeSurface == null) 0 else 1,
            imageReaders = if (activeRawReader == null) 0 else 1,
            openImages = if (activeRawImage == null) 0 else 1,
            cameraWorkers = if (shutdownRequested.get()) 0 else runtime.workerCount,
        )
    }

    private fun mark(milestone: CameraStartupMilestone, selection: ActiveCameraSelection) {
        trace.mark(
            milestone,
            elapsedRealtimeNs(),
            selection.selectionGeneration,
            selection.sessionGeneration,
        )
    }

    private fun transition(next: CameraEngineState) {
        CameraStateTransitions.requireAllowed(mutableState.value, next)
        mutableState.value = next
        val preview = currentPreview
        mutableRawPhotoAvailable.value =
            next is CameraEngineState.Previewing &&
            next.firstFrameVerified &&
            preview != null &&
            preview.selection == next.selection &&
            preview.route.physicalCameraId == null
    }

    private fun <T> dispatchDelivered(
        delivery: CloseOnceCameraResource<T>,
        block: suspend () -> Unit,
    ) {
        if (shutdownRequested.get()) {
            closeCleanup(delivery.detachForStaleCleanup())
        } else {
            callbackScope.launch { block() }
        }
    }

    private fun closeCleanup(cleanup: CameraResourceCleanup?) {
        if (cleanup == null) return
        try {
            cleanup.closeOnce()
        } catch (_: Throwable) {
            // Close has no authority to mutate current state; all detached cleanups are best effort.
        }
    }

    private fun closePlan(plan: CameraCleanupPlan?) {
        if (plan == null) return
        try {
            plan.closeAllOnce()
        } catch (_: Throwable) {
            // CameraCleanupPlan already attempts every detached cleanup and preserves close ordering.
        }
    }

    private fun closeRawResources(resources: AndroidRawSessionResources) {
        closeCleanup(resources.sessionCleanup)
        closeCleanup(resources.readerCleanup)
    }

    private data class SurfaceInput(
        val identity: PreviewSurfaceIdentity,
        val token: Any,
        val close: () -> Unit,
    )

    private data class ActiveSurface(
        val identity: PreviewSurfaceIdentity,
        val token: Any,
        val cleanup: CameraResourceCleanup,
    )

    private data class ActiveSession(
        val handle: CameraCaptureSessionHandle,
        val cleanup: CameraResourceCleanup,
    )

    private data class ActiveRawReader(val cleanup: CameraResourceCleanup)

    private data class ActiveRawImage(val cleanup: CameraResourceCleanup)

    private data class ActiveDevice(
        val handle: CameraDeviceHandle,
        val cleanup: CameraResourceCleanup,
        var eventPermit: PendingCameraOperationPermit,
        val openCommand: OpenCommand,
    )

    private data class PreviewIntent(
        val selection: ActiveCameraSelection,
        val route: CameraRoute,
        val surface: ActiveSurface,
        val configuration: PreviewConfiguration,
        val settings: SettingsSnapshot,
        val attempt: PreviewConfigurationAttemptKind,
    ) {
        fun identity() = CameraOperationIdentity(selection, surface.identity, attempt)
    }

    private data class SwitchCommand(
        val preview: PreviewIntent,
        val cleanupPermit: PendingCameraOperationPermit,
    )

    private data class OpenCommand(
        val preview: PreviewIntent,
        val openPermit: PendingCameraOperationPermit,
        val deviceEventPermit: AtomicReference<PendingCameraOperationPermit?> = AtomicReference(null),
    )

    private data class ConfigureCommand(
        val preview: PreviewIntent,
        val configurationPermit: PendingCameraOperationPermit,
        val device: CameraDeviceHandle,
    )

    private data class PreviewRejection(
        val next: ConfigureCommand? = null,
        val cleanup: CameraCleanupPlan? = null,
    )

    private data class RepeatingCommand(
        val preview: PreviewIntent,
        val repeatingPermit: PendingCameraOperationPermit,
        val firstFramePermit: PendingCameraOperationPermit,
        val session: CameraCaptureSessionHandle,
        val request: PreparedPreviewRequest,
    )

    private data class RawCaptureCommand(
        val preview: PreviewIntent,
        val outputPlan: CameraSessionOutputPlan,
        val context: RawCaptureContext,
        val device: CameraDeviceHandle,
        val characteristics: CameraCharacteristics,
    ) {
        init {
            require(outputPlan.captureToken == context.captureToken)
            require(outputPlan.previewSurfaceIdentity == preview.surface.identity)
        }
    }

    private data class AndroidRawDescriptor(
        val characteristics: CameraCharacteristics,
        val rawSize: IntSize,
        val sensorOrientationDegrees: Int,
        val lensFacing: LensFacing,
    )

    private data class AndroidRawSessionResources(
        val session: CameraCaptureSessionHandle,
        val sessionCleanup: CameraResourceCleanup,
        val reader: ImageReader,
        val readerCleanup: CameraResourceCleanup,
        val rawSurface: Surface,
    )

    private class RawCapturePlatformException(message: String) : Exception(message)

    private data class ControllerRuntime(
        val platform: CameraOwnerPlatform,
        val dispatcher: CoroutineDispatcher,
        val shutdownWorker: suspend () -> Unit,
        val workerCount: Int,
    ) {
        init { require(workerCount in 0..1) { "At most one camera callback worker is supported" } }
    }

    private class AndroidDeviceHandle(val device: CameraDevice) : CameraDeviceHandle {
        override fun close() = device.close()
    }

    private class AndroidSessionHandle(val session: CameraCaptureSession) : CameraCaptureSessionHandle {
        override fun close() = session.close()
    }

    private class AndroidPreparedPreviewRequest(val request: CaptureRequest) : PreparedPreviewRequest

    private class AndroidCameraOwnerPlatform(
        private val cameraManager: CameraManager,
        private val callbackHandler: Handler,
    ) : CameraOwnerPlatform {
        @SuppressLint("MissingPermission")
        override fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks) {
            var delivered: CloseOnceCameraResource<CameraDeviceHandle>? = null
            fun deliveryFor(device: CameraDevice): CloseOnceCameraResource<CameraDeviceHandle> {
                delivered?.let { return it }
                return CloseOnceCameraResource<CameraDeviceHandle>(
                    AndroidDeviceHandle(device),
                    CameraDeviceHandle::close,
                ).also { delivered = it }
            }
            cameraManager.openCamera(
                cameraId.value,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) = callbacks.onOpened(deliveryFor(camera))
                    override fun onDisconnected(camera: CameraDevice) = callbacks.onDisconnected(deliveryFor(camera))
                    override fun onError(camera: CameraDevice, error: Int) = callbacks.onError(deliveryFor(camera), error)
                },
                callbackHandler,
            )
        }

        override fun configurePreview(
            device: CameraDeviceHandle,
            surfaceToken: Any,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: CameraSessionCallbacks,
        ) = configurePreviewTargeted(
            device = device,
            surfaceToken = surfaceToken,
            physicalCameraId = null,
            configuration = configuration,
            settings = settings,
            attempt = attempt,
            callbacks = callbacks,
        )

        override fun configurePreviewTargeted(
            device: CameraDeviceHandle,
            surfaceToken: Any,
            physicalCameraId: PhysicalCameraId?,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: CameraSessionCallbacks,
        ) {
            val camera = (device as AndroidDeviceHandle).device
            val surface = surfaceToken as Surface
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            if (attempt == PreviewConfigurationAttemptKind.REQUESTED && settings.fpsRequest.overrideEnabled) {
                configuration.fps.resolvedRange?.let { range ->
                    builder.set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(range.minimum, range.maximum),
                    )
                }
            }
            val request = AndroidPreparedPreviewRequest(builder.build())
            var delivered: CloseOnceCameraResource<CameraCaptureSessionHandle>? = null
            fun deliveryFor(session: CameraCaptureSession): CloseOnceCameraResource<CameraCaptureSessionHandle> {
                delivered?.let { return it }
                return CloseOnceCameraResource<CameraCaptureSessionHandle>(
                    AndroidSessionHandle(session),
                    CameraCaptureSessionHandle::close,
                ).also { delivered = it }
            }
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    callbacks.onConfigured(deliveryFor(session), request)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    callbacks.onConfigureFailed(deliveryFor(session))
                }
            }
            if (physicalCameraId == null) {
                camera.createCaptureSession(
                    listOf(surface),
                    stateCallback,
                    callbackHandler,
                )
            } else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    throw UnsupportedOperationException("Physical preview output requires Android API 28+")
                }
                createPhysicalPreviewSession(camera, surface, physicalCameraId, stateCallback)
            }
        }

        fun resolveRawDescriptor(route: CameraRoute): AndroidRawDescriptor? {
            if (route.physicalCameraId != null) return null
            val characteristics = cameraManager.getCameraCharacteristics(route.openCameraId.value)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmpty()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in capabilities) return null
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val rawSizes = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
                .asSequence()
                .filter { it.width > 0 && it.height > 0 }
                .map { IntSize(it.width, it.height) }
                .distinct()
                .toList()
            val selected = rawSizes.maxWithOrNull(
                compareBy<IntSize>({ it.area }, { it.width }, { it.height }),
            ) ?: return null
            val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: return null
            if (orientation !in 0..270 || orientation % 90 != 0) return null
            val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            }
            return AndroidRawDescriptor(characteristics, selected, orientation, facing)
        }

        suspend fun configureRawSession(
            device: CameraDeviceHandle,
            previewSurfaceToken: Any,
            rawSize: IntSize,
            timeoutMillis: Long,
        ): AndroidRawSessionResources {
            val camera = (device as AndroidDeviceHandle).device
            val previewSurface = previewSurfaceToken as Surface
            val reader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                RAW_MAX_IMAGES,
            )
            val readerCleanup = CameraResourceCleanup(reader::close)
            val configured = CompletableDeferred<CameraCaptureSession>()
            try {
                camera.createCaptureSession(
                    listOf(previewSurface, reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!configured.complete(session)) session.close()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            configured.completeExceptionally(
                                RawCapturePlatformException("Temporary RAW session configuration was rejected"),
                            )
                        }
                    },
                    callbackHandler,
                )
                val session = withTimeout(timeoutMillis) { configured.await() }
                val handle = AndroidSessionHandle(session)
                val sessionCleanup = CameraResourceCleanup(handle::close)
                return AndroidRawSessionResources(
                    session = handle,
                    sessionCleanup = sessionCleanup,
                    reader = reader,
                    readerCleanup = readerCleanup,
                    rawSurface = reader.surface,
                )
            } catch (failure: Throwable) {
                readerCleanup.closeOnce()
                throw failure
            }
        }

        suspend fun captureRawPair(
            device: CameraDeviceHandle,
            resources: AndroidRawSessionResources,
            timeoutMillis: Long,
            onImage: () -> Unit,
            onResult: () -> Unit,
        ): RawPair<Image, CaptureResult> {
            val camera = (device as AndroidDeviceHandle).device
            val session = (resources.session as AndroidSessionHandle).session
            val pairer = RawTimestampPairer<Image, CaptureResult>(
                maximumEntries = RAW_PAIR_ENTRIES,
                timeoutMillis = timeoutMillis,
            )
            val paired = CompletableDeferred<RawPair<Image, CaptureResult>>()

            fun publish(candidate: RawPair<Image, CaptureResult>?) {
                if (candidate == null) return
                if (!paired.complete(candidate)) candidate.close()
            }

            resources.reader.setOnImageAvailableListener(
                { source ->
                    while (true) {
                        val image = try {
                            source.acquireNextImage()
                        } catch (failure: Throwable) {
                            paired.completeExceptionally(failure)
                            break
                        } ?: break
                        if (paired.isCompleted) {
                            image.close()
                        } else {
                            onImage()
                            publish(pairer.offerImage(image.timestamp, image))
                        }
                    }
                },
                callbackHandler,
            )

            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(resources.rawSurface)
                }.build()
                session.capture(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            if (timestamp == null || timestamp <= 0L) {
                                paired.completeExceptionally(
                                    RawCapturePlatformException("RAW capture result has no valid SENSOR_TIMESTAMP"),
                                )
                                return
                            }
                            onResult()
                            publish(pairer.offerResult(timestamp, result))
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            paired.completeExceptionally(
                                RawCapturePlatformException(
                                    "RAW capture failed reason=${failure.reason} sequence=${failure.sequenceId}",
                                ),
                            )
                        }

                        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                            paired.completeExceptionally(
                                RawCapturePlatformException("RAW capture sequence $sequenceId was aborted"),
                            )
                        }
                    },
                    callbackHandler,
                )
                return withTimeout(timeoutMillis) { paired.await() }
            } finally {
                resources.reader.setOnImageAvailableListener(null, null)
                pairer.close()
            }
        }

        @TargetApi(Build.VERSION_CODES.P)
        private fun createPhysicalPreviewSession(
            camera: CameraDevice,
            surface: Surface,
            physicalCameraId: PhysicalCameraId,
            callbacks: CameraCaptureSession.StateCallback,
        ) {
            val output = OutputConfiguration(surface)
            output.setPhysicalCameraId(physicalCameraId.value)
            camera.createCaptureSessionByOutputConfigurations(
                listOf(output),
                callbacks,
                callbackHandler,
            )
        }

        override fun startRepeating(
            session: CameraCaptureSessionHandle,
            request: PreparedPreviewRequest,
            onFrame: () -> Unit,
        ) {
            val realSession = (session as AndroidSessionHandle).session
            val realRequest = (request as AndroidPreparedPreviewRequest).request
            realSession.setRepeatingRequest(
                realRequest,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) = onFrame()
                },
                callbackHandler,
            )
        }
    }

    private companion object {
        const val RAW_MAX_IMAGES = 2
        const val RAW_PAIR_ENTRIES = 4

        fun createAndroidRuntime(cameraManager: CameraManager): ControllerRuntime {
            val thread = HandlerThread("camx-camera-control").apply { start() }
            val handler = Handler(thread.looper)
            return ControllerRuntime(
                AndroidCameraOwnerPlatform(cameraManager, handler),
                handler.asCoroutineDispatcher("camx-camera-control"),
                shutdownWorker = {
                    withContext(Dispatchers.IO) {
                        thread.quitSafely()
                        thread.join()
                    }
                },
                workerCount = 1,
            )
        }

        fun mapOpenInvocationFailure(error: Throwable): CameraFailure = when (error) {
            is SecurityException -> PermissionDenied(permanentlyDenied = false)
            is CameraAccessException -> when (error.reason) {
                CameraAccessException.CAMERA_IN_USE -> CameraInUse
                CameraAccessException.MAX_CAMERAS_IN_USE -> MaximumCamerasInUse
                CameraAccessException.CAMERA_DISABLED -> CameraDisabled
                else -> CameraDeviceError(error.reason)
            }
            else -> CameraDeviceError(-1)
        }

        fun platformDeviceFailure(code: Int): CameraFailure = when (code) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> CameraInUse
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> MaximumCamerasInUse
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraDisabled
            else -> CameraDeviceError(code)
        }
    }
}
