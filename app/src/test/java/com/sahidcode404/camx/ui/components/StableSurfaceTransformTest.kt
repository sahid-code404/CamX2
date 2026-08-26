package com.sahidcode404.camx.ui.components

import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSurfaceTransformTest {
    @Test
    fun zeroRotationAppliesOneUniformScaleAndExactCropTranslation() {
        val stream = IntSize(640, 480)
        val geometry = geometry(rotation = 0, scale = 2f, x = -100f, y = -50f)

        val transform = calculateStableSurfaceTransform(stream, geometry)

        assertEquals(2f, transform.scaleX, 0f)
        assertEquals(2f, transform.scaleY, 0f)
        assertBoundingBoxStartsAt(stream, transform, -100f, -50f)
    }

    @Test
    fun ninetyDegreeRotationUsesSwappedRenderedAxesWithoutStretching() {
        val stream = IntSize(640, 480)
        val geometry = geometry(rotation = 90, scale = 2f, x = -25f, y = -140f)

        val transform = calculateStableSurfaceTransform(stream, geometry)

        assertEquals(90, transform.clockwiseRotationDegrees)
        assertEquals(abs(transform.scaleX), abs(transform.scaleY), 0f)
        assertBoundingBoxStartsAt(stream, transform, -25f, -140f)
    }

    @Test
    fun frontMirrorUsesLocalXAxisAtZeroOrOneEightyDegrees() {
        for (rotation in listOf(0, 180)) {
            val mirrored = calculateStableSurfaceTransform(
                IntSize(800, 600),
                geometry(rotation = rotation, scale = 1.5f, x = 0f, y = -30f, mirror = true),
            )
            assertEquals(-1.5f, mirrored.scaleX, 0f)
            assertEquals(1.5f, mirrored.scaleY, 0f)
        }
    }

    @Test
    fun frontMirrorUsesLocalYAxisAfterQuarterTurnToRemainScreenHorizontal() {
        for (rotation in listOf(90, 270)) {
            val normal = calculateStableSurfaceTransform(
                IntSize(800, 600),
                geometry(rotation = rotation, scale = 1.5f, x = 0f, y = -30f, mirror = false),
            )
            val mirrored = calculateStableSurfaceTransform(
                IntSize(800, 600),
                geometry(rotation = rotation, scale = 1.5f, x = 0f, y = -30f, mirror = true),
            )

            assertEquals(normal.scaleX, mirrored.scaleX, 0f)
            assertEquals(-normal.scaleY, mirrored.scaleY, 0f)
            assertEquals(normal.translationX, mirrored.translationX, 0f)
            assertEquals(normal.translationY, mirrored.translationY, 0f)
        }
    }

    @Test
    fun allOrthogonalRotationsKeepFiniteUniformTransformAndExactCropOrigin() {
        val stream = IntSize(1440, 1080)
        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = geometry(rotation, scale = 1.25f, x = -120.5f, y = -18.25f)
            val transform = calculateStableSurfaceTransform(stream, geometry)

            assertTrue(transform.scaleX.isFinite())
            assertTrue(transform.scaleY.isFinite())
            assertTrue(transform.translationX.isFinite())
            assertTrue(transform.translationY.isFinite())
            assertEquals(abs(transform.scaleX), abs(transform.scaleY), 0f)
            assertBoundingBoxStartsAt(stream, transform, geometry.translatedX, geometry.translatedY)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonOrthogonalRotationIsRejectedAtUiBoundary() {
        calculateStableSurfaceTransform(
            IntSize(640, 480),
            geometry(rotation = 45, scale = 1f, x = 0f, y = 0f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteScaleIsRejectedAtUiBoundary() {
        calculateStableSurfaceTransform(
            IntSize(640, 480),
            geometry(rotation = 0, scale = Float.POSITIVE_INFINITY, x = 0f, y = 0f),
        )
    }

    private fun assertBoundingBoxStartsAt(
        stream: IntSize,
        transform: StableSurfaceTransform,
        expectedX: Float,
        expectedY: Float,
    ) {
        val swapped = transform.clockwiseRotationDegrees == 90 || transform.clockwiseRotationDegrees == 270
        val rotatedWidth = if (swapped) stream.height.toFloat() else stream.width.toFloat()
        val rotatedHeight = if (swapped) stream.width.toFloat() else stream.height.toFloat()
        val renderedWidth = rotatedWidth * abs(transform.scaleX)
        val renderedHeight = rotatedHeight * abs(transform.scaleY)
        val actualX = stream.width / 2f - renderedWidth / 2f + transform.translationX
        val actualY = stream.height / 2f - renderedHeight / 2f + transform.translationY
        assertEquals(expectedX, actualX, 0.001f)
        assertEquals(expectedY, actualY, 0.001f)
    }

    private fun geometry(
        rotation: Int,
        scale: Float,
        x: Float,
        y: Float,
        mirror: Boolean = false,
    ) = PreviewGeometry(
        clockwiseRotationDegrees = rotation,
        scale = scale,
        translatedX = x,
        translatedY = y,
        mirrorHorizontally = mirror,
    )
}
