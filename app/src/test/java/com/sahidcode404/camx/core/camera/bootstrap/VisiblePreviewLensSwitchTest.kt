package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.CameraInUse
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
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
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
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

class VisiblePreviewLensSwitchTest {
    @Test
    fun tapCurrentVerifiedLensIsNoOp() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        val starts = fixture.session.starts.size
        val pauses = fixture.session.pauseCalls
        fixture.coordinator.selectLens(lens("main"))
        assertEquals(starts, fixture.session.starts.size)
        assertEquals(pauses, fixture.session.pauseCalls)
    }

    @Test
    fun mainToUltrawideReleasesCurrentLeaseBeforeReacquiringSameSurface() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.events.clear()
        val identity = fixture.surface.identity
        fixture.coordinator.selectLens(lens("ultra"))
        assertEquals("pause", fixture.events[0])
        assertEquals("await:${identity.value}", fixture.events[1])
        assertEquals("start:route:ultra", fixture.events.last())
        assertEquals(identity, fixture.session.starts.last().lease.identity)
        assertEquals(CameraRouteId("route:ultra"), fixture.session.starts.last().route.id)
    }

    @Test
    fun ultrawideToMainSwitchesBackThroughSameCoordinator() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("ultra"))
        fixture.session.verifyCurrent()
        fixture.coordinator.selectLens(lens("main"))
        assertEquals(CameraRouteId("route:main"), fixture.session.starts.last().route.id)
    }

    @Test
    fun mainToTeleUsesTeleAdvertisedConfiguration() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("tele"))
        val start = fixture.session.starts.last()
        assertEquals(CameraRouteId("route:tele"), start.route.id)
        assertEquals(IntSize(1920, 1080), start.configuration.size)
        assertEquals(IntSize(1920, 1080), fixture.coordinator.renderSpec.value?.bufferSize)
    }

    @Test
    fun rearToFrontUsesFrontFacingGeometryAndMirrorPreference() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("front"))
        assertEquals(CameraRouteId("route:front"), fixture.session.starts.last().route.id)
        assertTrue(checkNotNull(fixture.coordinator.renderSpec.value).geometry.mirrorHorizontally)
    }

    @Test
    fun frontToRearRecomputesRearGeometryWithoutFrontMirror() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("front"))
        fixture.session.verifyCurrent()
        fixture.coordinator.selectLens(lens("main"))
        assertFalse(checkNotNull(fixture.coordinator.renderSpec.value).geometry.mirrorHorizontally)
    }

    @Test
    fun logicalToPhysicalAndPhysicalAToBKeepExactRouteTargets() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("physicalA"))
        val a = fixture.session.starts.last().route
        fixture.session.verifyCurrent()
        fixture.coordinator.selectLens(lens("physicalB"))
        val b = fixture.session.starts.last().route
        assertEquals(CameraTransportId("logical-parent"), a.openCameraId)
        assertEquals(PhysicalCameraId("member-a"), a.physicalCameraId)
        assertEquals(CameraTransportId("logical-parent"), b.openCameraId)
        assertEquals(PhysicalCameraId("member-b"), b.physicalCameraId)
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun configuredOrRepeatingPreviewWithoutFirstFrameDoesNotVerifyLens() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("tele"))
        val selection = fixture.session.starts.last().selection
        fixture.session.stateFlow.value = CameraEngineState.ConfiguringPreview(
            selection,
            com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind.REQUESTED,
        )
        assertEquals(LensTestStatus.OPENING, fixture.status("tele"))
        fixture.session.stateFlow.value = CameraEngineState.Previewing(selection, false)
        assertEquals(LensTestStatus.OPENING, fixture.status("tele"))
        fixture.session.stateFlow.value = CameraEngineState.Previewing(selection, true)
        assertEquals(LensTestStatus.VERIFIED, fixture.status("tele"))
        assertTrue(fixture.item("tele").selected)
    }

    @Test
    fun switchFailureMarksOnlyTargetFailedAndExplicitRetryStartsAgain() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("tele"))
        val teleSelection = fixture.session.starts.last().selection
        fixture.session.stateFlow.value = CameraEngineState.RecoverableError(teleSelection, CameraInUse)
        assertEquals(LensTestStatus.FAILED, fixture.status("tele"))
        assertNotEquals(LensTestStatus.FAILED, fixture.status("main"))
        val starts = fixture.session.starts.size
        fixture.coordinator.selectLens(lens("tele"))
        assertEquals(starts + 1, fixture.session.starts.size)
    }

    @Test
    fun rapidAToBToCLeavesCAsCurrentStartedRoute() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("ultra"))
        fixture.coordinator.selectLens(lens("tele"))
        assertEquals(CameraRouteId("route:tele"), fixture.session.starts.last().route.id)
        assertEquals(LensTestStatus.OPENING, fixture.status("tele"))
        assertNotEquals(LensTestStatus.VERIFIED, fixture.status("ultra"))
    }

    @Test
    fun pauseDuringBlockedSwitchCancelsTargetBeforeControllerStart() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.surface.blockNextAcquire()
        val starts = fixture.session.starts.size
        fixture.coordinator.selectLens(lens("tele"))
        fixture.coordinator.pause()
        fixture.surface.releaseBlockedAcquire()
        assertEquals(starts, fixture.session.starts.size)
        assertTrue(fixture.session.pauseCalls >= 2)
    }

    @Test
    fun resumeAfterPauseReturnsToPreviouslySelectedVerifiedLens() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("tele"))
        fixture.session.verifyCurrent()
        fixture.coordinator.pause()
        val starts = fixture.session.starts.size
        fixture.coordinator.resume(DisplayRotation.ROTATION_0)
        assertEquals(starts + 1, fixture.session.starts.size)
        assertEquals(CameraRouteId("route:tele"), fixture.session.starts.last().route.id)
    }

    @Test
    fun destroyedSurfaceDuringBlockedSwitchRejectsOldCompletionAndUsesNewSurface() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.surface.blockNextAcquire()
        val starts = fixture.session.starts.size

        fixture.coordinator.selectLens(lens("ultra"))
        assertTrue(fixture.surface.blockedAcquireInFlight())
        val oldIdentity = fixture.surface.identity
        fixture.surface.invalidate(oldIdentity)
        fixture.coordinator.surfaceInvalidated(oldIdentity)

        fixture.surface.releaseBlockedAcquire()
        assertEquals(starts, fixture.session.starts.size)

        val newIdentity = PreviewSurfaceIdentity(oldIdentity.value + 1L)
        fixture.surface.publish(newIdentity)
        assertEquals(starts + 1, fixture.session.starts.size)
        assertEquals(CameraRouteId("route:ultra"), fixture.session.starts.last().route.id)
        assertEquals(newIdentity, fixture.session.starts.last().lease.identity)

        fixture.session.verifyCurrent()
        assertEquals(LensTestStatus.VERIFIED, fixture.status("ultra"))
        assertTrue(fixture.item("ultra").selected)
    }

    @Test
    fun publishedReplacementThenPreviousInvalidationRestartsSelectedLensOnceOnNewSurface() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("ultra"))
        fixture.session.verifyCurrent()
        val starts = fixture.session.starts.size
        val oldIdentity = fixture.surface.identity
        val newIdentity = PreviewSurfaceIdentity(oldIdentity.value + 1L)

        fixture.surface.publishReplacement(newIdentity)
        fixture.coordinator.surfaceInvalidated(oldIdentity)

        assertEquals(starts + 1, fixture.session.starts.size)
        assertEquals(CameraRouteId("route:ultra"), fixture.session.starts.last().route.id)
        assertEquals(newIdentity, fixture.session.starts.last().lease.identity)
    }

    @Test
    fun shutdownDuringBlockedSwitchPreventsLateStart() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.surface.blockNextAcquire()
        val starts = fixture.session.starts.size
        fixture.coordinator.selectLens(lens("ultra"))
        awaitUnit { fixture.coordinator.shutdownForTest() }
        fixture.surface.releaseBlockedAcquire()
        assertEquals(starts, fixture.session.starts.size)
        assertEquals(1, fixture.session.shutdownCalls)
    }

    private class Fixture(
        val coordinator: VisiblePreviewCoordinator,
        val topology: CameraTopologySnapshot,
        val topologyFlow: MutableStateFlow<CameraTopologySnapshot?>,
        val surface: FakeSurfacePort,
        val session: FakeSessionPort,
        val events: MutableList<String>,
    ) {
        fun startAndVerifyMain() {
            coordinator.setPermission(true)
            coordinator.resume(DisplayRotation.ROTATION_0)
            session.verifyCurrent()
            assertEquals(LensTestStatus.VERIFIED, status("main"))
        }
        fun item(name: String) = coordinator.lensItems.value.single { it.canonicalFingerprint == lens(name) }
        fun status(name: String) = item(name).status
    }

    private data class StartCall(
        val selection: ActiveCameraSelection,
        val route: CameraRoute,
        val lease: VisiblePreviewLease,
        val configuration: PreviewConfiguration,
    )

    private class FakeSessionPort(private val events: MutableList<String>) : VisiblePreviewSessionPort {
        val stateFlow = MutableStateFlow<CameraEngineState>(CameraEngineState.WaitingForSurface(null))
        override val state: StateFlow<CameraEngineState> = stateFlow
        val starts = mutableListOf<StartCall>()
        var pauseCalls = 0
        var shutdownCalls = 0
        var activeLease: VisiblePreviewLease? = null

        override suspend fun startPreview(
            selection: ActiveCameraSelection,
            route: CameraRoute,
            lease: VisiblePreviewLease,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
        ) {
            events += "start:${route.id.value}"
            starts += StartCall(selection, route, lease, configuration)
            activeLease = lease
            stateFlow.value = CameraEngineState.Opening(selection, selection.sessionGeneration)
        }

        override suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
            val lease = activeLease ?: return
            if (lease.identity != identity) return
            lease.close()
            activeLease = null
            stateFlow.value = CameraEngineState.WaitingForSurface(starts.lastOrNull()?.selection)
        }

        override suspend fun pause() {
            events += "pause"
            pauseCalls += 1
            activeLease?.close()
            activeLease = null
            stateFlow.value = CameraEngineState.WaitingForSurface(starts.lastOrNull()?.selection)
        }

        override suspend fun shutdown() {
            shutdownCalls += 1
            activeLease?.close()
            activeLease = null
            stateFlow.value = CameraEngineState.Closed
        }

        fun verifyCurrent() {
            val selection = starts.last().selection
            stateFlow.value = CameraEngineState.Previewing(selection, true)
        }
    }

    private class FakeLease(override val identity: PreviewSurfaceIdentity) : VisiblePreviewLease {
        override val viewSize = IntSize(1080, 1920)
        override val bufferSize = IntSize(640, 480)
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeSurfacePort(private val events: MutableList<String>) : VisiblePreviewSurfacePort {
        private var currentIdentity: PreviewSurfaceIdentity? = PreviewSurfaceIdentity(42L)
        private var blockedNextAcquire: CompletableDeferred<VisiblePreviewLease>? = null
        private var staleBlockedAcquire: CompletableDeferred<VisiblePreviewLease>? = null
        private var staleBlockedIdentity: PreviewSurfaceIdentity? = null
        private var waitForNewSurface: CompletableDeferred<VisiblePreviewLease>? = null
        var lastBufferSize: IntSize? = null

        val identity: PreviewSurfaceIdentity
            get() = checkNotNull(currentIdentity) { "No current fake preview surface" }

        override suspend fun awaitSurface(): VisiblePreviewLease {
            val current = currentIdentity
            events += "await:${current?.value ?: "none"}"
            val blocked = blockedNextAcquire
            if (blocked != null) {
                blockedNextAcquire = null
                staleBlockedAcquire = blocked
                staleBlockedIdentity = current
                return blocked.await()
            }
            if (current != null) return FakeLease(current)
            val waiter = waitForNewSurface ?: CompletableDeferred<VisiblePreviewLease>().also {
                waitForNewSurface = it
            }
            return waiter.await()
        }

        override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) {
            lastBufferSize = size
        }

        fun blockNextAcquire() {
            check(blockedNextAcquire == null && staleBlockedAcquire == null) {
                "Only one fake blocked surface acquisition is supported"
            }
            blockedNextAcquire = CompletableDeferred()
        }

        fun blockedAcquireInFlight(): Boolean = staleBlockedAcquire != null

        fun invalidate(identity: PreviewSurfaceIdentity) {
            if (currentIdentity == identity) currentIdentity = null
        }

        fun publish(identity: PreviewSurfaceIdentity) {
            currentIdentity = identity
            val waiter = waitForNewSurface ?: return
            waitForNewSurface = null
            waiter.complete(FakeLease(identity))
        }

        fun publishReplacement(newIdentity: PreviewSurfaceIdentity) {
            publish(newIdentity)
        }

        fun releaseBlockedAcquire() {
            val wait = staleBlockedAcquire ?: blockedNextAcquire ?: return
            val acquiredIdentity = staleBlockedIdentity ?: currentIdentity
            blockedNextAcquire = null
            staleBlockedAcquire = null
            staleBlockedIdentity = null
            wait.complete(FakeLease(checkNotNull(acquiredIdentity) { "Blocked acquire had no surface identity" }))
        }
    }

    private class SeedSource(private val route: CameraRoute) : VisiblePreviewSeedSource {
        override suspend fun discoverSeed(): CameraRoute = route
    }

    private class CapabilitySource(private val main: LensSpec) : SelectedSeedPreviewCapabilitySource {
        override fun read(route: CameraRoute): SelectedSeedCapabilityResult = SelectedSeedCapabilityResult.Available(
            SelectedSeedPreviewCapabilities(
                capabilities = route.capabilities,
                sensorOrientationDegrees = main.orientation,
                lensFacing = main.facing,
            ),
        )
    }

    private data class LensSpec(
        val name: String,
        val facing: LensFacing = LensFacing.BACK,
        val size: IntSize = IntSize(1280, 720),
        val focal: Float = 4f,
        val sensorWidth: Float = 6f,
        val orientation: Int = 90,
        val physical: String? = null,
        val openId: String = "public",
    )

    private fun fixture(): Fixture {
        val specs = listOf(
            LensSpec("ultra", size = IntSize(1280, 720), focal = 2f),
            LensSpec("main", size = IntSize(1280, 720), focal = 4f),
            LensSpec("tele", size = IntSize(1920, 1080), focal = 8f),
            LensSpec("front", facing = LensFacing.FRONT, size = IntSize(1280, 720), focal = 3f, openId = "front-public"),
            LensSpec("physicalA", focal = 2.5f, physical = "member-a", openId = "logical-parent"),
            LensSpec("physicalB", focal = 7f, physical = "member-b", openId = "logical-parent"),
        )
        val topology = topology(specs)
        val events = mutableListOf<String>()
        val surface = FakeSurfacePort(events)
        val session = FakeSessionPort(events)
        val topologyFlow = MutableStateFlow<CameraTopologySnapshot?>(topology)
        val mainRoute = topology.routes.single { it.id == CameraRouteId("route:main") }
        val coordinator = VisiblePreviewCoordinator(
            seedSource = SeedSource(mainRoute),
            capabilitySource = CapabilitySource(specs.single { it.name == "main" }),
            surfacePort = surface,
            session = session,
            topology = topologyFlow,
            runtimeApiLevel = 35,
            settings = { SettingsSnapshot() },
            dispatcher = Dispatchers.Unconfined,
        )
        return Fixture(coordinator, topology, topologyFlow, surface, session, events)
    }

    private fun topology(specs: List<LensSpec>): CameraTopologySnapshot {
        val profiles = specs.map { spec ->
            val physical = spec.physical?.let(::PhysicalCameraId)
            val route = CameraRoute(
                id = CameraRouteId("route:${spec.name}"),
                source = if (physical == null) CameraRouteSource.JAVA_PUBLIC else CameraRouteSource.JAVA_PHYSICAL,
                openCameraId = CameraTransportId(spec.openId),
                physicalCameraId = physical,
                capabilities = CameraCapabilities(
                    previewStreams = listOf(
                        CameraStreamCapability(
                            PreviewStreamType.CAMERA2_PRIVATE,
                            spec.size,
                            33_333_333L,
                        ),
                    ),
                    fpsRanges = listOf(CameraFpsCapability(30, 30)),
                ),
                metadataTrust = CameraTrust.ADVERTISED,
            )
            CameraProfile(
                fingerprint = CameraProfileFingerprint("profile:${spec.name}"),
                canonicalFingerprint = lens(spec.name),
                route = route,
            )
        }
        val lenses = specs.map { spec ->
            CanonicalLens(
                fingerprint = lens(spec.name),
                facing = spec.facing,
                profiles = listOf(profiles.single { it.fingerprint == CameraProfileFingerprint("profile:${spec.name}") }),
            )
        }
        val evidence = specs.map { spec ->
            val profile = profiles.single { it.fingerprint == CameraProfileFingerprint("profile:${spec.name}") }
            CameraMetadataEvidence(
                source = profile.route.source,
                transportId = profile.route.openCameraId,
                physicalId = profile.route.physicalCameraId,
                facing = spec.facing,
                focalLengthsMillimetres = listOf(spec.focal),
                sensorPhysicalWidthMillimetres = spec.sensorWidth,
                sensorPhysicalHeightMillimetres = spec.sensorWidth * 0.75f,
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4000, 3000),
                sensorOrientationDegrees = spec.orientation,
                capabilities = profile.route.capabilities,
            )
        }
        return CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = CameraEnvironmentFingerprint("switch-test"),
            routes = profiles.map { it.route },
            canonicalLenses = lenses,
            generatedAtElapsedRealtimeNs = 1L,
            evidence = evidence,
        )
    }

    private companion object {
        fun lens(name: String) = CanonicalLensFingerprint("lens:$name")

        fun awaitUnit(block: suspend () -> Unit) {
            var result: Result<Unit>? = null
            block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
                override val context = kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(value: Result<Unit>) { result = value }
            })
            checkNotNull(result).getOrThrow()
        }
    }
}