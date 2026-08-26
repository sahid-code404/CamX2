package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraResourceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.session.CameraCaptureSessionHandle
import com.sahidcode404.camx.core.camera.session.CameraDeviceHandle
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.camera.session.CameraOpenCallbacks
import com.sahidcode404.camx.core.camera.session.CameraOwnerPlatform
import com.sahidcode404.camx.core.camera.session.CameraSessionCallbacks
import com.sahidcode404.camx.core.camera.session.CameraSessionController
import com.sahidcode404.camx.core.camera.session.CloseOnceCameraResource
import com.sahidcode404.camx.core.camera.session.PreparedPreviewRequest
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePreviewRealControllerRapidSwitchTest {
    @Test
    fun bSupersededByCWhileRealPauseCleanupBlockedOnlyCOpensAndVerifies() {
        RealFixture().use { fixture ->
            fixture.startAndVerifySeedA()
            val activeA = fixture.platform.configurations.single()
            activeA.session.blockNextClose()

            fixture.coordinator.selectLens(lens("b"))
            assertTrue(activeA.session.closeEntered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.awaitControllerState { it is CameraEngineState.Pausing } is CameraEngineState.Pausing)

            fixture.coordinator.selectLens(lens("c"))
            activeA.session.releaseClose.countDown()

            assertTrue(fixture.platform.openSignal.tryAcquire(5, TimeUnit.SECONDS))
            fixture.awaitOpenCount(2)
            assertEquals(CameraTransportId("open:c"), fixture.platform.opens[1].cameraId)
            assertEquals(2, fixture.platform.opens.size)

            fixture.driveOpenToVerified(1)
            val verified = fixture.awaitControllerState {
                it is CameraEngineState.Previewing && it.firstFrameVerified && it.selection.routeId == CameraRouteId("route:c")
            } as CameraEngineState.Previewing
            assertEquals(CameraRouteId("route:c"), verified.selection.routeId)
            fixture.awaitCoordinatorVerified(lens("c"))

            assertEquals(1, activeA.session.closeCount.get())
            assertEquals(1, fixture.platform.opens[0].device.closeCount.get())
            assertEquals(1, fixture.surface.leases.first().closeCount.get())
            assertEquals(
                CameraResourceSnapshot(cameraDevices = 1, captureSessions = 1, ownedSurfaces = 1, cameraWorkers = 1),
                fixture.controller.resources.value,
            )
            assertTrue(fixture.coordinator.lensItems.value.none { it.status.name == "FAILED" })
        }
    }

    @Test
    fun cancellationAfterPausingCannotStrandRealControllerAndFutureSelectionStillOpens() {
        RealFixture(useCoordinator = false).use { fixture ->
            fixture.startDirectAndVerify("a")
            val activeA = fixture.platform.configurations.single()
            activeA.session.blockNextClose()

            val callerExecutor = Executors.newSingleThreadExecutor()
            val callerDispatcher = callerExecutor.asCoroutineDispatcher()
            try {
                val pauseJob = fixture.testScope(callerDispatcher).launch { fixture.controller.pause() }
                assertTrue(activeA.session.closeEntered.await(5, TimeUnit.SECONDS))
                assertTrue(fixture.awaitControllerState { it is CameraEngineState.Pausing } is CameraEngineState.Pausing)

                pauseJob.cancel()
                activeA.session.releaseClose.countDown()
                runBlocking { withTimeout(5_000L) { pauseJob.join() } }

                assertTrue(fixture.awaitControllerState { it is CameraEngineState.WaitingForSurface } is CameraEngineState.WaitingForSurface)
                assertEquals(CameraResourceSnapshot(cameraWorkers = 1), fixture.controller.resources.value)
                assertEquals(1, activeA.session.closeCount.get())
                assertEquals(1, fixture.platform.opens[0].device.closeCount.get())
                assertEquals(1, fixture.directSurfaces.first().closeCount.get())

                fixture.startDirect("c")
                assertTrue(fixture.platform.openSignal.tryAcquire(5, TimeUnit.SECONDS))
                fixture.awaitOpenCount(2)
                assertEquals(CameraTransportId("open:c"), fixture.platform.opens[1].cameraId)
                fixture.driveOpenToVerified(1)
                assertTrue(
                    fixture.awaitControllerState {
                        it is CameraEngineState.Previewing && it.firstFrameVerified && it.selection.routeId == CameraRouteId("route:c")
                    } is CameraEngineState.Previewing,
                )
            } finally {
                callerDispatcher.close()
                callerExecutor.shutdownNow()
            }
        }
    }

    @Test
    fun lateOpenedResourceFromBIsClosedAndCannotConfigureAfterCBecomesLatest() {
        RealFixture().use { fixture ->
            fixture.startAndVerifySeedA()

            fixture.coordinator.selectLens(lens("b"))
            assertTrue(fixture.platform.openSignal.tryAcquire(5, TimeUnit.SECONDS))
            fixture.awaitOpenCount(2)
            val openB = fixture.platform.opens[1]
            assertEquals(CameraTransportId("open:b"), openB.cameraId)

            fixture.coordinator.selectLens(lens("c"))
            assertTrue(fixture.platform.openSignal.tryAcquire(5, TimeUnit.SECONDS))
            fixture.awaitOpenCount(3)
            val openC = fixture.platform.opens[2]
            assertEquals(CameraTransportId("open:c"), openC.cameraId)

            openB.opened()
            fixture.awaitDeviceClosed(openB.device)
            assertEquals(1, openB.device.closeCount.get())
            assertTrue(fixture.platform.configurations.none { it.device === openB.device })

            openC.opened()
            assertTrue(fixture.platform.configurationSignal.tryAcquire(5, TimeUnit.SECONDS))
            fixture.awaitConfigurationCount(2)
            fixture.platform.configurations[1].configured()
            fixture.awaitControllerState {
                it is CameraEngineState.Previewing && !it.firstFrameVerified && it.selection.routeId == CameraRouteId("route:c")
            }
            assertTrue(fixture.platform.repeatSignal.tryAcquire(5, TimeUnit.SECONDS))
            fixture.platform.repeats.last().frame()
            fixture.awaitCoordinatorVerified(lens("c"))
            assertTrue(fixture.coordinator.lensItems.value.none { it.status.name == "FAILED" })
        }
    }

    private class RealFixture(
        private val useCoordinator: Boolean = true,
    ) : AutoCloseable {
        private val controllerExecutor = Executors.newSingleThreadExecutor()
        private val coordinatorExecutor = Executors.newSingleThreadExecutor()
        private val controllerDispatcher = controllerExecutor.asCoroutineDispatcher()
        private val coordinatorDispatcher = coordinatorExecutor.asCoroutineDispatcher()
        val platform = FakePlatform()
        val controller = CameraSessionController(
            platform = platform,
            dispatcher = controllerDispatcher,
            workerCount = 1,
        )
        val surface = FakeSurfacePort()
        val directSurfaces = Collections.synchronizedList(mutableListOf<FakeDirectSurface>())
        private val topology = topology()
        private val sessionPort = ControllerSessionPort(controller)
        val coordinator = VisiblePreviewCoordinator(
            seedSource = VisiblePreviewSeedSource { route("a") },
            capabilitySource = SelectedSeedPreviewCapabilitySource { route ->
                SelectedSeedCapabilityResult.Available(
                    SelectedSeedPreviewCapabilities(
                        capabilities = route.capabilities,
                        sensorOrientationDegrees = 90,
                        lensFacing = LensFacing.BACK,
                    ),
                )
            },
            surfacePort = surface,
            session = sessionPort,
            topology = kotlinx.coroutines.flow.MutableStateFlow(topology),
            runtimeApiLevel = 35,
            settings = { SettingsSnapshot() },
            dispatcher = coordinatorDispatcher,
        )

        fun startAndVerifySeedA() {
            check(useCoordinator)
            coordinator.setPermission(true)
            coordinator.resume(DisplayRotation.ROTATION_0)
            assertTrue(platform.openSignal.tryAcquire(5, TimeUnit.SECONDS))
            awaitOpenCount(1)
            assertEquals(CameraTransportId("open:a"), platform.opens[0].cameraId)
            driveOpenToVerified(0)
            awaitCoordinatorVerified(lens("a"))
        }

        fun startDirectAndVerify(name: String) {
            startDirect(name)
            assertTrue(platform.openSignal.tryAcquire(5, TimeUnit.SECONDS))
            driveOpenToVerified(platform.opens.lastIndex)
        }

        fun startDirect(name: String) {
            val surface = FakeDirectSurface(PreviewSurfaceIdentity(1_000L + directSurfaces.size))
            directSurfaces += surface
            runBlocking {
                controller.startPreviewForTest(
                    selection = selection(name),
                    route = route(name),
                    surfaceIdentity = surface.identity,
                    surfaceToken = surface,
                    closeSurface = surface::close,
                    configuration = previewConfiguration(),
                    settings = SettingsSnapshot(),
                )
            }
        }

        fun driveOpenToVerified(openIndex: Int) {
            val open = platform.opens[openIndex]
            open.opened()
            assertTrue(platform.configurationSignal.tryAcquire(5, TimeUnit.SECONDS))
            val configuration = platform.configurations.last()
            configuration.configured()
            awaitControllerState {
                it is CameraEngineState.Previewing && !it.firstFrameVerified && it.selection.routeId == routeIdFor(open.cameraId)
            }
            assertTrue(platform.repeatSignal.tryAcquire(5, TimeUnit.SECONDS))
            platform.repeats.last().frame()
            awaitControllerState {
                it is CameraEngineState.Previewing && it.firstFrameVerified && it.selection.routeId == routeIdFor(open.cameraId)
            }
        }

        fun awaitCoordinatorVerified(lens: CanonicalLensFingerprint) = runBlocking {
            withTimeout(5_000L) {
                coordinator.uiState.first { state ->
                    state is VisiblePreviewUiState.Previewing && state.firstFrameVerified &&
                        coordinator.lensItems.value.any { it.canonicalFingerprint == lens && it.status.name == "VERIFIED" }
                }
            }
        }

        fun awaitControllerState(predicate: (CameraEngineState) -> Boolean): CameraEngineState = runBlocking {
            withTimeout(5_000L) { controller.state.first(predicate) }
        }

        fun awaitOpenCount(count: Int) = awaitCount { platform.opens.size >= count }
        fun awaitConfigurationCount(count: Int) = awaitCount { platform.configurations.size >= count }
        fun awaitDeviceClosed(device: FakeDevice) = awaitCount { device.closeCount.get() >= 1 }

        private fun awaitCount(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!predicate()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for real-controller test condition" }
                Thread.yield()
            }
        }

        fun testScope(dispatcher: CoroutineDispatcher) = kotlinx.coroutines.CoroutineScope(dispatcher)

        override fun close() {
            if (controller.state.value != CameraEngineState.Closed) {
                runBlocking { controller.shutdown() }
            }
            coordinatorDispatcher.close()
            controllerDispatcher.close()
            coordinatorExecutor.shutdownNow()
            controllerExecutor.shutdownNow()
        }
    }

    private class ControllerSessionPort(
        private val controller: CameraSessionController,
    ) : VisiblePreviewSessionPort {
        override val state: StateFlow<CameraEngineState> = controller.state

        override suspend fun startPreview(
            selection: ActiveCameraSelection,
            route: CameraRoute,
            lease: VisiblePreviewLease,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
        ) {
            controller.startPreviewForTest(
                selection = selection,
                route = route,
                surfaceIdentity = lease.identity,
                surfaceToken = lease,
                closeSurface = lease::close,
                configuration = configuration,
                settings = settings,
            )
        }

        override suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
            controller.surfaceInvalidated(identity)
        }

        override suspend fun pause() {
            controller.pause()
        }

        override suspend fun shutdown() {
            controller.shutdown()
        }
    }

    private class FakeSurfacePort : VisiblePreviewSurfacePort {
        val leases = Collections.synchronizedList(mutableListOf<FakeLease>())
        private val identity = PreviewSurfaceIdentity(500L)

        override suspend fun awaitSurface(): VisiblePreviewLease = FakeLease(identity).also(leases::add)

        override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) = Unit
    }

    private class FakeLease(
        override val identity: PreviewSurfaceIdentity,
    ) : VisiblePreviewLease {
        override val viewSize = IntSize(1080, 1920)
        override val bufferSize = IntSize(1280, 720)
        val closeCount = AtomicInteger()
        override fun close() { closeCount.incrementAndGet() }
    }

    private class FakeDirectSurface(val identity: PreviewSurfaceIdentity) {
        val closeCount = AtomicInteger()
        fun close() { closeCount.incrementAndGet() }
    }

    private class FakePlatform : CameraOwnerPlatform {
        val opens = Collections.synchronizedList(mutableListOf<OpenCall>())
        val configurations = Collections.synchronizedList(mutableListOf<ConfigurationCall>())
        val repeats = Collections.synchronizedList(mutableListOf<RepeatCall>())
        val openSignal = Semaphore(0)
        val configurationSignal = Semaphore(0)
        val repeatSignal = Semaphore(0)

        override fun open(cameraId: CameraTransportId, callbacks: CameraOpenCallbacks) {
            opens += OpenCall(cameraId, callbacks)
            openSignal.release()
        }

        override fun configurePreview(
            device: CameraDeviceHandle,
            surfaceToken: Any,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: CameraSessionCallbacks,
        ) {
            configurations += ConfigurationCall(device, attempt, callbacks)
            configurationSignal.release()
        }

        override fun startRepeating(
            session: CameraCaptureSessionHandle,
            request: PreparedPreviewRequest,
            onFrame: () -> Unit,
        ) {
            repeats += RepeatCall(session, onFrame)
            repeatSignal.release()
        }
    }

    private class OpenCall(
        val cameraId: CameraTransportId,
        private val callbacks: CameraOpenCallbacks,
    ) {
        val device = FakeDevice()
        private val delivery = CloseOnceCameraResource<CameraDeviceHandle>(device, CameraDeviceHandle::close)
        fun opened() = callbacks.onOpened(delivery)
    }

    private class ConfigurationCall(
        val device: CameraDeviceHandle,
        val attempt: PreviewConfigurationAttemptKind,
        private val callbacks: CameraSessionCallbacks,
    ) {
        val session = BlockingSession()
        private val delivery = CloseOnceCameraResource<CameraCaptureSessionHandle>(session, CameraCaptureSessionHandle::close)
        fun configured() = callbacks.onConfigured(delivery, FakeRequest)
    }

    private class RepeatCall(
        val session: CameraCaptureSessionHandle,
        private val onFrame: () -> Unit,
    ) {
        fun frame() = onFrame()
    }

    private class FakeDevice : CameraDeviceHandle {
        val closeCount = AtomicInteger()
        override fun close() { closeCount.incrementAndGet() }
    }

    private class BlockingSession : CameraCaptureSessionHandle {
        val closeCount = AtomicInteger()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        @Volatile private var blockClose = false

        fun blockNextClose() {
            blockClose = true
        }

        override fun close() {
            closeCount.incrementAndGet()
            if (blockClose) {
                blockClose = false
                closeEntered.countDown()
                check(releaseClose.await(5, TimeUnit.SECONDS)) { "Timed out releasing blocked session close" }
            }
        }
    }

    private data object FakeRequest : PreparedPreviewRequest

    private companion object {
        fun lens(name: String) = CanonicalLensFingerprint("lens:$name")

        fun selection(name: String) = ActiveCameraSelection(
            canonicalLensFingerprint = lens(name),
            profileFingerprint = CameraProfileFingerprint("profile:$name"),
            routeId = CameraRouteId("route:$name"),
            selectionGeneration = SelectionGeneration(0L),
            sessionGeneration = SessionGeneration(0L),
        )

        fun route(name: String) = CameraRoute(
            id = CameraRouteId("route:$name"),
            source = CameraRouteSource.JAVA_PUBLIC,
            openCameraId = CameraTransportId("open:$name"),
            capabilities = CameraCapabilities(
                previewStreams = listOf(
                    CameraStreamCapability(
                        type = PreviewStreamType.CAMERA2_PRIVATE,
                        size = IntSize(1280, 720),
                        minimumFrameDurationNs = 33_333_333L,
                    ),
                ),
                fpsRanges = listOf(CameraFpsCapability(30, 30)),
            ),
            metadataTrust = CameraTrust.ADVERTISED,
        )

        fun topology(): CameraTopologySnapshot {
            val names = listOf("a", "b", "c")
            val profiles = names.map { name ->
                CameraProfile(
                    fingerprint = CameraProfileFingerprint("profile:$name"),
                    canonicalFingerprint = lens(name),
                    route = route(name),
                )
            }
            val lenses = names.map { name ->
                CanonicalLens(
                    fingerprint = lens(name),
                    facing = LensFacing.BACK,
                    profiles = listOf(profiles.single { it.fingerprint == CameraProfileFingerprint("profile:$name") }),
                )
            }
            val evidence = names.mapIndexed { index, name ->
                CameraMetadataEvidence(
                    source = CameraRouteSource.JAVA_PUBLIC,
                    transportId = CameraTransportId("open:$name"),
                    facing = LensFacing.BACK,
                    focalLengthsMillimetres = listOf(2f + index * 2f),
                    sensorPhysicalWidthMillimetres = 6f,
                    sensorPhysicalHeightMillimetres = 4.5f,
                    activeArray = IntSize(4000, 3000),
                    pixelArray = IntSize(4000, 3000),
                    sensorOrientationDegrees = 90,
                    capabilities = route(name).capabilities,
                )
            }
            return CameraTopologySnapshot(
                schema = CameraSchemaVersions.TOPOLOGY,
                environment = CameraEnvironmentFingerprint("real-controller-rapid-switch"),
                routes = profiles.map { it.route },
                canonicalLenses = lenses,
                generatedAtElapsedRealtimeNs = 1L,
                evidence = evidence,
            )
        }

        fun previewConfiguration() = PreviewConfiguration(
            streamType = PreviewStreamType.CAMERA2_PRIVATE,
            size = IntSize(1280, 720),
            fps = PreviewFpsResolution(
                request = PreviewFpsRequest(false, 30, 30),
                resolvedRange = CameraFpsCapability(30, 30),
                reason = PreviewFpsFallbackReason.OVERRIDE_DISABLED,
            ),
            highResolutionViewfinder = false,
            signature = "rapid-switch-real-controller",
        )

        fun routeIdFor(cameraId: CameraTransportId) = CameraRouteId("route:${cameraId.value.removePrefix("open:")}")
    }
}
