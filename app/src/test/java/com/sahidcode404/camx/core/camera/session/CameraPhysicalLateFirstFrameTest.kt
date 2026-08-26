package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhysicalLateFirstFrameTest {
    @Test
    fun latePhysicalBFirstFrameIsIgnoredAfterPhysicalCBecomesAuthoritative() {
        val platform = FakePlatform()
        val controller = CameraSessionController(
            platform = platform,
            dispatcher = Dispatchers.Unconfined,
            elapsedRealtimeNs = { 11L },
            workerCount = 1,
        )

        start(controller, "b", "physical-b", 1L)
        platform.opens.last().opened()
        platform.configurations.last().configured()
        val repeatB = platform.repeats.last()
        assertEquals(PhysicalCameraId("physical-b"), platform.configurations.last().physicalCameraId)

        start(controller, "c", "physical-c", 2L)
        platform.opens.last().opened()
        platform.configurations.last().configured()
        val repeatC = platform.repeats.last()
        assertEquals(PhysicalCameraId("physical-c"), platform.configurations.last().physicalCameraId)

        val beforeLateB = controller.state.value as CameraEngineState.Previewing
        assertEquals(CameraRouteId("route:c"), beforeLateB.selection.routeId)
        assertFalse(beforeLateB.firstFrameVerified)

        repeatB.frame()

        val afterLateB = controller.state.value as CameraEngineState.Previewing
        assertEquals(CameraRouteId("route:c"), afterLateB.selection.routeId)
        assertFalse(afterLateB.firstFrameVerified)

        repeatC.frame()

        val verifiedC = controller.state.value as CameraEngineState.Previewing
        assertEquals(CameraRouteId("route:c"), verifiedC.selection.routeId)
        assertTrue(verifiedC.firstFrameVerified)
        assertEquals(
            listOf(CameraTransportId("logical-parent"), CameraTransportId("logical-parent")),
            platform.opens.map { it.cameraId },
        )
    }

    private fun start(
        controller: CameraSessionController,
        name: String,
        physical: String,
        surfaceIdentity: Long,
    ) {
        runSuspend {
            controller.startPreviewForTest(
                selection = ActiveCameraSelection(
                    canonicalLensFingerprint = CanonicalLensFingerprint("lens:$name"),
                    profileFingerprint = CameraProfileFingerprint("profile:$name"),
                    routeId = CameraRouteId("route:$name"),
                    selectionGeneration = SelectionGeneration(0L),
                    sessionGeneration = SessionGeneration(0L),
                ),
                route = CameraRoute(
                    id = CameraRouteId("route:$name"),
                    source = CameraRouteSource.JAVA_PHYSICAL,
                    openCameraId = CameraTransportId("logical-parent"),
                    physicalCameraId = PhysicalCameraId(physical),
                    capabilities = CameraCapabilities(),
                    metadataTrust = CameraTrust.ADVERTISED,
                ),
                surfaceIdentity = PreviewSurfaceIdentity(surfaceIdentity),
                surfaceToken = Any(),
                closeSurface = {},
                configuration = configuration(),
                settings = SettingsSnapshot(),
            )
        }
    }

    private class FakePlatform : CameraOwnerPlatform {
        val opens = mutableListOf<OpenCall>()
        val configurations = mutableListOf<ConfigurationCall>()
        val repeats = mutableListOf<RepeatCall>()

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
            error("Targeted configuration seam is required")
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
            configurations += ConfigurationCall(physicalCameraId, callbacks)
        }

        override fun startRepeating(
            session: CameraCaptureSessionHandle,
            request: PreparedPreviewRequest,
            onFrame: () -> Unit,
        ) {
            repeats += RepeatCall(onFrame)
        }
    }

    private class OpenCall(
        val cameraId: CameraTransportId,
        private val callbacks: CameraOpenCallbacks,
    ) {
        private val device = FakeDevice()
        private val delivery = CloseOnceCameraResource<CameraDeviceHandle>(device, CameraDeviceHandle::close)
        fun opened() = callbacks.onOpened(delivery)
    }

    private class ConfigurationCall(
        val physicalCameraId: PhysicalCameraId?,
        private val callbacks: CameraSessionCallbacks,
    ) {
        private val session = FakeSession()
        private val delivery = CloseOnceCameraResource<CameraCaptureSessionHandle>(
            session,
            CameraCaptureSessionHandle::close,
        )
        fun configured() = callbacks.onConfigured(delivery, FakeRequest)
    }

    private class RepeatCall(private val onFrame: () -> Unit) {
        fun frame() = onFrame()
    }

    private class FakeDevice : CameraDeviceHandle {
        override fun close() = Unit
    }

    private class FakeSession : CameraCaptureSessionHandle {
        override fun close() = Unit
    }

    private data object FakeRequest : PreparedPreviewRequest

    private companion object {
        fun configuration() = PreviewConfiguration(
            streamType = PreviewStreamType.CAMERA2_PRIVATE,
            size = IntSize(1280, 720),
            fps = PreviewFpsResolution(
                request = PreviewFpsRequest(false, 30, 30),
                resolvedRange = null,
                reason = PreviewFpsFallbackReason.OVERRIDE_DISABLED,
            ),
            highResolutionViewfinder = false,
            signature = "physical-late-frame-test",
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
