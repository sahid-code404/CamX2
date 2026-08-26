package com.sahidcode404.camx.feature.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraScreenVisualContractTest {
    @Test
    fun `normal camera surface does not render transient lifecycle text`() {
        val source = cameraScreenSource()
        listOf(
            "Opening camera...",
            "Starting camera...",
            "Waiting for surface...",
            "Waiting for first frame",
            "Camera error",
        ).forEach { forbidden ->
            assertFalse("CameraScreen visually regressed transient text: $forbidden", source.contains(forbidden))
        }

        assertFalse(source.contains("visiblePreviewStatusText("))
        assertFalse(source.contains("cameraStatusText("))
    }

    @Test
    fun `lens button visually renders photographic identity only`() {
        val source = cameraScreenSource()
        val lensButton = source.substringAfter("private fun LensTestButton(")
            .substringBefore("/** Lifecycle state remains available")

        assertTrue(lensButton.contains("text = item.primaryLabel"))
        assertTrue(lensButton.contains("text = secondary"))
        assertTrue(lensButton.contains("contentDescription = description"))
        assertTrue(lensButton.contains("val statusLabel = lensStatusText(item.status)"))

        assertFalse(lensButton.contains("Text(statusLabel"))
        assertFalse(lensButton.contains("text = statusLabel"))
        assertFalse(lensButton.contains("Text(lensStatusText"))
        assertFalse(lensButton.contains("text = lensStatusText"))
        listOf("Advertised", "Available", "Opening", "Verified", "Failed").forEach { lifecycle ->
            assertFalse("Lens lifecycle text became visual: $lifecycle", lensButton.contains("Text(\"$lifecycle\""))
        }
    }

    private fun cameraScreenSource(): String {
        val relative = "src/main/java/com/sahidcode404/camx/feature/camera/CameraScreen.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("CameraScreen.kt not found from ${System.getProperty("user.dir")}")
        return sourceFile.readText()
    }
}
