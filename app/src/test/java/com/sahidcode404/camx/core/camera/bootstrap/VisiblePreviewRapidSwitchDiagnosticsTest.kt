package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.LensSwitchDiagnostics
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.PreviewConfigurationAttemptKind
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePreviewRapidSwitchDiagnosticsTest {
    @Test
    fun `timings are monotonic counters match actual transactions and stale state is ignored`() {
        var nowNs = 2_000_000L
        val published = mutableListOf<LensSwitchDiagnostics>()
        val state = VisiblePreviewRapidSwitchState(
            clockNanos = { nowNs },
            sink = { published += it },
        )
        val a = selection("a", selectionGeneration = 1L, sessionGeneration = 1L)

        state.request(A, tapNs = 1_000_000L)
        nowNs = 3_000_000L
        state.markCleanupComplete()
        nowNs = 4_000_000L
        state.observe(CameraEngineState.Opening(a, a.sessionGeneration))
        nowNs = 5_000_000L
        state.observe(CameraEngineState.Opening(a, a.sessionGeneration))
        nowNs = 6_000_000L
        state.observe(CameraEngineState.ConfiguringPreview(a, PreviewConfigurationAttemptKind.REQUESTED))
        nowNs = 7_000_000L
        state.observe(CameraEngineState.Previewing(a, firstFrameVerified = false))
        nowNs = 8_000_000L
        state.observe(CameraEngineState.Previewing(a, firstFrameVerified = true))
        state.markRetry()
        state.markFallback()

        val completed = state.snapshot()
        val durations = listOfNotNull(
            completed.tapToAcceptedMs,
            completed.tapToCleanupCompleteMs,
            completed.tapToOpenRequestedMs,
            completed.tapToCameraOpenedMs,
            completed.tapToSessionConfiguredMs,
            completed.tapToFirstFrameMs,
            completed.tapToPreviewVerifiedMs,
        )
        assertEquals(durations.sorted(), durations)
        assertTrue(durations.all { it >= 0L })
        assertEquals(1L, completed.actualOpenCount)
        assertEquals(1L, completed.transientRetryCount)
        assertEquals(1L, completed.fallbackToLastVerifiedCount)

        nowNs = 10_000_000L
        state.request(B, tapNs = 9_000_000L)
        nowNs = 11_000_000L
        state.request(C, tapNs = 10_000_000L)
        val beforeStale = state.snapshot()

        nowNs = 12_000_000L
        state.observe(
            CameraEngineState.Opening(
                selection("b", selectionGeneration = 2L, sessionGeneration = 2L),
                SessionGeneration(2L),
            ),
        )
        assertEquals(beforeStale, state.snapshot())

        val c1 = selection("c", selectionGeneration = 3L, sessionGeneration = 3L)
        nowNs = 13_000_000L
        state.observe(CameraEngineState.Opening(c1, c1.sessionGeneration))
        nowNs = 14_000_000L
        state.observe(CameraEngineState.Opening(c1, c1.sessionGeneration))
        val c2 = selection("c", selectionGeneration = 3L, sessionGeneration = 4L)
        nowNs = 15_000_000L
        state.observe(CameraEngineState.Opening(c2, c2.sessionGeneration))

        val final = state.snapshot()
        assertEquals(2L, final.supersededIntentCount)
        assertEquals(3L, final.actualOpenCount)
        assertEquals(1L, final.transientRetryCount)
        assertEquals(1L, final.fallbackToLastVerifiedCount)
        assertTrue(published.size < 32)
    }

    @Test
    fun `clock regression never emits a negative duration`() {
        var nowNs = 5_000_000L
        val state = VisiblePreviewRapidSwitchState(
            clockNanos = { nowNs },
            sink = {},
        )
        state.request(A, tapNs = 10_000_000L)
        assertNull(state.snapshot().tapToAcceptedMs)

        nowNs = 4_000_000L
        state.markCleanupComplete()
        assertNull(state.snapshot().tapToCleanupCompleteMs)
    }

    private fun selection(
        suffix: String,
        selectionGeneration: Long,
        sessionGeneration: Long,
    ) = ActiveCameraSelection(
        canonicalLensFingerprint = CanonicalLensFingerprint("lens:$suffix"),
        profileFingerprint = CameraProfileFingerprint("profile:$suffix"),
        routeId = CameraRouteId("route:$suffix"),
        selectionGeneration = SelectionGeneration(selectionGeneration),
        sessionGeneration = SessionGeneration(sessionGeneration),
    )

    private companion object {
        val A = CanonicalLensFingerprint("lens:a")
        val B = CanonicalLensFingerprint("lens:b")
        val C = CanonicalLensFingerprint("lens:c")
    }
}
