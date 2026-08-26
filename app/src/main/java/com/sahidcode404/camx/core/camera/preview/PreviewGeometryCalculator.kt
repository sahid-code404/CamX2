package com.sahidcode404.camx.core.camera.preview

import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.model.PreviewGeometryInput
import kotlin.math.max

object PreviewGeometryCalculator {
    fun calculate(input: PreviewGeometryInput): PreviewGeometry {
        val rotation = rotationDegrees(
            input.sensorOrientationDegrees,
            input.displayRotation,
            input.lensFacing,
        )
        val swapAxes = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapAxes) input.streamSize.height else input.streamSize.width
        val rotatedHeight = if (swapAxes) input.streamSize.width else input.streamSize.height
        val scale = max(
            input.viewSize.width.toDouble() / rotatedWidth.toDouble(),
            input.viewSize.height.toDouble() / rotatedHeight.toDouble(),
        )
        require(scale.isFinite() && scale > 0.0) { "Preview scale must be finite and positive" }
        val renderedWidth = rotatedWidth.toDouble() * scale
        val renderedHeight = rotatedHeight.toDouble() * scale
        return PreviewGeometry(
            clockwiseRotationDegrees = rotation,
            scale = scale.toFloat(),
            translatedX = ((input.viewSize.width - renderedWidth) / 2.0).toFloat(),
            translatedY = ((input.viewSize.height - renderedHeight) / 2.0).toFloat(),
            mirrorHorizontally = input.lensFacing == LensFacing.FRONT && input.mirrorFrontPreview,
        )
    }

    internal fun rotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotation: DisplayRotation,
        lensFacing: LensFacing,
    ): Int {
        require(sensorOrientationDegrees in 0..270 && sensorOrientationDegrees % 90 == 0) {
            "Sensor orientation must be one of 0, 90, 180, or 270 degrees"
        }
        return when (lensFacing) {
            LensFacing.FRONT -> Math.floorMod(
                sensorOrientationDegrees + displayRotation.degrees,
                360,
            )
            LensFacing.BACK,
            LensFacing.EXTERNAL,
            LensFacing.UNKNOWN,
            -> Math.floorMod(
                sensorOrientationDegrees - displayRotation.degrees,
                360,
            )
        }
    }
}
