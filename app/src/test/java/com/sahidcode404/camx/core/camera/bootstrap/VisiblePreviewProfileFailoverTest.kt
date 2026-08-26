package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.LensSwitchDiagnostics
import com.sahidcode404.camx.core.camera.diagnostics.SafeBaselineConfigurationRejected
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
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePreviewProfileFailoverTest {
    @Test
    fun `structural profile A failure uses same canonical planner without transient retry`() {
        val fixture = fixture()
        fixture.startMain()
        fixture.coordinator.selectLens(AUX)
        assertEquals(CameraRouteId("aux-public"), fixture.session.starts.last().route.id)
        val aSelection = fixture.session.starts.last().selection

        fixture.session.stateFlow.value = CameraEngineState.StructuralError(
            aSelection,
            SafeBaselineConfigurationRejected,
        )
        assertEquals(CameraRouteId("aux-deep"), fixture.session.starts.last().route.id)
        assertEquals(2, fixture.session.starts.count { it.selection.canonicalLensFingerprint == AUX })
        assertEquals(0L, fixture.diagnostics.value.transientRetryCount)
        assertEquals(0L, fixture.diagnostics.value.fallbackToLastVerifiedCount)

        fixture.session.verifyCurrent()
        val item = fixture.coordinator.lensItems.value.single { it.canonicalFingerprint == AUX }
        assertEquals(LensTestStatus.VERIFIED, item.status)
        assertTrue(item.selected)
    }

    @Test
    fun `second structural failure stops without transient retry fallback or A B A loop`() {
        val fixture = fixture()
        fixture.startMain()
        fixture.coordinator.selectLens(AUX)
        val a = fixture.session.starts.last().selection
        fixture.session.stateFlow.value = CameraEngineState.StructuralError(a, SafeBaselineConfigurationRejected)
        val b = fixture.session.starts.last().selection
        val startsBeforeSecondFailure = fixture.session.starts.size

        fixture.session.stateFlow.value = CameraEngineState.StructuralError(b, SafeBaselineConfigurationRejected)

        assertEquals(startsBeforeSecondFailure, fixture.session.starts.size)
        assertEquals(0L, fixture.diagnostics.value.transientRetryCount)
        assertEquals(0L, fixture.diagnostics.value.fallbackToLastVerifiedCount)
        assertEquals(LensTestStatus.FAILED, fixture.coordinator.lensItems.value.single { it.canonicalFingerprint == AUX }.status)
    }

    private data class StartCall(
        val selection: ActiveCameraSelection,
        val route: CameraRoute,
        val configuration: PreviewConfiguration,
    )

    private class FakeSession : VisiblePreviewSessionPort {
        val stateFlow = MutableStateFlow<CameraEngineState>(CameraEngineState.WaitingForSurface(null))
        override val state: StateFlow<CameraEngineState> = stateFlow
        val starts = mutableListOf<StartCall>()

        override suspend fun startPreview(
            selection: ActiveCameraSelection,
            route: CameraRoute,
            lease: VisiblePreviewLease,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
        ) {
            starts += StartCall(selection, route, configuration)
            stateFlow.value = CameraEngineState.Opening(selection, selection.sessionGeneration)
        }

        override suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) = Unit
        override suspend fun pause() {
            stateFlow.value = CameraEngineState.WaitingForSurface(starts.lastOrNull()?.selection)
        }
        override suspend fun shutdown() {
            stateFlow.value = CameraEngineState.Closed
        }

        fun verifyCurrent() {
            stateFlow.value = CameraEngineState.Previewing(starts.last().selection, true)
        }
    }

    private class Surface : VisiblePreviewSurfacePort {
        override suspend fun awaitSurface(): VisiblePreviewLease = object : VisiblePreviewLease {
            override val identity = PreviewSurfaceIdentity(99L)
            override val viewSize = IntSize(1080, 1920)
            override val bufferSize = IntSize(1280, 720)
            override fun close() = Unit
        }
        override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) = Unit
    }

    private data class Fixture(
        val coordinator: VisiblePreviewCoordinator,
        val session: FakeSession,
        val diagnostics: MutableStateFlow<LensSwitchDiagnostics>,
    ) {
        fun startMain() {
            coordinator.setPermission(true)
            coordinator.resume(DisplayRotation.ROTATION_0)
            session.verifyCurrent()
        }
    }

    private fun fixture(): Fixture {
        val topology = topology()
        val main = topology.routes.single { it.id == CameraRouteId("main") }
        val session = FakeSession()
        val diagnostics = MutableStateFlow(LensSwitchDiagnostics())
        val coordinator = VisiblePreviewCoordinator(
            seedSource = VisiblePreviewSeedSource { main },
            capabilitySource = SelectedSeedPreviewCapabilitySource { route ->
                SelectedSeedCapabilityResult.Available(
                    SelectedSeedPreviewCapabilities(route.capabilities, 90, LensFacing.BACK),
                )
            },
            surfacePort = Surface(),
            session = session,
            topology = MutableStateFlow(topology),
            runtimeApiLevel = 35,
            settings = { SettingsSnapshot() },
            switchDiagnosticsSink = { diagnostics.value = it },
            dispatcher = Dispatchers.Unconfined,
        )
        return Fixture(coordinator, session, diagnostics)
    }

    private fun topology(): CameraTopologySnapshot {
        val main = route("main", CameraRouteSource.JAVA_PUBLIC)
        val auxPublic = route("aux-public", CameraRouteSource.JAVA_PUBLIC)
        val auxDeep = route("aux-deep", CameraRouteSource.JAVA_DEEP_PROBED)
        val mainProfile = profile(main, MAIN)
        val auxPublicProfile = profile(auxPublic, AUX)
        val auxDeepProfile = profile(auxDeep, AUX)
        return CameraTopologySnapshot(
            schema = CameraSchemaVersions.TOPOLOGY,
            environment = CameraEnvironmentFingerprint("d2-failover"),
            routes = listOf(main, auxPublic, auxDeep),
            canonicalLenses = listOf(
                CanonicalLens(MAIN, LensFacing.BACK, listOf(mainProfile)),
                CanonicalLens(AUX, LensFacing.BACK, listOf(auxPublicProfile, auxDeepProfile)),
            ),
            generatedAtElapsedRealtimeNs = 1L,
            evidence = listOf(
                evidence(main, CameraRouteSource.JAVA_PUBLIC, 4f),
                evidence(auxPublic, CameraRouteSource.JAVA_PUBLIC, 2f),
                evidence(auxDeep, CameraRouteSource.JAVA_DEEP_PROBED, 2f),
            ),
        )
    }

    private fun route(id: String, source: CameraRouteSource) = CameraRoute(
        id = CameraRouteId(id),
        source = source,
        openCameraId = CameraTransportId(id),
        capabilities = CameraCapabilities(
            previewStreams = listOf(
                CameraStreamCapability(PreviewStreamType.CAMERA2_PRIVATE, IntSize(1280, 720), 33_333_333L),
            ),
            fpsRanges = listOf(CameraFpsCapability(30, 30)),
        ),
        metadataTrust = CameraTrust.ADVERTISED,
    )

    private fun profile(route: CameraRoute, lens: CanonicalLensFingerprint) = CameraProfile(
        fingerprint = CameraProfileFingerprint("profile:${route.id.value}"),
        canonicalFingerprint = lens,
        route = route,
    )

    private fun evidence(route: CameraRoute, source: CameraRouteSource, focal: Float) = CameraMetadataEvidence(
        source = source,
        transportId = route.openCameraId,
        facing = LensFacing.BACK,
        focalLengthsMillimetres = listOf(focal),
        sensorPhysicalWidthMillimetres = 6f,
        sensorPhysicalHeightMillimetres = 4.5f,
        sensorOrientationDegrees = 90,
        capabilities = route.capabilities,
    )

    private companion object {
        val MAIN = CanonicalLensFingerprint("lens:main")
        val AUX = CanonicalLensFingerprint("lens:aux")
    }
}
