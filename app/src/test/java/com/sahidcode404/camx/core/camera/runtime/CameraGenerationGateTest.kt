package com.sahidcode404.camx.core.camera.runtime

import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGenerationGateTest {
    @Test
    fun staleSessionAndCaptureAreRejected() {
        val gate = CameraGenerationGate()
        val selection = gate.advanceSelection()
        val token = gate.beginCapture()

        assertTrue(gate.acceptsCapture(selection.selection, selection.session, token))
        gate.advanceSession()

        assertFalse(gate.acceptsCapture(selection.selection, selection.session, token))
        assertNull(gate.snapshot().capture)
    }

    @Test
    fun callbackNeedsBothGenerationTypes() {
        val gate = CameraGenerationGate()
        val current = gate.advanceSelection()

        assertFalse(gate.accepts(SelectionGeneration(0), current.session))
        assertFalse(gate.accepts(current.selection, SessionGeneration(0)))
        assertTrue(gate.accepts(current.selection, current.session))
    }
}
