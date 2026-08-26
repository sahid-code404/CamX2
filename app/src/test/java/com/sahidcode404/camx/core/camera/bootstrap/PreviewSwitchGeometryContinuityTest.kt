package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.CameraInUse
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
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import com.sahidcode404.camx.feature.camera.shouldRevealPreviewSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSwitchGeometryContinuityTest {
    @Test
    fun ordinarySwitchRetainsVerifiedOutgoingRenderUntilTargetPolicyIsReady() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        val outgoing = checkNotNull(fixture.coordinator.renderSpec.value)
        fixture.surface.blockNextAcquire()

        fixture.coordinator.selectLens(lens("fourThree"))

        assertEquals(outgoing, fixture.coordinator.renderSpec.value)
        assertEquals(VisiblePreviewUiState.WaitingForSurface, fixture.coordinator.uiState.value)
        assertTrue(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, fixture.coordinator.renderSpec.value))

        fixture.surface.releaseBlockedAcquire()
        val target = checkNotNull(fixture.coordinator.renderSpec.value)
        assertEquals(IntSize(1440, 1080), target.bufferSize)
        assertNotEquals(outgoing, target)
        assertTrue(fixture.coordinator.uiState.value is VisiblePreviewUiState.Opening)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, target))
    }

    @Test
    fun targetBufferAndGeometryPublishTogetherAndControllerWaitsForExactBufferAck() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        val starts = fixture.session.starts.size
        fixture.surface.blockNextBufferAck()

        fixture.coordinator.selectLens(lens("fourThree"))

        val target = checkNotNull(fixture.coordinator.renderSpec.value)
        assertEquals(IntSize(1440, 1080), target.bufferSize)
        assertEquals(90, target.geometry.clockwiseRotationDegrees)
        assertEquals(starts, fixture.session.starts.size)
        assertEquals(
            "buffer:${fixture.surface.identity.value}:1440x1080",
            fixture.events.last(),
        )

        fixture.surface.releaseBlockedBufferAck()
        assertEquals(starts + 1, fixture.session.starts.size)
        assertEquals(CameraRouteId("route:fourThree"), fixture.session.starts.last().route.id)
    }

    @Test
    fun ninetyToTwoSeventySwitchNeverPublishesIdentityRotationIntermediate() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        assertEquals(90, fixture.coordinator.renderSpec.value?.geometry?.clockwiseRotationDegrees)
        fixture.surface.blockNextBufferAck()

        fixture.coordinator.selectLens(lens("rotated"))

        val target = checkNotNull(fixture.coordinator.renderSpec.value)
        assertEquals(270, target.geometry.clockwiseRotationDegrees)
        assertTrue(fixture.coordinator.uiState.value is VisiblePreviewUiState.Opening)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, target))
        fixture.surface.releaseBlockedBufferAck()
        fixture.session.verifyCurrent()
        assertEquals(270, fixture.coordinator.renderSpec.value?.geometry?.clockwiseRotationDegrees)
        assertTrue(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, fixture.coordinator.renderSpec.value))
    }

    @Test
    fun rearFrontRearAppliesMirrorOnlyBehindNeutralTargetTransition() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        assertFalse(checkNotNull(fixture.coordinator.renderSpec.value).geometry.mirrorHorizontally)

        fixture.surface.blockNextBufferAck()
        fixture.coordinator.selectLens(lens("front"))
        val frontTarget = checkNotNull(fixture.coordinator.renderSpec.value)
        assertTrue(frontTarget.geometry.mirrorHorizontally)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, frontTarget))
        fixture.surface.releaseBlockedBufferAck()
        fixture.session.verifyCurrent()
        assertTrue(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, fixture.coordinator.renderSpec.value))

        fixture.surface.blockNextBufferAck()
        fixture.coordinator.selectLens(lens("main"))
        val rearTarget = checkNotNull(fixture.coordinator.renderSpec.value)
        assertFalse(rearTarget.geometry.mirrorHorizontally)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, rearTarget))
        fixture.surface.releaseBlockedBufferAck()
        fixture.session.verifyCurrent()
        assertFalse(checkNotNull(fixture.coordinator.renderSpec.value).geometry.mirrorHorizontally)
    }

    @Test
    fun rapidAToBToCIgnoresLateBBufferAndVerifiedFrameForPresentation() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.surface.blockNextBufferAck()

        fixture.coordinator.selectLens(lens("fourThree"))
        val staleSelection = fixture.selectionFor("fourThree")
        assertEquals(IntSize(1440, 1080), fixture.coordinator.renderSpec.value?.bufferSize)

        fixture.coordinator.selectLens(lens("rotated"))
        val winningRender = checkNotNull(fixture.coordinator.renderSpec.value)
        assertEquals(270, winningRender.geometry.clockwiseRotationDegrees)
        assertEquals(CameraRouteId("route:rotated"), fixture.session.starts.last().route.id)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, winningRender))

        fixture.session.stateFlow.value = CameraEngineState.Previewing(staleSelection, true)
        assertEquals(winningRender, fixture.coordinator.renderSpec.value)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, winningRender))

        fixture.surface.releaseBlockedBufferAck()
        assertEquals(winningRender, fixture.coordinator.renderSpec.value)
        fixture.session.verifyCurrent()
        assertTrue(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, fixture.coordinator.renderSpec.value))
        assertEquals(270, fixture.coordinator.renderSpec.value?.geometry?.clockwiseRotationDegrees)
    }

    @Test
    fun transientDifferentGeometryTargetStaysCoveredThroughRetryThenReveals() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("front"))
        val target = checkNotNull(fixture.coordinator.renderSpec.value)
        val targetSelection = fixture.session.starts.last().selection

        fixture.session.stateFlow.value = CameraEngineState.RecoverableError(targetSelection, CameraInUse)

        assertEquals(target, fixture.coordinator.renderSpec.value)
        assertTrue(fixture.coordinator.uiState.value is VisiblePreviewUiState.Opening)
        assertEquals(CameraRouteId("route:front"), fixture.session.starts.last().route.id)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, target))

        fixture.session.verifyCurrent()
        assertTrue(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, fixture.coordinator.renderSpec.value))
        assertTrue(checkNotNull(fixture.coordinator.renderSpec.value).geometry.mirrorHorizontally)
    }

    @Test
    fun retryExhaustionFallsBackBehindCoverAndRevealsLastVerifiedGeometry() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        fixture.coordinator.selectLens(lens("front"))
        val firstAttempt = fixture.session.starts.last().selection
        fixture.session.stateFlow.value = CameraEngineState.RecoverableError(firstAttempt, CameraInUse)
        val retryAttempt = fixture.session.starts.last().selection
        fixture.session.stateFlow.value = CameraEngineState.RecoverableError(retryAttempt, CameraInUse)

        assertEquals(CameraRouteId("route:main"), fixture.session.starts.last().route.id)
        assertTrue(fixture.coordinator.uiState.value is VisiblePreviewUiState.Opening)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, fixture.coordinator.renderSpec.value))

        fixture.session.verifyCurrent()
        val restored = checkNotNull(fixture.coordinator.renderSpec.value)
        assertFalse(restored.geometry.mirrorHorizontally)
        assertTrue(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, restored))
    }

    @Test
    fun trueSurfaceInvalidationAndLifecyclePauseStillClearPresentation() {
        val fixture = fixture()
        fixture.startAndVerifyMain()
        val identity = fixture.surface.identity

        fixture.surface.invalidate(identity)
        fixture.coordinator.surfaceInvalidated(identity)
        assertNull(fixture.coordinator.renderSpec.value)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, null))

        fixture.surface.publish(PreviewSurfaceIdentity(identity.value + 1L))
        fixture.session.verifyCurrent()
        fixture.coordinator.pause()
        assertNull(fixture.coordinator.renderSpec.value)
        assertFalse(shouldRevealPreviewSurface(fixture.coordinator.uiState.value, null))
    }

    private data class LensSpec(
        val name: String,
        val facing: LensFacing = LensFacing.BACK,
        val size: IntSize,
        val focal: Float,
        val orientation: Int,
        val openId: String = name,
    )

    private class Fixture(
        val coordinator: VisiblePreviewCoordinator,
        val topology: CameraTopologySnapshot,
        val surface: FakeSurfacePort,
        val session: FakeSessionPort,
        val events: MutableList<String>,
    ) {
        fun startAndVerifyMain() {
            coordinator.setPermission(true)
            coordinator.resume(DisplayRotation.ROTATION_0)
            session.verifyCurrent()
            assertTrue(coordinator.uiState.value is VisiblePreviewUiState.Previewing)
            assertTrue(shouldRevealPreviewSurface(coordinator.uiState.value, coordinator.renderSpec.value))
        }

        fun selectionFor(name: String): ActiveCameraSelection {
            val profile = topology.canonicalLenses
                .single { it.fingerprint == lens(name) }
                .profiles.single()
            return ActiveCameraSelection(
                canonicalLensFingerprint = profile.canonicalFingerprint,
                profileFingerprint = profile.fingerprint,
                routeId = profile.route.id,
                selectionGeneration = com.sahidcode404.camx.core.camera.model.SelectionGeneration(0L),
                sessionGeneration = com.sahidcode404.camx.core.camera.model.SessionGeneration(0L),
            )
        }
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
        private var activeLease: VisiblePreviewLease? = null

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
            val lease = activeLease
            if (lease != null && lease.identity == identity) {
                lease.close()
                activeLease = null
            }
            stateFlow.value = CameraEngineState.WaitingForSurface(starts.lastOrNull()?.selection)
        }

        override suspend fun pause() {
            events += "pause"
            activeLease?.close()
            activeLease = null
            stateFlow.value = CameraEngineState.WaitingForSurface(starts.lastOrNull()?.selection)
        }

        override suspend fun shutdown() {
            activeLease?.close()
            activeLease = null
            stateFlow.value = CameraEngineState.Closed
        }

        fun verifyCurrent() {
            val selection = starts.last().selection
            stateFlow.value = CameraEngineState.Previewing(selection, true)
        }
    }

    private class FakeLease(
        override val identity: PreviewSurfaceIdentity,
        override val bufferSize: IntSize,
    ) : VisiblePreviewLease {
        override val viewSize = IntSize(1080, 1920)
        override fun close() = Unit
    }

    private class FakeSurfacePort(private val events: MutableList<String>) : VisiblePreviewSurfacePort {
        private var currentIdentity: PreviewSurfaceIdentity? = PreviewSurfaceIdentity(71L)
        private var currentBuffer = IntSize(1080, 1920)
        private var blockAcquire = false
        private var blockedAcquire: CompletableDeferred<VisiblePreviewLease>? = null
        private var blockBuffer = false
        private var blockedBuffer: CompletableDeferred<Unit>? = null
        private var waitForSurface: CompletableDeferred<VisiblePreviewLease>? = null

        val identity: PreviewSurfaceIdentity
            get() = checkNotNull(currentIdentity)

        override suspend fun awaitSurface(): VisiblePreviewLease {
            val identity = currentIdentity
            events += "await:${identity?.value ?: "none"}"
            if (blockAcquire) {
                blockAcquire = false
                val deferred = CompletableDeferred<VisiblePreviewLease>()
                blockedAcquire = deferred
                return deferred.await()
            }
            if (identity != null) return FakeLease(identity, currentBuffer)
            val deferred = waitForSurface ?: CompletableDeferred<VisiblePreviewLease>().also { waitForSurface = it }
            return deferred.await()
        }

        override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) {
            check(identity == currentIdentity) { "Stale surface identity cannot acknowledge a target buffer" }
            events += "buffer:${identity.value}:${size.width}x${size.height}"
            if (blockBuffer) {
                blockBuffer = false
                val deferred = CompletableDeferred<Unit>()
                blockedBuffer = deferred
                deferred.await()
            }
            check(identity == currentIdentity) { "Surface changed while target buffer acknowledgement was pending" }
            currentBuffer = size
        }

        fun blockNextAcquire() {
            blockAcquire = true
        }

        fun releaseBlockedAcquire() {
            val deferred = checkNotNull(blockedAcquire)
            blockedAcquire = null
            deferred.complete(FakeLease(identity, currentBuffer))
        }

        fun blockNextBufferAck() {
            blockBuffer = true
        }

        fun releaseBlockedBufferAck() {
            val deferred = blockedBuffer ?: return
            blockedBuffer = null
            deferred.complete(Unit)
        }

        fun invalidate(identity: PreviewSurfaceIdentity) {
            if (currentIdentity == identity) currentIdentity = null
        }

        fun publish(identity: PreviewSurfaceIdentity) {
            currentIdentity = identity
            val deferred = waitForSurface ?: return
            waitForSurface = null
            deferred.complete(FakeLease(identity, currentBuffer))
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

    private fun fixture(): Fixture {
        val specs = listOf(
            LensSpec("main", size = IntSize(1920, 1080), focal = 4f, orientation = 90),
            LensSpec("fourThree", size = IntSize(1440, 1080), focal = 2f, orientation = 90),
            LensSpec("rotated", size = IntSize(1280, 720), focal = 8f, orientation = 270),
            LensSpec(
                "front",
                facing = LensFacing.FRONT,
                size = IntSize(1440, 1080),
                focal = 3f,
                orientation = 270,
            ),
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
        return Fixture(coordinator, topology, surface, session, events)
    }

    private fun topology(specs: List<LensSpec>): CameraTopologySnapshot {
        val profiles = specs.map { spec ->
            val route = CameraRoute(
                id = CameraRouteId("route:${spec.name}"),
                source = CameraRouteSource.JAVA_PUBLIC,
                openCameraId = CameraTransportId(spec.openId),
                capabilities = CameraCapabilities(
                    previewStreams = listOf(
                        CameraStreamCapability(
                            type = PreviewStreamType.CAMERA2_PRIVATE,
                            size = spec.size,
                            minimumFrameDurationNs = 33_333_333L,
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
        val lenses = specs.mapIndexed { index, spec ->
            CanonicalLens(
                fingerprint = lens(spec.name),
                facing = spec.facing,
                profiles = listOf(profiles[index]),
            )
        }
        val evidence = specs.mapIndexed { index, spec ->
            val route = profiles[index].route
            CameraMetadataEvidence(
                source = CameraRouteSource.JAVA_PUBLIC,
                transportId = route.openCameraId,
                facing = spec.facing,
                focalLengthsMillimetres = listOf(spec.focal),
                sensorPhysicalWidthMillimetres = 6f,
                sensorPhysicalHeightMillimetres = 4.5f,
                activeArray = IntSize(4000, 3000),
                pixelArray = IntSize(4000, 3000),
                sensorOrientationDegrees = spec.orientation,
                capabilities = route.capabilities,
            )
        }
        return CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = CameraEnvironmentFingerprint("switch-geometry-continuity"),
            routes = profiles.map { it.route },
            canonicalLenses = lenses,
            generatedAtElapsedRealtimeNs = 1L,
            evidence = evidence,
        )
    }

    private companion object {
        fun lens(name: String) = CanonicalLensFingerprint("lens:$name")
    }
}
