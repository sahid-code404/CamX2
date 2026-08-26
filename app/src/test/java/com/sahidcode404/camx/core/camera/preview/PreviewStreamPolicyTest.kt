package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewStreamPolicyTest {
    @Test
    fun emptyCapabilitiesAreTypedUnsupported() {
        assertUnsupported(input(streams = emptyList()), PreviewUnsupportedReason.NO_ADVERTISED_STREAMS)
    }

    @Test
    fun singleSupportedCandidateIsSelectedExactly() {
        val candidate = stream(PreviewStreamType.CAMERA2_PRIVATE, 1600, 900)
        val result = supported(input(streams = listOf(candidate), view = IntSize(1600, 900)))
        assertEquals(candidate.type, result.configuration.streamType)
        assertEquals(candidate.size, result.configuration.size)
    }

    @Test
    fun autoPrefersPrivateDisplayStreamWhenCadenceIsEquivalent() {
        val private = stream(PreviewStreamType.CAMERA2_PRIVATE, 1280, 720)
        val yuv = stream(PreviewStreamType.CAMERA2_YUV_420_888, 1920, 1080)
        val result = supported(input(streams = listOf(yuv, private), view = IntSize(1920, 1080)))
        assertEquals(PreviewStreamType.CAMERA2_PRIVATE, result.configuration.streamType)
        assertEquals(private.size, result.configuration.size)
    }

    @Test
    fun explicitPrivateNeverSelectsAdvertisedYuv() {
        val private = stream(PreviewStreamType.CAMERA2_PRIVATE, 1440, 1080)
        val yuv = stream(PreviewStreamType.CAMERA2_YUV_420_888, 1920, 1080)
        val result = supported(
            input(
                streams = listOf(yuv, private),
                requestedType = PreviewStreamType.CAMERA2_PRIVATE,
                view = IntSize(1920, 1080),
            ),
        )
        assertEquals(private.type, result.configuration.streamType)
        assertEquals(private.size, result.configuration.size)
    }

    @Test
    fun explicitYuvSelectsOnlyAdvertisedYuvCapability() {
        val private = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)
        val yuv = stream(PreviewStreamType.CAMERA2_YUV_420_888, 1440, 1080)
        val result = supported(
            input(
                streams = listOf(private, yuv),
                requestedType = PreviewStreamType.CAMERA2_YUV_420_888,
                view = IntSize(1920, 1080),
            ),
        )
        assertEquals(yuv.type, result.configuration.streamType)
        assertEquals(yuv.size, result.configuration.size)
    }

    @Test
    fun unavailableExplicitTypeIsTypedUnsupported() {
        assertUnsupported(
            input(
                streams = listOf(stream(PreviewStreamType.CAMERA2_PRIVATE, 1280, 720)),
                requestedType = PreviewStreamType.CAMERA2_YUV_420_888,
            ),
            PreviewUnsupportedReason.REQUESTED_STREAM_TYPE_UNAVAILABLE,
        )
    }

    @Test
    fun normalPolicyAvoidsUnnecessarilyHugeCandidate() {
        val small = stream(PreviewStreamType.CAMERA2_PRIVATE, 1280, 720)
        val responsive = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)
        val huge = stream(PreviewStreamType.CAMERA2_PRIVATE, 3840, 2160)
        val result = supported(
            input(
                streams = listOf(huge, small, responsive),
                view = IntSize(1080, 2400),
                sensorOrientation = 90,
            ),
        )
        assertEquals(responsive.size, result.configuration.size)
        assertEquals(PreviewStreamSelectionReason.RESPONSIVE, result.selectionReason)
    }

    @Test
    fun normalPolicyRejectsClearlyUnderservingCandidateWhenBetterAlternativeExists() {
        val tiny = stream(PreviewStreamType.CAMERA2_PRIVATE, 640, 360)
        val larger = stream(PreviewStreamType.CAMERA2_PRIVATE, 3840, 2160)
        val result = supported(input(streams = listOf(tiny, larger), view = IntSize(1920, 1080)))
        assertEquals(larger.size, result.configuration.size)
    }

    @Test
    fun highResolutionOffTargetsViewEffectivePixelDensity() {
        val viewSized = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)
        val oversampled = stream(PreviewStreamType.CAMERA2_PRIVATE, 3840, 2160)
        val result = supported(input(streams = listOf(oversampled, viewSized), view = IntSize(1920, 1080)))
        assertEquals(viewSized.size, result.configuration.size)
        assertFalse(result.configuration.highResolutionViewfinder)
    }

    @Test
    fun highResolutionOnTargetsTwoTimesLinearOversamplingWithoutBlindlyTakingLargest() {
        val viewSized = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)
        val twoX = stream(PreviewStreamType.CAMERA2_PRIVATE, 3840, 2160)
        val fourX = stream(PreviewStreamType.CAMERA2_PRIVATE, 7680, 4320)
        val result = supported(
            input(
                streams = listOf(fourX, viewSized, twoX),
                view = IntSize(1920, 1080),
                highResolution = true,
            ),
        )
        assertEquals(twoX.size, result.configuration.size)
        assertTrue(result.configuration.highResolutionViewfinder)
        assertEquals(PreviewStreamSelectionReason.HIGH_RESOLUTION_TARGET, result.selectionReason)
    }

    @Test
    fun highResolutionUsesBestAdvertisedFallbackWhenTargetIsUnavailable() {
        val small = stream(PreviewStreamType.CAMERA2_PRIVATE, 960, 540)
        val viewSized = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)
        val result = supported(
            input(
                streams = listOf(small, viewSized),
                view = IntSize(2560, 1440),
                highResolution = true,
            ),
        )
        assertEquals(viewSized.size, result.configuration.size)
        assertEquals(PreviewStreamSelectionReason.HIGH_RESOLUTION_BEST_AVAILABLE, result.selectionReason)
    }

    @Test
    fun knownCadenceCompatibleCandidateBeatsHigherResolutionCadenceConflict() {
        val fps = PreviewFpsRequest(true, 30, 60)
        val fast = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080, 16_000_000L)
        val slow = stream(PreviewStreamType.CAMERA2_PRIVATE, 3840, 2160, 40_000_000L)
        val result = supported(
            input(
                streams = listOf(slow, fast),
                view = IntSize(1920, 1080),
                highResolution = true,
                fpsRequest = fps,
                ranges = listOf(CameraFpsCapability(30, 30), CameraFpsCapability(30, 60)),
            ),
        )
        assertEquals(fast.size, result.configuration.size)
        assertEquals(CameraFpsCapability(30, 60), result.configuration.fps.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.configuration.fps.reason)
    }

    @Test
    fun unknownMinimumFrameDurationRemainsUsableButDoesNotOutrankKnownCompatibleEvidence() {
        val request = PreviewFpsRequest(true, 30, 30)
        val known = stream(PreviewStreamType.CAMERA2_PRIVATE, 1600, 900, 30_000_000L)
        val unknown = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080, null)
        val result = supported(
            input(
                streams = listOf(unknown, known),
                view = IntSize(1920, 1080),
                fpsRequest = request,
                ranges = listOf(CameraFpsCapability(30, 30)),
            ),
        )
        assertEquals(known.size, result.configuration.size)
    }

    @Test
    fun unknownMinimumFrameDurationAloneDoesNotInventOrRejectAdvertisedFpsRange() {
        val request = PreviewFpsRequest(true, 30, 30)
        val only = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080, null)
        val result = supported(
            input(
                streams = listOf(only),
                fpsRequest = request,
                ranges = listOf(CameraFpsCapability(30, 30)),
            ),
        )
        assertEquals(CameraFpsCapability(30, 30), result.configuration.fps.resolvedRange)
        assertEquals(PreviewFpsFallbackReason.EXACT_MATCH, result.configuration.fps.reason)
    }

    @Test
    fun allCandidatesBlockedByKnownMinimumFrameDurationAreUnsupported() {
        val request = PreviewFpsRequest(true, 30, 60)
        val slow = stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080, 50_000_000L)
        assertUnsupported(
            input(
                streams = listOf(slow),
                fpsRequest = request,
                ranges = listOf(CameraFpsCapability(30, 60)),
            ),
            PreviewUnsupportedReason.NO_CADENCE_COMPATIBLE_STREAM,
        )
    }

    @Test
    fun enabledFpsOverrideWithNoReportedRangesIsUnsupported() {
        assertUnsupported(
            input(
                streams = listOf(stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)),
                fpsRequest = PreviewFpsRequest(true, 30, 30),
                ranges = emptyList(),
            ),
            PreviewUnsupportedReason.NO_REPORTED_FPS_RANGES,
        )
    }

    @Test
    fun invalidFpsRequestIsTypedUnsupportedWithoutDuplicatingResolverPolicy() {
        assertUnsupported(
            input(
                streams = listOf(stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080)),
                fpsRequest = PreviewFpsRequest(true, 60, 30),
            ),
            PreviewUnsupportedReason.INVALID_FPS_REQUEST,
        )
    }

    @Test
    fun portraitLandscapeSquareAndMismatchedAspectInputsAlwaysSelectAdvertisedSizes() {
        val streams = listOf(
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1280, 960),
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080),
            stream(PreviewStreamType.CAMERA2_PRIVATE, 2560, 1440),
        )
        val views = listOf(
            IntSize(1080, 2400),
            IntSize(2400, 1080),
            IntSize(1200, 1200),
            IntSize(1600, 900),
            IntSize(1200, 1600),
        )
        for (view in views) {
            val result = supported(input(streams = streams, view = view, sensorOrientation = 90))
            assertTrue(streams.any { it.type == result.configuration.streamType && it.size == result.configuration.size })
            assertGeometryCovers(view, result)
        }
    }

    @Test
    fun veryWideAndVeryTallViewsRemainResponsiveAndFinite() {
        val streams = listOf(
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1024, 768),
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080),
            stream(PreviewStreamType.CAMERA2_PRIVATE, 2560, 1080),
        )
        for (view in listOf(IntSize(3000, 700), IntSize(700, 3000))) {
            val result = supported(input(streams = streams, view = view, sensorOrientation = 90))
            assertTrue(result.geometry.scale.isFinite())
            assertTrue(result.geometry.scale > 0f)
            assertTrue(result.geometry.translatedX.isFinite())
            assertTrue(result.geometry.translatedY.isFinite())
            assertGeometryCovers(view, result)
        }
    }

    @Test
    fun deterministicSignatureChangesOnlyWithRelevantResolvedConfigurationState() {
        val streams = listOf(
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080),
            stream(PreviewStreamType.CAMERA2_PRIVATE, 3840, 2160),
        )
        val first = supported(input(streams = streams, view = IntSize(1920, 1080)))
        val reordered = supported(input(streams = streams.reversed(), view = IntSize(1920, 1080)))
        val highRes = supported(input(streams = streams, view = IntSize(1920, 1080), highResolution = true))
        assertEquals(first.configuration.signature, reordered.configuration.signature)
        assertFalse(first.configuration.signature == highRes.configuration.signature)
        assertTrue(first.configuration.signature.startsWith("pv1;"))
        assertFalse(first.configuration.signature.contains("@"))
    }

    @Test
    fun boundedPermutationMatrixIsOrderIndependentAndPreservesGeometryInvariants() {
        val candidates = listOf(
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1280, 720, 33_000_000L),
            stream(PreviewStreamType.CAMERA2_PRIVATE, 1920, 1080, 16_000_000L),
            stream(PreviewStreamType.CAMERA2_YUV_420_888, 1600, 1200, null),
        )
        val permutations = permutations(candidates)
        val views = listOf(IntSize(1080, 2400), IntSize(2400, 1080), IntSize(1000, 1000))
        val orientations = listOf(0, 90, 180, 270)
        val rotations = DisplayRotation.entries
        val facings = LensFacing.entries
        for (view in views) {
            for (orientation in orientations) {
                for (rotation in rotations) {
                    for (facing in facings) {
                        for (mirror in listOf(false, true)) {
                            val baseline = supported(
                                input(
                                    streams = candidates,
                                    view = view,
                                    sensorOrientation = orientation,
                                    displayRotation = rotation,
                                    facing = facing,
                                    mirror = mirror,
                                ),
                            )
                            for (permutation in permutations) {
                                val result = supported(
                                    input(
                                        streams = permutation,
                                        view = view,
                                        sensorOrientation = orientation,
                                        displayRotation = rotation,
                                        facing = facing,
                                        mirror = mirror,
                                    ),
                                )
                                assertEquals(baseline.configuration, result.configuration)
                                assertEquals(baseline.geometry, result.geometry)
                                assertTrue(
                                    candidates.any {
                                        it.type == result.configuration.streamType && it.size == result.configuration.size
                                    },
                                )
                                assertTrue(result.configuration.size.width > 0)
                                assertTrue(result.configuration.size.height > 0)
                                assertTrue(result.geometry.clockwiseRotationDegrees in listOf(0, 90, 180, 270))
                                assertTrue(result.geometry.scale.isFinite() && result.geometry.scale > 0f)
                                assertTrue(result.geometry.translatedX.isFinite())
                                assertTrue(result.geometry.translatedY.isFinite())
                                assertEquals(facing == LensFacing.FRONT && mirror, result.geometry.mirrorHorizontally)
                                assertGeometryCovers(view, result)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun candidateLimitFailsClosedBeforeSortingUnboundedInput() {
        val oversized = List(MAX_PREVIEW_POLICY_CANDIDATES + 1) { index ->
            stream(PreviewStreamType.CAMERA2_PRIVATE, 640 + index, 480)
        }
        assertUnsupported(input(streams = oversized), PreviewUnsupportedReason.CAPABILITY_LIMIT_EXCEEDED)
    }

    private fun assertGeometryCovers(view: IntSize, result: PreviewPolicyResult.Supported) {
        val rotation = result.geometry.clockwiseRotationDegrees
        val swap = rotation == 90 || rotation == 270
        val width = if (swap) result.configuration.size.height else result.configuration.size.width
        val height = if (swap) result.configuration.size.width else result.configuration.size.height
        val renderedWidth = width * result.geometry.scale
        val renderedHeight = height * result.geometry.scale
        assertTrue(renderedWidth + 0.01f >= view.width)
        assertTrue(renderedHeight + 0.01f >= view.height)
        assertTrue(
            kotlin.math.abs(renderedWidth - view.width) <= 0.05f ||
                kotlin.math.abs(renderedHeight - view.height) <= 0.05f,
        )
        assertEquals((view.width - renderedWidth) / 2f, result.geometry.translatedX, 0.05f)
        assertEquals((view.height - renderedHeight) / 2f, result.geometry.translatedY, 0.05f)
    }

    private fun supported(input: PreviewPolicyInput): PreviewPolicyResult.Supported {
        val result = PreviewStreamPolicy.resolve(input)
        assertTrue("Expected supported result, got $result", result is PreviewPolicyResult.Supported)
        return result as PreviewPolicyResult.Supported
    }

    private fun assertUnsupported(input: PreviewPolicyInput, reason: PreviewUnsupportedReason) {
        assertEquals(PreviewPolicyResult.Unsupported(reason), PreviewStreamPolicy.resolve(input))
    }

    private fun input(
        streams: List<CameraStreamCapability>,
        view: IntSize = IntSize(1920, 1080),
        sensorOrientation: Int = 0,
        displayRotation: DisplayRotation = DisplayRotation.ROTATION_0,
        facing: LensFacing = LensFacing.BACK,
        mirror: Boolean = false,
        requestedType: PreviewStreamType = PreviewStreamType.AUTO,
        highResolution: Boolean = false,
        fpsRequest: PreviewFpsRequest = PreviewFpsRequest(false, 30, 30),
        ranges: List<CameraFpsCapability> = listOf(CameraFpsCapability(30, 30), CameraFpsCapability(30, 60)),
    ) = PreviewPolicyInput(
        capabilities = CameraCapabilities(previewStreams = streams, fpsRanges = ranges),
        viewSize = view,
        sensorOrientationDegrees = sensorOrientation,
        displayRotation = displayRotation,
        lensFacing = facing,
        mirrorFrontPreview = mirror,
        requestedStreamType = requestedType,
        highResolutionViewfinder = highResolution,
        fpsRequest = fpsRequest,
    )

    private fun stream(
        type: PreviewStreamType,
        width: Int,
        height: Int,
        durationNs: Long? = null,
    ) = CameraStreamCapability(type, IntSize(width, height), durationNs)

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.size <= 1) return listOf(values)
        val result = ArrayList<List<T>>()
        for (index in values.indices) {
            val head = values[index]
            val rest = values.toMutableList().also { it.removeAt(index) }
            for (tail in permutations(rest)) result += listOf(head) + tail
        }
        return result
    }
}
