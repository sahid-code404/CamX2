package com.sahidcode404.camx.core.camera.session

import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraOutputPlanTest {
    @Test
    fun previewPlanCannotContainPermanentRawOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraOutputBinding(CameraOutputRole.RAW, CameraRequestLifetime.REPEATING)
        }
        val plan = CameraSessionOutputPlan.previewOnly(PreviewSurfaceIdentity(1L))
        assertNull(plan.captureToken)
        assertEquals(listOf(CameraOutputRole.PREVIEW), plan.bindings.map { it.role })
    }

    @Test
    fun temporaryRawPlanBindsRawToOneCaptureTokenAndOneShotRequest() {
        val token = CaptureToken(7L)
        val plan = CameraSessionOutputPlan.temporaryRaw(PreviewSurfaceIdentity(1L), token)
        assertEquals(token, plan.captureToken)
        assertEquals(
            CameraRequestLifetime.ONE_SHOT,
            plan.bindings.single { it.role == CameraOutputRole.RAW }.lifetime,
        )
    }

    @Test
    fun temporaryRawBurstPlanIsExplicitlyBoundedAndNeverRepeating() {
        val token = CaptureToken(9L)
        val plan = CameraSessionOutputPlan.temporaryRawBurst(PreviewSurfaceIdentity(3L), token)
        assertEquals(token, plan.captureToken)
        assertEquals(
            CameraRequestLifetime.BOUNDED_BURST,
            plan.bindings.single { it.role == CameraOutputRole.RAW }.lifetime,
        )
        assertEquals(
            CameraRequestLifetime.REPEATING,
            plan.bindings.single { it.role == CameraOutputRole.PREVIEW }.lifetime,
        )
    }

    @Test
    fun previewOutputCannotMasqueradeAsBurstLifetime() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraOutputBinding(CameraOutputRole.PREVIEW, CameraRequestLifetime.BOUNDED_BURST)
        }
    }
}
