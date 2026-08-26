package com.sahidcode404.camx.core.camera.model

import org.junit.Assert.assertThrows
import org.junit.Test

class CameraModelsTest {
    @Test
    fun identifiersRejectBlankValues() {
        assertThrows(IllegalArgumentException::class.java) { CameraRouteId(" ") }
        assertThrows(IllegalArgumentException::class.java) { CameraProfileFingerprint("") }
    }

    @Test
    fun resolvedPreviewConfigurationRejectsAutoAndBlankSignature() {
        val fps = PreviewFpsResolution(
            request = PreviewFpsRequest(false, 30, 30),
            resolvedRange = null,
            reason = PreviewFpsFallbackReason.OVERRIDE_DISABLED,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PreviewConfiguration(
                PreviewStreamType.AUTO,
                IntSize(1920, 1080),
                fps,
                highResolutionViewfinder = false,
                signature = "preview",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewConfiguration(
                PreviewStreamType.CAMERA2_PRIVATE,
                IntSize(1920, 1080),
                fps,
                highResolutionViewfinder = false,
                signature = " ",
            )
        }
    }

    @Test
    fun canonicalLensRejectsProfileFromAnotherLens() {
        val route = CameraRoute(
            id = CameraRouteId("route:a"),
            source = CameraRouteSource.JAVA_PUBLIC,
            openCameraId = CameraTransportId("opaque"),
            capabilities = CameraCapabilities(),
            metadataTrust = CameraTrust.ADVERTISED,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalLens(
                fingerprint = CanonicalLensFingerprint("lens:a"),
                facing = LensFacing.BACK,
                profiles = listOf(
                    CameraProfile(
                        fingerprint = CameraProfileFingerprint("profile:a"),
                        canonicalFingerprint = CanonicalLensFingerprint("lens:b"),
                        route = route,
                    ),
                ),
            )
        }
    }

    @Test
    fun topologyRejectsOneRouteAssignedToTwoCanonicalLenses() {
        val route = CameraRoute(
            id = CameraRouteId("route:shared"),
            source = CameraRouteSource.JAVA_PUBLIC,
            openCameraId = CameraTransportId("opaque"),
            capabilities = CameraCapabilities(),
            metadataTrust = CameraTrust.ADVERTISED,
        )
        val firstLens = CanonicalLensFingerprint("lens:first")
        val secondLens = CanonicalLensFingerprint("lens:second")
        assertThrows(IllegalArgumentException::class.java) {
            CameraTopologySnapshot(
                schema = 1,
                environment = CameraEnvironmentFingerprint("environment"),
                routes = listOf(route),
                canonicalLenses = listOf(
                    CanonicalLens(
                        firstLens,
                        LensFacing.BACK,
                        listOf(CameraProfile(CameraProfileFingerprint("profile:first"), firstLens, route)),
                    ),
                    CanonicalLens(
                        secondLens,
                        LensFacing.BACK,
                        listOf(CameraProfile(CameraProfileFingerprint("profile:second"), secondLens, route)),
                    ),
                ),
                generatedAtElapsedRealtimeNs = 1L,
            )
        }
    }

    @Test
    fun metadataAndRawContextRejectNonOrthogonalOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraMetadataEvidence(
                source = CameraRouteSource.JAVA_PUBLIC,
                transportId = CameraTransportId("opaque"),
                sensorOrientationDegrees = 45,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            rawContext(sensorOrientationDegrees = 360)
        }
    }

    @Test
    fun rawContextUsesTheBoundedPairingTimeoutContract() {
        rawContext(timeoutMillis = RawContractLimits.MAXIMUM_TIMEOUT_MILLIS)
        assertThrows(IllegalArgumentException::class.java) {
            rawContext(timeoutMillis = RawContractLimits.MAXIMUM_TIMEOUT_MILLIS + 1L)
        }
    }

    private fun rawContext(
        timeoutMillis: Long = RawContractLimits.DEFAULT_TIMEOUT_MILLIS,
        sensorOrientationDegrees: Int = 90,
    ) = RawCaptureContext(
        captureToken = CaptureToken(1L),
        selectionGeneration = SelectionGeneration(1L),
        sessionGeneration = SessionGeneration(1L),
        canonicalLensFingerprint = CanonicalLensFingerprint("lens:raw"),
        cameraProfileFingerprint = CameraProfileFingerprint("profile:raw"),
        routeId = CameraRouteId("route:raw"),
        displayRotationAtShutter = DisplayRotation.ROTATION_0,
        sensorOrientationDegrees = sensorOrientationDegrees,
        lensFacing = LensFacing.BACK,
        rawSize = IntSize(4000, 3000),
        timeoutMillis = timeoutMillis,
    )
}
