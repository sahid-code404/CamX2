package com.sahidcode404.camx.core.rawvideo.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSensorRawVideoIngestOwnershipContractTest {
    @Test
    fun `ingest queue never retains Camera2 Image ownership`() {
        val source = source("src/main/java/com/sahidcode404/camx/core/rawvideo/recording/AndroidSensorRawVideoIngest.kt")

        assertTrue(
            source.contains(
                "ArrayBlockingQueue<SensorRawVideoFrameBatch>(reservation.ingestQueueFrames)",
            ),
        )
        assertTrue(source.contains("assembler.assemble("))
        assertTrue(source.indexOf("assembler.assemble(") < source.indexOf("queue.offer("))
        assertFalse(source.contains("ArrayBlockingQueue<PairedRawVideoSample<Image, CaptureResult>>"))
        assertFalse(source.contains("queue.forEach { it.close() }"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Source file not found: $relative from ${System.getProperty("user.dir")}")
    }
}
