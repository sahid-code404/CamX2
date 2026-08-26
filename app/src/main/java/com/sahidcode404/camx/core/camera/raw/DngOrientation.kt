package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.LensFacing

object DngOrientation {
    // Values are TIFF/Exif orientation constants. RAW pixels are never rotated.
    fun tiffOrientation(
        sensorOrientationDegrees: Int,
        lensFacing: LensFacing,
        displayRotationAtShutter: DisplayRotation,
    ): Int {
        require(Math.floorMod(sensorOrientationDegrees, 90) == 0) {
            "Sensor orientation must be orthogonal"
        }
        val clockwise = when (lensFacing) {
            LensFacing.FRONT -> Math.floorMod(
                sensorOrientationDegrees + displayRotationAtShutter.degrees,
                360,
            )
            LensFacing.BACK, LensFacing.EXTERNAL, LensFacing.UNKNOWN -> Math.floorMod(
                sensorOrientationDegrees - displayRotationAtShutter.degrees,
                360,
            )
        }
        return when (clockwise) {
            0 -> 1
            90 -> 6
            180 -> 3
            270 -> 8
            else -> error("Normalized orientation must be orthogonal")
        }
    }
}
