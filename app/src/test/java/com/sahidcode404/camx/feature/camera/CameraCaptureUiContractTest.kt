package com.sahidcode404.camx.feature.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureUiContractTest {
    @Test
    fun `photo shutter invokes production capture callback`() {
        val source = source("src/main/java/com/sahidcode404/camx/feature/camera/CameraScreen.kt")
        assertTrue(source.contains("CameraCaptureMode.PHOTO -> onCapturePhoto()"))
        assertTrue(source.contains("photoCaptureEnabled"))
        assertTrue(source.contains("capture_raw_photo_content_description"))
        assertFalse(
            Regex("enabled\\s*=\\s*false,[\\s\\S]{0,250}onClick\\s*=\\s*\\{\\s*\\}")
                .containsMatchIn(source),
        )
    }

    @Test
    fun `video control is visible but truthfully gated until M10`() {
        val screen = source("src/main/java/com/sahidcode404/camx/feature/camera/CameraScreen.kt")
        val activity = source("src/main/java/com/sahidcode404/camx/MainActivity.kt")
        assertTrue(screen.contains("CameraCaptureMode.VIDEO"))
        assertTrue(screen.contains("raw_video_m10_unavailable"))
        assertTrue(screen.contains("CameraCaptureMode.VIDEO -> onToggleVideoRecording()"))
        assertTrue(activity.contains("videoCaptureEnabled = false"))
    }

    @Test
    fun `activity calls graph capture rather than owning Camera2`() {
        val activity = source("src/main/java/com/sahidcode404/camx/MainActivity.kt")
        assertTrue(activity.contains("visiblePreviewGraph.capturePhoto(currentDisplayRotation())"))
        assertFalse(activity.contains("android.hardware.camera2.CameraDevice"))
        assertFalse(activity.contains("android.hardware.camera2.CameraCaptureSession"))
        assertFalse(activity.contains("openCamera("))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Source file not found: $relative from ${System.getProperty("user.dir")}")
    }
}
