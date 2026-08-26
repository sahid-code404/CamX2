package com.sahidcode404.camx.core.camera.trace

import com.sahidcode404.camx.core.camera.model.CameraStartupMilestone
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedCameraStartupTraceTest {
    @Test
    fun ringRetainsNewestEventsInOrder() {
        val trace = BoundedCameraStartupTrace(capacity = 2)
        trace.mark(CameraStartupMilestone.PROCESS_START, 10L)
        trace.mark(CameraStartupMilestone.ACTIVITY_CREATE, 20L)
        trace.mark(CameraStartupMilestone.SURFACE_READY, 30L)
        assertEquals(
            listOf(CameraStartupMilestone.ACTIVITY_CREATE, CameraStartupMilestone.SURFACE_READY),
            trace.snapshot().events.map { it.milestone },
        )
    }
}
