package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.LensFacing
import org.junit.Assert.assertEquals
import org.junit.Test

class DngOrientationTest {
    @Test
    fun rearOrientationUsesShutterRotationSnapshot() {
        assertEquals(6, DngOrientation.tiffOrientation(90, LensFacing.BACK, DisplayRotation.ROTATION_0))
        assertEquals(1, DngOrientation.tiffOrientation(90, LensFacing.BACK, DisplayRotation.ROTATION_90))
    }

    @Test
    fun frontOrientationUsesFacingSpecificMath() {
        assertEquals(3, DngOrientation.tiffOrientation(90, LensFacing.FRONT, DisplayRotation.ROTATION_90))
    }
}
