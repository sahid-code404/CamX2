package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhysicalTargetingTest {
    @Test
    fun directRouteUsesNoPhysicalTargetAndPreservesLogicalOpenId() {
        val fixture = Fixture()
        fixture.start("main", physicalId = null, logicalOpenId = "opaque-logical")
        val open = fixture.platform.opens.single()

        assertEquals(CameraTransportId("opaque-logical"), open.cameraId)
        open.opened()

        val configuration = fixture.platform.configurations.single()
        assertEquals(null, configuration.physicalCameraId)
        assertEquals(PreviewConfigurationAttemptKind.REQUESTED, configuration.attempt)
        assertSame(open.device, configuration.device)
    }

    @Test
    fun physicalRouteOpensLogicalParentAndTargetsPhysicalMember() {
        val fixture = Fixture()
        fixture.start("ultrawide", PhysicalCameraId("opaque-physical-u"), "opaque-logical")
        val open = fixture.platform.opens.single()

        assertEquals(CameraTransportId("opaque-logical"), open.cameraId)
        open.opened()

        val configuration = fixture.platform.configurations.single()
        assertEquals(PhysicalCameraId("opaque-physical-u"), configuration.physicalCameraId)
        assertSame(open.device, configuration.device)
    }

    @Test
    fun requestedToSafeBaselineKeepsExactPhysicalTarget() {
        val settings = requestedFpsSettings()
        val fixture = Fixture(settings)
        fixture.start("tele", PhysicalCameraId("opaque-physical-t"), "opaque-logical")
        fixture.platform.opens.single().opened()

        fixture.platform.configurations.single().failed()

        assertEquals(2, fixture.platform.configurations.size)
        assertEquals(
            listOf(PreviewConfigurationAttemptKind.REQUESTED, PreviewConfigurationAttemptKind.SAFE_BASELINE),
            fixture.platform.configurations.map { it.attempt },
        )
        assertTrue(
            fixture.platform.configurations.all {
                it.physicalCameraId == PhysicalCameraId("opaque-physical-t")
            },
        )
        assertSame(
            fixture.platform.configurations[0].device,
            fixture.platform.configurations[1].device,
        )
    }

    @Test
    fun physicalAToPhysicalBSeparatesTargetsAndLateASessionCannotAffectB() {
        val fixture = Fixture()
        fixture.start("a", PhysicalCameraId("physical-a"), "logical-parent")
        fixture.platform.opens.last().opened()
        val configurationA = fixture.platform.configurations.last()

        fixture.start("b", PhysicalCameraId("physical-b"), "logical-parent")
        fixture.platform.opens.last().opened()
        val configurationB = fixture.platform.configurations.last()

        assertEquals(PhysicalCameraId("physical-a"), configurationA.physicalCameraId)
        assertEquals(PhysicalCameraId("physical-b"), configurationB.physicalCameraId)
        configurationA.configured()

        assertEquals(1, configurationA.session.closeCount)
        assertEquals(0, configurationB.session.closeCount)
        assertEquals(CameraRouteId("route:b"), fixture.controller.state.value.selectionOrNull()?.routeId)

        configurationB.configured()
        assertTrue(fixture.controller.state.value is CameraEngineState.Previewing)
    }

    private class Fixture(
        private val settings: SettingsSnapshot = SettingsSnapshot(),
    ) {
        val platform = FakePlatform()
        val controller = CameraSessionController(
            platform = platform,
            dispatcher = Dispatchers.Unconfined,
            elapsedRealtimeNs = { 7L },
            workerCount = 1,
        )
        private var surfaceSequence = 0L

        fun start(
            name: String,
            physicalId: PhysicalCameraId?,
            logicalOpenId: String,
        ) {
            val surface = FakeSurface(PreviewSurfaceIdentity(++surfaceSequence))
            runSuspend {
                controller.startPreviewForTest(
                    selection = selection(name),
                    route = route(name, physicalId, logicalOpenId),
                    surfaceIdentity = surface.identity,
                    surfaceToken = surface.token,
                    closeSurface = surface::close,
                    configuration = previewConfiguration(settings.fpsRequest),
                    settings = settings,
                )
            }
        }
    }

    private class FakePlatform : CameraOwnerPlatform {
        val opens = mutableListOf<OpenCall>()
        val configurations = mutableListOf<ConfigurationCall>()

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
            error("CameraSessionController must use the typed targeted preview seam")
        }

        override fun configurePreviewTargeted(
            device: CameraDeviceHandle,
            surfaceToken: Any,
            physicalCameraId: PhysicalCameraId?,
            configuration: PreviewConfiguration,
            settings: SettingsSnapshot,
            attempt: PreviewConfigurationAttemptKind,
            callbacks: CameraSessionCallbacks,
        ) {
            configurations += ConfigurationCall(device, physicalCameraId, attempt, callbacks)
        }

        override fun startRepeating(
            session: CameraCaptureSessionHandle,
            request: PreparedPreviewRequest,
            onFrame: () -> Unit,
        ) = Unit
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
        val physicalCameraId: PhysicalCameraId?,
        val attempt: PreviewConfigurationAttemptKind,
        private val callbacks: CameraSessionCallbacks,
    ) {
        val session = FakeSession()
        private val delivery = CloseOnceCameraResource<CameraCaptureSessionHandle>(
            session,
            CameraCaptureSessionHandle::close,
        )
        fun configured() = callbacks.onConfigured(delivery, FakeRequest)
        fun failed() = callbacks.onConfigureFailed(delivery)
    }

    private class FakeDevice : CameraDeviceHandle {
        override fun close() = Unit
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
        fun close() = Unit
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

        fun route(
            name: String,
            physicalId: PhysicalCameraId?,
            logicalOpenId: String,
        ) = CameraRoute(
            id = CameraRouteId("route:$name"),
            source = if (physicalId == null) CameraRouteSource.JAVA_PUBLIC else CameraRouteSource.JAVA_PHYSICAL,
            openCameraId = CameraTransportId(logicalOpenId),
            physicalCameraId = physicalId,
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
            size = IntSize(1280, 720),
            fps = PreviewFpsResolution(
                request = request,
                resolvedRange = CameraFpsCapability(30, 30),
                reason = PreviewFpsFallbackReason.EXACT_MATCH,
            ),
            highResolutionViewfinder = false,
            signature = "aux-target-test",
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
            return checkNotNull(outcome) { "Deterministic owner test unexpectedly suspended" }.getOrThrow()
        }
    }
}
