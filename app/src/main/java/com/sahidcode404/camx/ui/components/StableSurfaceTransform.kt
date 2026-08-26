package com.sahidcode404.camx.ui.components

import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewGeometry

data class StableSurfaceTransform(
    val layoutSize: IntSize,
    val clockwiseRotationDegrees: Int,
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

/** Converts CAMX-104 geometry into Android View transform properties without re-solving geometry. */
fun calculateStableSurfaceTransform(
    streamSize: IntSize,
    geometry: PreviewGeometry,
): StableSurfaceTransform {
    require(geometry.clockwiseRotationDegrees in setOf(0, 90, 180, 270)) {
        "Preview rotation must be orthogonal"
    }
    require(geometry.scale.isFinite() && geometry.scale > 0f) {
        "Preview scale must be finite and positive"
    }
    require(geometry.translatedX.isFinite() && geometry.translatedY.isFinite()) {
        "Preview translations must be finite"
    }
    val swapAxes = geometry.clockwiseRotationDegrees == 90 ||
        geometry.clockwiseRotationDegrees == 270
    val rotatedWidth = if (swapAxes) streamSize.height.toFloat() else streamSize.width.toFloat()
    val rotatedHeight = if (swapAxes) streamSize.width.toFloat() else streamSize.height.toFloat()
    val renderedWidth = rotatedWidth * geometry.scale
    val renderedHeight = rotatedHeight * geometry.scale

    // View rotation/scale occurs around the unrotated child center. Translate that transformed
    // bounding box so its top-left is exactly CAMX-104's center-crop translation.
    val translationX = geometry.translatedX + (renderedWidth - streamSize.width.toFloat()) / 2f
    val translationY = geometry.translatedY + (renderedHeight - streamSize.height.toFloat()) / 2f

    // CAMX-104's mirror flag is horizontal in the final displayed coordinate system. Conjugating
    // that reflection through a quarter-turn rotation swaps the local reflection axis.
    val mirrorLocalX = geometry.mirrorHorizontally && !swapAxes
    val mirrorLocalY = geometry.mirrorHorizontally && swapAxes
    return StableSurfaceTransform(
        layoutSize = streamSize,
        clockwiseRotationDegrees = geometry.clockwiseRotationDegrees,
        scaleX = if (mirrorLocalX) -geometry.scale else geometry.scale,
        scaleY = if (mirrorLocalY) -geometry.scale else geometry.scale,
        translationX = translationX,
        translationY = translationY,
    )
}
