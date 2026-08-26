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
    fun `video control invokes real M10 start and stop bridge`() {
        val screen = source("src/main/java/com/sahidcode404/camx/feature/camera/CameraScreen.kt")
        val activity = source("src/main/java/com/sahidcode404/camx/MainActivity.kt")
        val graph = source("src/main/java/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewGraph.kt")
        assertTrue(screen.contains("CameraCaptureMode.VIDEO -> onToggleVideoRecording()"))
        assertTrue(screen.contains("stop_raw_video_content_description"))
        assertTrue(screen.contains("RawRecordingTimer("))
        assertTrue(activity.contains("visiblePreviewGraph.startRawVideo(currentDisplayRotation())"))
        assertTrue(activity.contains("visiblePreviewGraph.stopRawVideo()"))
        assertTrue(activity.contains("videoCaptureEnabled = videoUiEnabled"))
        assertFalse(activity.contains("videoCaptureEnabled = false"))
        assertFalse(activity.contains("onToggleVideoRecording = {}"))
        assertTrue(graph.contains("controller.startRawVideo(displayRotation, rawVideoStore)"))
        assertTrue(graph.contains("controller.stopRawVideo()"))
    }

    @Test
    fun `video failure reason is not hidden behind generic cancellation`() {
        val activity = source("src/main/java/com/sahidcode404/camx/MainActivity.kt")
        val ingest = source("src/main/java/com/sahidcode404/camx/core/rawvideo/recording/AndroidSensorRawVideoIngest.kt")
        assertTrue(activity.contains("LaunchedEffect(rawVideoStatus)"))
        assertTrue(activity.contains("visiblePreviewGraph.rawVideoStatus.value"))
        assertTrue(activity.contains("is SensorRawVideoStatus.Failed"))
        assertTrue(activity.contains("TimeoutCancellationException"))
        assertTrue(ingest.contains("INGEST_BACKPRESSURE_TIMEOUT_MILLIS"))
        assertTrue(ingest.contains("queue.offer("))
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
