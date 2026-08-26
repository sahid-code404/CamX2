package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.diagnostics.CameraDeviceError
import com.sahidcode404.camx.core.camera.diagnostics.CameraDisconnected
import com.sahidcode404.camx.core.camera.diagnostics.SafeBaselineConfigurationRejected
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraResourceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStartupMilestone
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionControllerTest {
    @Test
    fun successfulOpenConfigureRepeatingAndFirstFrame() {
        val fixture = Fixture()
        fixture.start("a")
        assertTrue(fixture.controller.state.value is CameraEngineState.Opening)
        assertEquals(CameraResourceSnapshot(ownedSurfaces = 1, cameraWorkers = 1), fixture.controller.resources.value)

        val open = fixture.platform.opens.single()
        open.opened()
        assertTrue(fixture.controller.state.value is CameraEngineState.ConfiguringPreview)
        assertEquals(1, fixture.controller.resources.value.cameraDevices)
        assertEquals(1, fixture.platform.configurations.size)

        fixture.platform.configurations.single().configured()
        val previewing = fixture.controller.state.value as CameraEngineState.Previewing
        assertFalse(previewing.firstFrameVerified)
        assertEquals(1, fixture.platform.repeats.size)
        assertEquals(1, fixture.controller.resources.value.captureSessions)

        fixture.platform.repeats.single().frame()
        assertTrue((fixture.controller.state.value as CameraEngineState.Previewing).firstFrameVerified)
    }

    @Test
    fun duplicateFirstFrameIsIgnoredAndVerificationNeverRegresses() {
        val fixture = Fixture()
        fixture.startAndPreview("a")
        val repeat = fixture.platform.repeats.single()

        repeat.frame()
        repeat.frame()

        assertTrue((fixture.controller.state.value as CameraEngineState.Previewing).firstFrameVerified)
        assertEquals(
            1,
            fixture.controller.traceSnapshot().events.count {
                it.milestone == CameraStartupMilestone.FIRST_PREVIEW_FRAME
            },
        )
    }

    @Test
    fun staleOnOpenedClosesDeliveredDeviceExactlyOnce() {
        val fixture = Fixture()
        fixture.start("a")
        val stale = fixture.platform.opens.single()
        fixture.start("b")

        stale.opened()
        stale.opened()

        assertEquals(1, stale.device.closeCount)
        assertEquals(CameraRouteId("route:b"), fixture.controller.state.value.selectionOrNull()?.routeId)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
    }

    @Test
    fun staleOnConfiguredClosesDeliveredSessionExactlyOnce() {
        val fixture = Fixture()
        fixture.start("a")
        fixture.platform.opens.single().opened()
        val stale = fixture.platform.configurations.single()
        fixture.start("b")

        stale.configured()
        stale.configured()

        assertEquals(1, stale.session.closeCount)
        assertEquals(CameraRouteId("route:b"), fixture.controller.state.value.selectionOrNull()?.routeId)
        assertEquals(0, fixture.controller.resources.value.captureSessions)
    }

    @Test
    fun aToBWithLateAOpenLeavesBAsAuthoritativeIntent() {
        val fixture = Fixture()
        fixture.start("a")
        val openA = fixture.platform.opens.single()
        fixture.start("b")
        val openB = fixture.platform.opens.last()

        openA.opened()
        openB.opened()

        assertEquals(1, openA.device.closeCount)
        assertEquals(0, openB.device.closeCount)
        assertEquals(CameraRouteId("route:b"), fixture.controller.state.value.selectionOrNull()?.routeId)
        assertEquals(1, fixture.controller.resources.value.cameraDevices)
    }

    @Test
    fun aToBToCWithReorderedOpenCallbacksAdoptsOnlyC() {
        val fixture = Fixture()
        fixture.start("a")
        val openA = fixture.platform.opens.last()
        fixture.start("b")
        val openB = fixture.platform.opens.last()
        fixture.start("c")
        val openC = fixture.platform.opens.last()

        openB.opened()
        openA.opened()
        openC.opened()

        assertEquals(1, openA.device.closeCount)
        assertEquals(1, openB.device.closeCount)
        assertEquals(0, openC.device.closeCount)
        assertEquals(CameraRouteId("route:c"), fixture.controller.state.value.selectionOrNull()?.routeId)
        assertEquals(1, fixture.platform.configurations.size)
        assertSame(openC.device, fixture.platform.configurations.single().device)
    }

    @Test
    fun pauseDuringOpenClosesSurfaceAndLateDevice() {
        val fixture = Fixture()
        val surface = fixture.start("a")
        val open = fixture.platform.opens.single()

        runSuspend { fixture.controller.pause() }
        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
        assertEquals(1, surface.closeCount)
        open.opened()

        assertEquals(1, open.device.closeCount)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
        assertEquals(0, fixture.controller.resources.value.ownedSurfaces)
    }

    @Test
    fun pauseDuringConfigureClosesDeviceAndLateSession() {
        val fixture = Fixture()
        val surface = fixture.start("a")
        val open = fixture.platform.opens.single()
        open.opened()
        val configuration = fixture.platform.configurations.single()

        runSuspend { fixture.controller.pause() }
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        configuration.configured()

        assertEquals(1, configuration.session.closeCount)
        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
        assertEquals(0, fixture.controller.resources.value.captureSessions)
    }

    @Test
    fun shutdownDuringOpenClosesLateDeviceOutsideCancelledScope() {
        val fixture = Fixture()
        val surface = fixture.start("a")
        val open = fixture.platform.opens.single()

        runSuspend { fixture.controller.shutdown() }
        assertEquals(1, surface.closeCount)
        assertEquals(CameraEngineState.Closed, fixture.controller.state.value)
        open.opened()

        assertEquals(1, open.device.closeCount)
        assertEquals(CameraResourceSnapshot(), fixture.controller.resources.value)
    }

    @Test
    fun shutdownDuringConfigureClosesDeviceAndLateSession() {
        val fixture = Fixture()
        val surface = fixture.start("a")
        val open = fixture.platform.opens.single()
        open.opened()
        val configuration = fixture.platform.configurations.single()

        runSuspend { fixture.controller.shutdown() }
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        configuration.configured()

        assertEquals(1, configuration.session.closeCount)
        assertEquals(CameraEngineState.Closed, fixture.controller.state.value)
        assertEquals(CameraResourceSnapshot(), fixture.controller.resources.value)
    }

    @Test
    fun switchClosesPreviousActiveSessionDeviceAndSurface() {
        val fixture = Fixture()
        val surfaceA = fixture.startAndPreview("a")
        val openA = fixture.platform.opens.single()
        val sessionA = fixture.platform.configurations.single().session

        fixture.start("b")

        assertEquals(1, sessionA.closeCount)
        assertEquals(1, openA.device.closeCount)
        assertEquals(1, surfaceA.closeCount)
        assertEquals(CameraRouteId("route:b"), fixture.controller.state.value.selectionOrNull()?.routeId)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
        assertEquals(0, fixture.controller.resources.value.captureSessions)
        assertEquals(1, fixture.controller.resources.value.ownedSurfaces)
    }

    @Test
    fun pauseClosesAllActiveCameraResources() {
        val fixture = Fixture()
        val surface = fixture.startAndPreview("a")
        val open = fixture.platform.opens.single()
        val session = fixture.platform.configurations.single().session

        runSuspend { fixture.controller.pause() }

        assertEquals(1, session.closeCount)
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
        assertEquals(0, fixture.controller.resources.value.captureSessions)
        assertEquals(0, fixture.controller.resources.value.ownedSurfaces)
        assertEquals(1, fixture.controller.resources.value.cameraWorkers)
    }

    @Test
    fun shutdownClosesActiveResourcesAndDuplicateShutdownIsSafe() {
        val fixture = Fixture()
        val surface = fixture.startAndPreview("a")
        val open = fixture.platform.opens.single()
        val session = fixture.platform.configurations.single().session

        runSuspend { fixture.controller.shutdown() }
        runSuspend { fixture.controller.shutdown() }

        assertEquals(1, session.closeCount)
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        assertEquals(1, fixture.workerShutdownCount)
        assertEquals(CameraEngineState.Closed, fixture.controller.state.value)
        assertEquals(CameraResourceSnapshot(), fixture.controller.resources.value)
    }

    @Test
    fun disconnectAfterOpenUsesTypedRecoverableFailureAndClosesCurrentResources() {
        val fixture = Fixture()
        val surface = fixture.start("a")
        val open = fixture.platform.opens.single()
        open.opened()

        open.disconnected()

        val error = fixture.controller.state.value as CameraEngineState.RecoverableError
        assertEquals(CameraDisconnected, error.failure)
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
    }

    @Test
    fun deviceErrorAfterOpenIsTypedAndCannotLeakCurrentResources() {
        val fixture = Fixture()
        val surface = fixture.startAndPreview("a")
        val open = fixture.platform.opens.single()
        val session = fixture.platform.configurations.single().session

        open.error(4)

        val error = fixture.controller.state.value as CameraEngineState.RecoverableError
        assertTrue(error.failure is CameraDeviceError)
        assertEquals(1, session.closeCount)
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
        assertEquals(0, fixture.controller.resources.value.captureSessions)
    }

    @Test
    fun requestedConfigureRejectionIssuesExactlyOneNewSafeBaselineAttempt() {
        val fixture = Fixture(settings = requestedFpsSettings())
        fixture.start("a")
        fixture.platform.opens.single().opened()
        val requested = fixture.platform.configurations.single()
        val requestedState = fixture.controller.state.value as CameraEngineState.ConfiguringPreview

        requested.failed()

        assertEquals(2, fixture.platform.configurations.size)
        assertEquals(PreviewConfigurationAttemptKind.REQUESTED, fixture.platform.configurations[0].attempt)
        assertEquals(PreviewConfigurationAttemptKind.SAFE_BASELINE, fixture.platform.configurations[1].attempt)
        val baselineState = fixture.controller.state.value as CameraEngineState.ConfiguringPreview
        assertEquals(PreviewConfigurationAttemptKind.SAFE_BASELINE, baselineState.attempt)
        assertEquals(requestedState.selection.selectionGeneration, baselineState.selection.selectionGeneration)
        assertTrue(
            baselineState.selection.sessionGeneration.value > requestedState.selection.sessionGeneration.value,
        )
        assertEquals(1, requested.session.closeCount)
        assertEquals(0, fixture.platform.opens.single().device.closeCount)
    }

    @Test
    fun safeBaselineRejectionIsStructuralAndCannotLoop() {
        val fixture = Fixture(settings = requestedFpsSettings())
        val surface = fixture.start("a")
        val open = fixture.platform.opens.single()
        open.opened()
        fixture.platform.configurations[0].failed()
        val baseline = fixture.platform.configurations[1]

        baseline.failed()

        val error = fixture.controller.state.value as CameraEngineState.StructuralError
        assertEquals(SafeBaselineConfigurationRejected, error.failure)
        assertEquals(2, fixture.platform.configurations.size)
        assertEquals(1, fixture.platform.configurations[0].session.closeCount)
        assertEquals(1, baseline.session.closeCount)
        assertEquals(1, open.device.closeCount)
        assertEquals(1, surface.closeCount)
        assertEquals(0, fixture.controller.resources.value.cameraDevices)
        assertEquals(0, fixture.controller.resources.value.captureSessions)
    }

    @Test
    fun repeatingRejectionUsesTheSameOneShotBaselinePolicy() {
        val fixture = Fixture(settings = requestedFpsSettings())
        fixture.start("a")
        fixture.platform.opens.single().opened()
        fixture.platform.failNextRepeating = true

        fixture.platform.configurations.single().configured()

        assertEquals(2, fixture.platform.configurations.size)
        assertEquals(PreviewConfigurationAttemptKind.SAFE_BASELINE, fixture.platform.configurations.last().attempt)
        assertEquals(1, fixture.platform.configurations.first().session.closeCount)
        assertTrue(fixture.controller.state.value is CameraEngineState.ConfiguringPreview)
    }

    @Test
    fun resourceCountsNeverGoNegativeAndOneCallbackWorkerExistsUntilShutdown() {
        val fixture = Fixture()
        assertEquals(1, fixture.controller.resources.value.cameraWorkers)
        fixture.startAndPreview("a")
        val active = fixture.controller.resources.value
        assertEquals(1, active.cameraDevices)
        assertEquals(1, active.captureSessions)
        assertEquals(1, active.ownedSurfaces)
        assertEquals(1, active.cameraWorkers)

        runSuspend { fixture.controller.shutdown() }

        val final = fixture.controller.resources.value
        assertEquals(CameraResourceSnapshot(), final)
        assertTrue(final.cameraDevices >= 0)
        assertTrue(final.captureSessions >= 0)
        assertTrue(final.ownedSurfaces >= 0)
        assertEquals(0, final.cameraWorkers)
    }

    @Test
    fun surfaceInvalidationClosesCurrentResourcesAndIgnoresStaleIdentity() {
        val fixture = Fixture()
        val surface = fixture.startAndPreview("a")
        val open = fixture.platform.opens.single()
        val session = fixture.platform.configurations.single().session

        runSuspend { fixture.controller.surfaceInvalidated(PreviewSurfaceIdentity(999L)) }
        assertEquals(0, surface.closeCount)
        assertEquals(0, open.device.closeCount)
        assertEquals(0, session.closeCount)

        runSuspend { fixture.controller.surfaceInvalidated(surface.identity) }
        assertEquals(1, surface.closeCount)
        assertEquals(1, open.device.closeCount)
        assertEquals(1, session.closeCount)
        assertTrue(fixture.controller.state.value is CameraEngineState.WaitingForSurface)
    }

    private class Fixture(
        val settings: SettingsSnapshot = SettingsSnapshot(),
    ) {
        val platform = FakePlatform()
        var workerShutdownCount = 0
        val controller = CameraSessionController(
            platform = platform,
            dispatcher = Dispatchers.Unconfined,
            elapsedRealtimeNs = { 123L },
            shutdownWorker = { workerShutdownCount += 1 },
            workerCount = 1,
        )
        private var surfaceSequence = 0L

        fun start(name: String): FakeSurface {
            val surface = FakeSurface(PreviewSurfaceIdentity(++surfaceSequence))
            runSuspend {
                controller.startPreviewForTest(
                    selection = selection(name),
                    route = route(name),
                    surfaceIdentity = surface.identity,
                    surfaceToken = surface.token,
                    closeSurface = surface::close,
                    configuration = previewConfiguration(settings.fpsRequest),
                    settings = settings,
                )
            }
            return surface
        }

        fun startAndPreview(name: String): FakeSurface {
            val surface = start(name)
            platform.opens.last().opened()
            platform.configurations.last().configured()
            return surface
        }
    }

    private class FakePlatform : CameraOwnerPlatform {
        val opens = mutableListOf<OpenCall>()
        val configurations = mutableListOf<ConfigurationCall>()
        val repeats = mutableListOf<RepeatCall>()
        var failNextRepeating = false

        override fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks) {
            opens += OpenCall(cameraId, callbacks)
        }

        override fun configurePreview(
            device: CameraDeviceHandle,
            surfaceToken: Any,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: CameraSessionCallbacks,
        ) {
            configurations += ConfigurationCall(device, surfaceToken, attempt, callbacks)
        }

        override fun startRepeating(
            session: CameraCaptureSessionHandle,
            request: PreparedPreviewRequest,
            onFrame: () -> Unit,
        ) {
            if (failNextRepeating) {
                failNextRepeating = false
                throw IllegalStateException("repeating rejected")
            }
            repeats += RepeatCall(session, onFrame)
        }
    }

    private class OpenCall(
        val cameraId: CameraTransportId,
        private val callbacks: CameraOpenCallbacks,
    ) {
        val device = FakeDevice(cameraId.value)
        private val delivery = CloseOnceCameraResource<CameraDeviceHandle>(
            device,
            CameraDeviceHandle::close,
        )

        fun opened() = callbacks.onOpened(delivery)
        fun disconnected() = callbacks.onDisconnected(delivery)
        fun error(code: Int) = callbacks.onError(delivery, code)
    }

    private class ConfigurationCall(
        val device: CameraDeviceHandle,
        val surfaceToken: Any,
        val attempt: PreviewConfigurationAttemptKind,
        private val callbacks: CameraSessionCallbacks,
    ) {
        val session = FakeSession()
        private val delivery = CloseOnceCameraResource<CameraCaptureSessionHandle>(
            session,
            CameraCaptureSessionHandle::close,
        )
        private val request = FakeRequest

        fun configured() = callbacks.onConfigured(delivery, request)
        fun failed() = callbacks.onConfigureFailed(delivery)
    }

    private class RepeatCall(
        val session: CameraCaptureSessionHandle,
        private val onFrame: () -> Unit,
    ) {
        fun frame() = onFrame()
    }

    private class FakeDevice(val name: String) : CameraDeviceHandle {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeSession : CameraCaptureSessionHandle {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeSurface(val identity: PreviewSurfaceIdentity) {
        val token = Any()
        var closeCount = 0
            private set

        fun close() {
            closeCount += 1
        }
    }

    private data object FakeRequest : PreparedPreviewRequest

    private companion object {
        fun selection(name: String) = ActiveCameraSelection(
            canonicalLensFingerprint = CanonicalLensFingerprint("lens:$name"),
            profileFingerprint = CameraProfileFingerprint("profile:$name"),
            routeId = CameraRouteId("route:$name"),
            selectionGeneration = SelectionGeneration(0L),
            sessionGeneration = SessionGeneration(0L),
        )

        fun route(name: String) = CameraRoute(
            id = CameraRouteId("route:$name"),
            source = CameraRouteSource.JAVA_PUBLIC,
            openCameraId = CameraTransportId("opaque:$name"),
            capabilities = CameraCapabilities(),
            metadataTrust = CameraTrust.ADVERTISED,
        )

        fun requestedFpsSettings() = SettingsSnapshot(
            fpsRequest = PreviewFpsRequest(
                overrideEnabled = true,
                requestedMinimum = 30,
                requestedMaximum = 30,
            ),
        )

        fun previewConfiguration(request: PreviewFpsRequest) = PreviewConfiguration(
            streamType = PreviewStreamType.CAMERA2_PRIVATE,
            size = IntSize(1920, 1080),
            fps = PreviewFpsResolution(
                request = request,
                resolvedRange = CameraFpsCapability(30, 30),
                reason = PreviewFpsFallbackReason.EXACT_MATCH,
            ),
            highResolutionViewfinder = false,
            signature = "camx-103-test",
        )

        fun <T> runSuspend(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(
                object : Continuation<T> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<T>) {
                        outcome = result
                    }
                },
            )
            return checkNotNull(outcome) {
                "CAMX-103 deterministic test unexpectedly suspended"
            }.getOrThrow()
        }
    }
}
