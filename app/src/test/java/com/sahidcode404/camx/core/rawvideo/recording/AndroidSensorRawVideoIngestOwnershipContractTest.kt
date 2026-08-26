package com.sahidcode404.camx.core.rawvideo.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSensorRawVideoIngestOwnershipContractTest {
    @Test
    fun ingestQueueNeverRetainsCamera2ImageOwnership() {
        val source = source("src/main/java/com/sahidcode404/camx/core/rawvideo/recording/AndroidSensorRawVideoIngest.kt")

        assertTrue(
            source.contains(
                "ArrayBlockingQueue<DetachedRawVideoPair>(reservation.ingestQueueFrames)",
            ),
        )
        assertTrue(source.contains("DetachedRawVideoPair.from(pair)"))
        assertTrue(source.contains("assembler.assemble("))
        assertTrue(source.indexOf("queue.offer(") < source.indexOf("assembler.assemble("))
        assertFalse(source.contains("ArrayBlockingQueue<PairedRawVideoSample<Image, CaptureResult>>"))
        assertTrue(source.contains("runCatching { pair.close() }"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Source file not found: " + relative + " from " + System.getProperty("user.dir"))
    }
}
