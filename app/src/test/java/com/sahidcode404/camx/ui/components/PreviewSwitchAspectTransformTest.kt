package com.sahidcode404.camx.ui.components

import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewGeometryInput
import com.sahidcode404.camx.core.camera.preview.PreviewGeometryCalculator
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSwitchAspectTransformTest {
    private val viewport = IntSize(1080, 1920)

    @Test
    fun fourThreeToSixteenNineAndBackRemainUniformCenterCrop() {
        val fourThree = transform(IntSize(1440, 1080), orientation = 90, facing = LensFacing.BACK)
        val sixteenNine = transform(IntSize(1920, 1080), orientation = 90, facing = LensFacing.BACK)

        assertUniform(fourThree)
        assertUniform(sixteenNine)
        assertEquals(90, fourThree.clockwiseRotationDegrees)
        assertEquals(90, sixteenNine.clockwiseRotationDegrees)
        assertTrue(abs(fourThree.scaleX) > 0f)
        assertTrue(abs(sixteenNine.scaleX) > 0f)
    }

    @Test
    fun substantiallyDifferentSupportedBuffersNeverUseIndependentAxisScaling() {
        listOf(
            IntSize(1920, 1080),
            IntSize(1440, 1080),
            IntSize(1280, 720),
            IntSize(640, 480),
        ).forEach { stream ->
            assertUniform(transform(stream, orientation = 90, facing = LensFacing.BACK))
        }
    }

    @Test
    fun ninetyToTwoSeventyAndRearFrontTargetsKeepUniformScaleAndCorrectMirrorAxis() {
        val rear90 = transform(IntSize(1920, 1080), orientation = 90, facing = LensFacing.BACK)
        val rear270 = transform(IntSize(1280, 720), orientation = 270, facing = LensFacing.BACK)
        val front270 = transform(IntSize(1440, 1080), orientation = 270, facing = LensFacing.FRONT)

        assertEquals(90, rear90.clockwiseRotationDegrees)
        assertEquals(270, rear270.clockwiseRotationDegrees)
        assertEquals(270, front270.clockwiseRotationDegrees)
        assertUniform(rear90)
        assertUniform(rear270)
        assertUniform(front270)
        assertTrue(front270.scaleX * front270.scaleY < 0f)
        assertTrue(rear270.scaleX * rear270.scaleY > 0f)
    }

    private fun transform(
        stream: IntSize,
        orientation: Int,
        facing: LensFacing,
    ): StableSurfaceTransform {
        val geometry = PreviewGeometryCalculator.calculate(
            PreviewGeometryInput(
                viewSize = viewport,
                streamSize = stream,
                sensorOrientationDegrees = orientation,
                displayRotation = DisplayRotation.ROTATION_0,
                lensFacing = facing,
                mirrorFrontPreview = true,
            ),
        )
        return calculateStableSurfaceTransform(stream, geometry)
    }

    private fun assertUniform(transform: StableSurfaceTransform) {
        assertTrue(transform.scaleX.isFinite())
        assertTrue(transform.scaleY.isFinite())
        assertEquals(abs(transform.scaleX), abs(transform.scaleY), 0f)
    }
}
