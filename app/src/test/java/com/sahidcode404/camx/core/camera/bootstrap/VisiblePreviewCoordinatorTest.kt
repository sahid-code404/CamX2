package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.CameraInUse
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.preview.PreviewPolicyResult
import com.sahidcode404.camx.core.camera.preview.PreviewStreamSelectionReason
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePreviewCoordinatorTest {
    @Test
    fun permissionFalseMeansNoDiscoveryOrPreviewStart() {
        val fixture = fixture()

        fixture.coordinator.resume(DisplayRotation.ROTATION_0)

        assertEquals(0, fixture.seed.calls)
        assertEquals(0, fixture.session.startCalls)
        assertEquals(VisiblePreviewUiState.WaitingForPermission, fixture.coordinator.uiState.value)
    }

    @Test
    fun permissionGrantStartsSeedDiscovery() {
        val fixture = fixture(surfaceAvailable = false)

        fixture.coordinator.setPermission(true)
        fixture.coordinator.resume(DisplayRotation.ROTATION_0)

        assertEquals(1, fixture.seed.calls)
        assertEquals(VisiblePreviewUiState.WaitingForSurface, fixture.coordinator.uiState.value)
    }

    @Test
    fun noSeedProducesTypedRecoverableUnavailableState() {
        val fixture = fixture(seedRoute = null)
        start(fixture)

        val state = fixture.coordinator.uiState.value as VisiblePreviewUiState.Unavailable
        assertEquals(VisiblePreviewProblem.NoCredibleSeed, state.problem)
        assertEquals(0, fixture.capabilities.calls)
        assertEquals(0, fixture.session.startCalls)
    }

    @Test
    fun seedAvailableReadsOnlySelectedRouteCapabilities() {
        val fixture = fixture(surfaceAvailable = false)
        start(fixture)

        assertEquals(1, fixture.capabilities.calls)
        assertEquals(fixture.route, fixture.capabilities.lastRoute)
        assertEquals(VisiblePreviewUiState.WaitingForSurface, fixture.coordinator.uiState.value)
    }

    @Test
    fun capabilityReadFailureProjectsTypedUnavailableState() {
        val fixture = fixture(
            capabilityResult = SelectedSeedCapabilityResult.Unavailable(
                SelectedSeedCapabilityFailure.STREAM_MAP_UNAVAILABLE,
            ),
        )
        start(fixture)

        val state = fixture.coordinator.uiState.value as VisiblePreviewUiState.Unavailable
        assertEquals(
            VisiblePreviewProblem.Capability(SelectedSeedCapabilityFailure.STREAM_MAP_UNAVAILABLE),
            state.problem,
        )
        assertEquals(0, fixture.session.startCalls)
    }

    @Test
    fun unsupportedPreviewPolicyDoesNotStartController() {
        val fixture = fixture(
            policyResult = PreviewPolicyResult.Unsupported(
                com.sahidcode404.camx.core.camera.preview.PreviewUnsupportedReason.NO_ADVERTISED_STREAMS,
            ),
        )
        start(fixture)

        assertTrue((fixture.coordinator.uiState.value as VisiblePreviewUiState.Unavailable).problem is VisiblePreviewProblem.Policy)
        assertEquals(0, fixture.session.startCalls)
    }

    @Test
    fun surfaceAvailableBeforeDiscoveryStartsWhenDiscoveryCompletes() {
        val seedDeferred = CompletableDeferred<CameraRoute?>()
        val fixture = fixture(seedDeferred = seedDeferred, surfaceAvailable = true)
        start(fixture)
        assertEquals(0, fixture.session.startCalls)

        seedDeferred.complete(fixture.route)

        assertEquals(1, fixture.session.startCalls)
        assertEquals(fixture.configuration.size, fixture.surface.lastAwaitedBufferSize)
    }

    @Test
    fun discoveryBeforeSurfaceWaitsAndThenStartsExactlyOnce() {
        val fixture = fixture(surfaceAvailable = false)
        start(fixture)
        assertEquals(VisiblePreviewUiState.WaitingForSurface, fixture.coordinator.uiState.value)
        assertEquals(0, fixture.session.startCalls)

        fixture.surface.publish()

        assertEquals(1, fixture.session.startCalls)
    }

    @Test
    fun repeatedResumeAndLowFrequencyStateProjectionDoNotDuplicateCurrentGenerationStart() {
        val fixture = fixture()
        start(fixture)
        assertEquals(1, fixture.session.startCalls)

        fixture.coordinator.resume(DisplayRotation.ROTATION_0)
        fixture.session.projectPreview(firstFrameVerified = false)
        fixture.session.projectPreview(firstFrameVerified = true)

        assertEquals(1, fixture.session.startCalls)
        val state = fixture.coordinator.uiState.value as VisiblePreviewUiState.Previewing
        assertTrue(state.firstFrameVerified)
    }

    @Test
    fun pauseDuringSeedDiscoveryCancelsStartupBeforePreviewStart() {
        val seedDeferred = CompletableDeferred<CameraRoute?>()
        val fixture = fixture(seedDeferred = seedDeferred)
        start(fixture)

        fixture.coordinator.pause()
        seedDeferred.complete(fixture.route)

        assertEquals(0, fixture.session.startCalls)
        assertEquals(1, fixture.session.pauseCalls)
    }

    @Test
    fun resumeAfterPauseStartsANewGenerationWithStableBootstrapIdentity() {
        val fixture = fixture()
        start(fixture)
        val firstSelection = fixture.session.lastSelection

        fixture.coordinator.pause()
        fixture.coordinator.resume(DisplayRotation.ROTATION_0)

        assertEquals(2, fixture.session.startCalls)
        val secondSelection = fixture.session.lastSelection
        assertEquals(firstSelection?.canonicalLensFingerprint, secondSelection?.canonicalLensFingerprint)
        assertEquals(firstSelection?.profileFingerprint, secondSelection?.profileFingerprint)
        assertEquals(firstSelection?.routeId, secondSelection?.routeId)
    }

    @Test
    fun changedCapabilityEvidenceChangesBootstrapProfileButNotCanonicalSeedIdentity() {
        val first = fixture()
        start(first)
        val firstSelection = checkNotNull(first.session.lastSelection)
        val secondCaps = availableCapabilities().copy(
            capabilities = availableCapabilities().capabilities.copy(
                fpsRanges = listOf(CameraFpsCapability(15, 30)),
            ),
        )
        val second = fixture(
            capabilityResult = SelectedSeedCapabilityResult.Available(secondCaps),
        )
        start(second)
        val secondSelection = checkNotNull(second.session.lastSelection)

        assertEquals(firstSelection.canonicalLensFingerprint, secondSelection.canonicalLensFingerprint)
        assertNotEquals(firstSelection.profileFingerprint, secondSelection.profileFingerprint)
    }

    @Test
    fun currentSurfaceInvalidationIsForwardedAndRestartWaitsForReplacement() {
        val fixture = fixture(surfaceAvailable = true)
        start(fixture)
        val identity = fixture.surface.identity
        fixture.surface.available = false

        fixture.coordinator.surfaceInvalidated(identity)

        assertEquals(listOf(identity), fixture.session.invalidated)
        assertEquals(1, fixture.session.startCalls)
        fixture.surface.publish(newIdentity = PreviewSurfaceIdentity(identity.value + 1L))
        assertEquals(2, fixture.session.startCalls)
    }

    @Test
    fun controllerRecoverableFailureWithoutCanonicalTargetDoesNotFakeFirstFrame() {
        val fixture = fixture()
        start(fixture)

        fixture.session.projectPreview(firstFrameVerified = false)
        fixture.session.stateFlow.value = CameraEngineState.RecoverableError(
            selection = fixture.session.lastSelection,
            failure = CameraInUse,
        )

        assertEquals(VisiblePreviewUiState.WaitingForSurface, fixture.coordinator.uiState.value)
        assertFalse((fixture.coordinator.uiState.value as? VisiblePreviewUiState.Previewing)?.firstFrameVerified == true)
    }

    @Test
    fun shutdownIsExactlyOnceAndPreventsFurtherStartup() {
        val fixture = fixture()
        start(fixture)

        awaitUnit { fixture.coordinator.shutdownForTest() }
        awaitUnit { fixture.coordinator.shutdownForTest() }
        fixture.coordinator.resume(DisplayRotation.ROTATION_90)

        assertEquals(1, fixture.session.shutdownCalls)
        assertEquals(1, fixture.session.startCalls)
    }

    private fun start(fixture: Fixture) {
        fixture.coordinator.setPermission(true)
        fixture.coordinator.resume(DisplayRotation.ROTATION_0)
    }

    private fun fixture(
        seedRoute: CameraRoute? = route(),
        seedDeferred: CompletableDeferred<CameraRoute?>? = null,
        surfaceAvailable: Boolean = true,
        capabilityResult: SelectedSeedCapabilityResult = SelectedSeedCapabilityResult.Available(
            availableCapabilities(),
        ),
        policyResult: PreviewPolicyResult = supportedPolicy(),
    ): Fixture {
        val seed = FakeSeedSource(seedRoute, seedDeferred)
        val capabilities = FakeCapabilitySource(capabilityResult)
        val surface = FakeSurfacePort(surfaceAvailable)
        val session = FakeSessionPort()
        val coordinator = VisiblePreviewCoordinator(
            seedSource = seed,
            capabilitySource = capabilities,
            surfacePort = surface,
            session = session,
            policy = VisiblePreviewPolicyPort { policyResult },
            settings = { SettingsSnapshot() },
            dispatcher = Dispatchers.Unconfined,
        )
        return Fixture(
            coordinator = coordinator,
            seed = seed,
            capabilities = capabilities,
            surface = surface,
            session = session,
            route = route(),
            configuration = (policyResult as? PreviewPolicyResult.Supported)?.configuration ?: configuration(),
        )
    }

    private data class Fixture(
        val coordinator: VisiblePreviewCoordinator,
        val seed: FakeSeedSource,
        val capabilities: FakeCapabilitySource,
        val surface: FakeSurfacePort,
        val session: FakeSessionPort,
        val route: CameraRoute,
        val configuration: PreviewConfiguration,
    )

    private class FakeSeedSource(
        private val route: CameraRoute?,
        private val deferred: CompletableDeferred<CameraRoute?>?,
    ) : VisiblePreviewSeedSource {
        var calls = 0
        override suspend fun discoverSeed(): CameraRoute? {
            calls += 1
            return deferred?.await() ?: route
        }
    }

    private class FakeCapabilitySource(
        private val result: SelectedSeedCapabilityResult,
    ) : SelectedSeedPreviewCapabilitySource {
        var calls = 0
        var lastRoute: CameraRoute? = null
        override fun read(route: CameraRoute): SelectedSeedCapabilityResult {
            calls += 1
            lastRoute = route
            return result
        }
    }

    private class FakeSurfacePort(initiallyAvailable: Boolean) : VisiblePreviewSurfacePort {
        var available = initiallyAvailable
        var identity = PreviewSurfaceIdentity(100L)
        private var pending = CompletableDeferred<VisiblePreviewLease>()
        var lastAwaitedBufferSize: IntSize? = null

        override suspend fun awaitSurface(): VisiblePreviewLease {
            if (available) return FakeLease(identity)
            return pending.await()
        }

        override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) {
            lastAwaitedBufferSize = size
        }

        fun publish(newIdentity: PreviewSurfaceIdentity = identity) {
            identity = newIdentity
            available = true
            pending.complete(FakeLease(identity))
            pending = CompletableDeferred()
        }
    }

    private class FakeLease(
        override val identity: PreviewSurfaceIdentity,
    ) : VisiblePreviewLease {
        override val viewSize = IntSize(1080, 1920)
        override val bufferSize = IntSize(640, 480)
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeSessionPort : VisiblePreviewSessionPort {
        val stateFlow = MutableStateFlow<CameraEngineState>(CameraEngineState.WaitingForSurface(null))
        override val state: StateFlow<CameraEngineState> = stateFlow
        var startCalls = 0
        var pauseCalls = 0
        var shutdownCalls = 0
        val invalidated = mutableListOf<PreviewSurfaceIdentity>()
        var lastSelection: ActiveCameraSelection? = null
        var activeLease: VisiblePreviewLease? = null

        override suspend fun startPreview(
            selection: ActiveCameraSelection,
            route: CameraRoute,
            lease: VisiblePreviewLease,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
        ) {
            startCalls += 1
            lastSelection = selection
            activeLease = lease
            stateFlow.value = CameraEngineState.Opening(selection, selection.sessionGeneration)
        }

        override suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
            invalidated += identity
            activeLease?.close()
            activeLease = null
        }

        override suspend fun pause() {
            pauseCalls += 1
            activeLease?.close()
            activeLease = null
        }

        override suspend fun shutdown() {
            shutdownCalls += 1
            activeLease?.close()
            activeLease = null
            stateFlow.value = CameraEngineState.Closed
        }

        fun projectPreview(firstFrameVerified: Boolean) {
            val selection = checkNotNull(lastSelection)
            stateFlow.value = CameraEngineState.Previewing(selection, firstFrameVerified)
        }
    }

    companion object {
        private fun route() = CameraRoute(
            id = CameraRouteId("route:bootstrap-test"),
            source = CameraRouteSource.JAVA_PUBLIC,
            openCameraId = CameraTransportId("opaque-test-route"),
            capabilities = CameraCapabilities(),
            metadataTrust = CameraTrust.ADVERTISED,
            previewTrust = PreviewTrust.ADVERTISED,
        )

        private fun availableCapabilities() = SelectedSeedPreviewCapabilities(
            capabilities = CameraCapabilities(
                previewStreams = listOf(
                    CameraStreamCapability(
                        PreviewStreamType.CAMERA2_PRIVATE,
                        IntSize(640, 480),
                        33_333_333L,
                    ),
                ),
                fpsRanges = listOf(CameraFpsCapability(30, 30)),
            ),
            sensorOrientationDegrees = 90,
            lensFacing = LensFacing.BACK,
        )

        private fun configuration() = PreviewConfiguration(
            streamType = PreviewStreamType.CAMERA2_PRIVATE,
            size = IntSize(640, 480),
            fps = PreviewFpsResolution(
                PreviewFpsRequest(false, 30, 30),
                null,
                PreviewFpsFallbackReason.OVERRIDE_DISABLED,
            ),
            highResolutionViewfinder = false,
            signature = "visible-preview-test",
        )

        private fun supportedPolicy() = PreviewPolicyResult.Supported(
            configuration = configuration(),
            geometry = PreviewGeometry(
                clockwiseRotationDegrees = 90,
                scale = 2f,
                translatedX = 0f,
                translatedY = -100f,
                mirrorHorizontally = false,
            ),
            selectionReason = PreviewStreamSelectionReason.RESPONSIVE,
        )

        private fun awaitUnit(block: suspend () -> Unit) {
            var result: Result<Unit>? = null
            block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
                override val context = kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result).getOrThrow()
        }
    }
}
