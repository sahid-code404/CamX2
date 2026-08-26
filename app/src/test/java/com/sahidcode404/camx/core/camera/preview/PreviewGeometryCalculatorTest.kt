package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewGeometryInput
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreviewGeometryCalculatorTest {
    @Test
    fun rearPortraitStreamIsRotatedAndCenterCropped() {
        val result = PreviewGeometryCalculator.calculate(
            PreviewGeometryInput(
                viewSize = IntSize(1080, 2400),
                streamSize = IntSize(1920, 1080),
                sensorOrientationDegrees = 90,
                displayRotation = DisplayRotation.ROTATION_0,
                lensFacing = LensFacing.BACK,
                mirrorFrontPreview = true,
            ),
        )
        assertEquals(90, result.clockwiseRotationDegrees)
        assertEquals(1.25f, result.scale, 0.0001f)
        assertEquals(-135f, result.translatedX, 0.0001f)
        assertEquals(0f, result.translatedY, 0.0001f)
        assertFalse(result.mirrorHorizontally)
    }

    @Test
    fun everyDisplayRotationBackAndFrontMirrorCombinationIsOrthogonalAndExplicit() {
        for (displayRotation in DisplayRotation.entries) {
            for (facing in listOf(LensFacing.BACK, LensFacing.FRONT)) {
                for (mirror in listOf(false, true)) {
                    val result = PreviewGeometryCalculator.calculate(
                        PreviewGeometryInput(
                            viewSize = IntSize(1200, 2000),
                            streamSize = IntSize(1920, 1080),
                            sensorOrientationDegrees = 90,
                            displayRotation = displayRotation,
                            lensFacing = facing,
                            mirrorFrontPreview = mirror,
                        ),
                    )
                    val expectedRotation = if (facing == LensFacing.FRONT) {
                        Math.floorMod(90 + displayRotation.degrees, 360)
                    } else {
                        Math.floorMod(90 - displayRotation.degrees, 360)
                    }
                    assertEquals(expectedRotation, result.clockwiseRotationDegrees)
                    assertEquals(facing == LensFacing.FRONT && mirror, result.mirrorHorizontally)
                    assertGeometry(
                        IntSize(1200, 2000),
                        IntSize(1920, 1080),
                        result.clockwiseRotationDegrees,
                        result.scale,
                        result.translatedX,
                        result.translatedY,
                    )
                }
            }
        }
    }

    @Test
    fun externalAndUnknownNeverInheritFrontMirrorBehavior() {
        for (facing in listOf(LensFacing.EXTERNAL, LensFacing.UNKNOWN)) {
            val result = PreviewGeometryCalculator.calculate(
                PreviewGeometryInput(
                    viewSize = IntSize(1000, 1000),
                    streamSize = IntSize(1600, 900),
                    sensorOrientationDegrees = 270,
                    displayRotation = DisplayRotation.ROTATION_90,
                    lensFacing = facing,
                    mirrorFrontPreview = true,
                ),
            )
            assertEquals(180, result.clockwiseRotationDegrees)
            assertFalse(result.mirrorHorizontally)
        }
    }

    @Test
    fun ninetyAndTwoSeventySwapStreamAxesBeforeScale() {
        for (sensorOrientation in listOf(90, 270)) {
            val result = PreviewGeometryCalculator.calculate(
                PreviewGeometryInput(
                    viewSize = IntSize(1000, 1600),
                    streamSize = IntSize(1600, 1000),
                    sensorOrientationDegrees = sensorOrientation,
                    displayRotation = DisplayRotation.ROTATION_0,
                    lensFacing = LensFacing.BACK,
                    mirrorFrontPreview = false,
                ),
            )
            assertEquals(1f, result.scale, 0.0001f)
            assertEquals(0f, result.translatedX, 0.0001f)
            assertEquals(0f, result.translatedY, 0.0001f)
        }
    }

    @Test
    fun centerCropCoversPortraitLandscapeSquareWideAndTallViewsWithoutDistortion() {
        val views = listOf(
            IntSize(1080, 2400),
            IntSize(2400, 1080),
            IntSize(1200, 1200),
            IntSize(3200, 700),
            IntSize(700, 3200),
        )
        val streams = listOf(IntSize(1920, 1080), IntSize(1440, 1080), IntSize(2560, 1080))
        for (view in views) {
            for (stream in streams) {
                val result = PreviewGeometryCalculator.calculate(
                    PreviewGeometryInput(
                        viewSize = view,
                        streamSize = stream,
                        sensorOrientationDegrees = 90,
                        displayRotation = DisplayRotation.ROTATION_0,
                        lensFacing = LensFacing.BACK,
                        mirrorFrontPreview = false,
                    ),
                )
                assertGeometry(
                    view,
                    stream,
                    result.clockwiseRotationDegrees,
                    result.scale,
                    result.translatedX,
                    result.translatedY,
                )
            }
        }
    }

    @Test
    fun invalidSensorOrientationIsRejectedAtPureGeometryBoundary() {
        for (invalid in listOf(-90, 1, 45, 89, 91, 360)) {
            try {
                PreviewGeometryCalculator.calculate(
                    PreviewGeometryInput(
                        viewSize = IntSize(1000, 1000),
                        streamSize = IntSize(1000, 1000),
                        sensorOrientationDegrees = invalid,
                        displayRotation = DisplayRotation.ROTATION_0,
                        lensFacing = LensFacing.BACK,
                        mirrorFrontPreview = false,
                    ),
                )
                fail("Expected invalid sensor orientation $invalid to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected pure input rejection.
            }
        }
    }

    @Test
    fun policyReturnsTypedUnsupportedForInvalidSensorOrientation() {
        val result = PreviewStreamPolicy.resolve(
            PreviewPolicyInput(
                capabilities = com.sahidcode404.camx.core.camera.model.CameraCapabilities(
                    previewStreams = listOf(
                        com.sahidcode404.camx.core.camera.model.CameraStreamCapability(
                            com.sahidcode404.camx.core.camera.model.PreviewStreamType.CAMERA2_PRIVATE,
                            IntSize(1000, 1000),
                            null,
                        ),
                    ),
                ),
                viewSize = IntSize(1000, 1000),
                sensorOrientationDegrees = 45,
                displayRotation = DisplayRotation.ROTATION_0,
                lensFacing = LensFacing.BACK,
                mirrorFrontPreview = false,
                requestedStreamType = com.sahidcode404.camx.core.camera.model.PreviewStreamType.AUTO,
                highResolutionViewfinder = false,
                fpsRequest = com.sahidcode404.camx.core.camera.model.PreviewFpsRequest(false, 30, 30),
            ),
        )
        assertEquals(
            PreviewPolicyResult.Unsupported(PreviewUnsupportedReason.INVALID_SENSOR_ORIENTATION),
            result,
        )
    }

    private fun assertGeometry(
        view: IntSize,
        stream: IntSize,
        rotation: Int,
        scale: Float,
        translatedX: Float,
        translatedY: Float,
    ) {
        val swap = rotation == 90 || rotation == 270
        val rotatedWidth = if (swap) stream.height else stream.width
        val rotatedHeight = if (swap) stream.width else stream.height
        val renderedWidth = rotatedWidth * scale
        val renderedHeight = rotatedHeight * scale
        assertTrue(scale.isFinite() && scale > 0f)
        assertTrue(translatedX.isFinite())
        assertTrue(translatedY.isFinite())
        assertTrue(renderedWidth + 0.01f >= view.width)
        assertTrue(renderedHeight + 0.01f >= view.height)
        assertTrue(abs(renderedWidth - view.width) <= 0.05f || abs(renderedHeight - view.height) <= 0.05f)
        assertEquals((view.width - renderedWidth) / 2f, translatedX, 0.05f)
        assertEquals((view.height - renderedHeight) / 2f, translatedY, 0.05f)
        val sourceAspect = rotatedWidth.toDouble() / rotatedHeight.toDouble()
        val renderedAspect = renderedWidth.toDouble() / renderedHeight.toDouble()
        assertTrue(abs(sourceAspect - renderedAspect) < 0.00001)
    }
}
