package com.sahidcode404.camx.feature.camera

import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewProblem
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewRenderSpec
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewStartupFailure
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewUiState
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceVisibilityPolicyTest {
    private val render = VisiblePreviewRenderSpec(
        bufferSize = IntSize(1920, 1080),
        geometry = PreviewGeometry(
            clockwiseRotationDegrees = 90,
            scale = 1f,
            translatedX = 0f,
            translatedY = 0f,
            mirrorHorizontally = false,
        ),
    )

    @Test
    fun verifiedOutgoingPresentationCanRemainVisibleDuringReleaseAndPreparation() {
        assertTrue(shouldRevealPreviewSurface(VisiblePreviewUiState.Starting, render))
        assertTrue(shouldRevealPreviewSurface(VisiblePreviewUiState.WaitingForSurface, render))
    }

    @Test
    fun targetOpeningAndUnverifiedFirstFrameStayCovered() {
        assertFalse(shouldRevealPreviewSurface(VisiblePreviewUiState.Opening(render), render))
        assertFalse(
            shouldRevealPreviewSurface(
                VisiblePreviewUiState.Previewing(render, firstFrameVerified = false),
                render,
            ),
        )
    }

    @Test
    fun exactVerifiedFirstFrameRevealsTargetPresentation() {
        assertTrue(
            shouldRevealPreviewSurface(
                VisiblePreviewUiState.Previewing(render, firstFrameVerified = true),
                render,
            ),
        )
    }

    @Test
    fun errorsAndRealPresentationTeardownRemainCovered() {
        val error = VisiblePreviewUiState.Error(
            VisiblePreviewProblem.Startup(VisiblePreviewStartupFailure.PREVIEW_START_FAILED),
        )
        assertFalse(shouldRevealPreviewSurface(error, render))
        assertFalse(shouldRevealPreviewSurface(VisiblePreviewUiState.Starting, null))
        assertFalse(shouldRevealPreviewSurface(VisiblePreviewUiState.WaitingForPermission, null))
    }
}
