package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBurstCaptureReportTest {
    @Test
    fun exactEightFrameEvidenceIsSuccessful() {
        val report = completeReport()

        assertTrue(report.success)
    }

    @Test
    fun partialPairsCanNeverBeSuccessful() {
        val report = completeReport().copy(
            imagesReceived = 7,
            resultsReceived = 6,
            exactPairsCreated = 5,
            framesCopied = 0,
            framesAccepted = 0,
            unmatchedImages = 2,
            unmatchedResults = 1,
            timedOut = true,
        )

        assertFalse(report.success)
    }

    @Test
    fun duplicateOrdinalOrOutstandingImageRejectsOtherwiseCompleteEvidence() {
        assertFalse(completeReport().copy(duplicateOrdinals = 1).success)
        assertFalse(completeReport().copy(imageObjectsStillOwned = 1).success)
    }

    @Test
    fun preflightIsRequiredButNeverCountsAsBurstMembership() {
        val withoutPreflight = completeReport().copy(preflight = null, preflightFrames = 0)

        assertFalse(withoutPreflight.success)
        assertTrue(completeReport().success)
    }

    private fun completeReport() = RawBurstCaptureReport(
        identity = RawBurstCaptureIdentity(
            captureToken = CaptureToken(2L),
            selectionGeneration = SelectionGeneration(3L),
            sessionGeneration = SessionGeneration(4L),
            canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
            cameraProfileFingerprint = CameraProfileFingerprint("profile"),
            routeId = CameraRouteId("route"),
            rawSize = IntSize(4000, 3000),
            displayRotationAtShutter = DisplayRotation.ROTATION_0,
        ),
        preflight = RawSourceLayoutCertification(
            captureToken = CaptureToken(1L),
            selectionGeneration = SelectionGeneration(3L),
            sessionGeneration = SessionGeneration(3L),
            canonicalLensFingerprint = CanonicalLensFingerprint("lens"),
            cameraProfileFingerprint = CameraProfileFingerprint("profile"),
            routeId = CameraRouteId("route"),
            rawSize = IntSize(4000, 3000),
            imageFormat = 32,
            rowStrideBytes = 8_192,
            pixelStrideBytes = 2,
            sourceRequiredBytes = 24_575_808L,
        ),
        requestedFrames = 8,
        preflightFrames = 1,
        captureRequestsSubmitted = 8,
        imagesReceived = 8,
        resultsReceived = 8,
        exactPairsCreated = 8,
        framesCopied = 8,
        framesAccepted = 8,
        duplicateImageTimestamps = 0,
        duplicateResultTimestamps = 0,
        duplicateOrdinals = 0,
        unmatchedImages = 0,
        unmatchedResults = 0,
        invalidImages = 0,
        captureFailures = 0,
        sequenceAborted = false,
        timedOut = false,
        cancelled = false,
        staleCallbacksAccepted = 0,
        imageObjectsStillOwned = 0,
        evidencePersisted = false,
        failureDetail = null,
    )
}
