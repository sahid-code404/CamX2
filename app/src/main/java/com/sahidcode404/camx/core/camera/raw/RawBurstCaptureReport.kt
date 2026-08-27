package com.sahidcode404.camx.core.camera.raw

import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.CaptureToken
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exact Camera2 identity observed for the eight-frame CP1 burst transaction. */
data class RawBurstCaptureIdentity(
    val captureToken: CaptureToken,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val rawSize: IntSize,
    val displayRotationAtShutter: DisplayRotation,
)

/**
 * Runtime RAW_SENSOR plane-layout evidence from one probe-only Camera2 still capture. The probe is
 * tied to the exact canonical/profile/route selection and is never counted as a burst member.
 */
data class RawSourceLayoutCertification(
    val captureToken: CaptureToken,
    val selectionGeneration: SelectionGeneration,
    val sessionGeneration: SessionGeneration,
    val canonicalLensFingerprint: CanonicalLensFingerprint,
    val cameraProfileFingerprint: CameraProfileFingerprint,
    val routeId: CameraRouteId,
    val rawSize: IntSize,
    val imageFormat: Int,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
    val sourceRequiredBytes: Long,
) {
    init {
        require(rowStrideBytes > 0)
        require(pixelStrideBytes > 0)
        require(sourceRequiredBytes > 0L)
    }

    fun matches(selection: ActiveCameraSelection): Boolean =
        selection.selectionGeneration == selectionGeneration &&
            selection.canonicalLensFingerprint == canonicalLensFingerprint &&
            selection.profileFingerprint == cameraProfileFingerprint &&
            selection.routeId == routeId
}

data class RawBurstDiagnosticsSnapshot(
    val imagesReceived: Int = 0,
    val resultsReceived: Int = 0,
    val exactPairsCreated: Int = 0,
    val framesCopied: Int = 0,
    val framesAccepted: Int = 0,
    val duplicateImageTimestamps: Int = 0,
    val duplicateResultTimestamps: Int = 0,
    val duplicateOrdinals: Int = 0,
    val unmatchedImages: Int = 0,
    val unmatchedResults: Int = 0,
)

/** Immutable, all-path CP1 capture diagnostics. A partial set is never successful. */
data class RawBurstCaptureReport(
    val identity: RawBurstCaptureIdentity?,
    val preflight: RawSourceLayoutCertification?,
    val requestedFrames: Int,
    val preflightFrames: Int,
    val captureRequestsSubmitted: Int,
    val imagesReceived: Int,
    val resultsReceived: Int,
    val exactPairsCreated: Int,
    val framesCopied: Int,
    val framesAccepted: Int,
    val duplicateImageTimestamps: Int,
    val duplicateResultTimestamps: Int,
    val duplicateOrdinals: Int,
    val unmatchedImages: Int,
    val unmatchedResults: Int,
    val invalidImages: Int,
    val captureFailures: Int,
    val sequenceAborted: Boolean,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val staleCallbacksAccepted: Int,
    val imageObjectsStillOwned: Int,
    val evidencePersisted: Boolean,
    val failureDetail: String?,
) {
    init {
        require(requestedFrames > 0)
        require(preflightFrames in 0..1)
        require(captureRequestsSubmitted in 0..requestedFrames)
        listOf(
            imagesReceived,
            resultsReceived,
            exactPairsCreated,
            framesCopied,
            framesAccepted,
            duplicateImageTimestamps,
            duplicateResultTimestamps,
            duplicateOrdinals,
            unmatchedImages,
            unmatchedResults,
            invalidImages,
            captureFailures,
            staleCallbacksAccepted,
            imageObjectsStillOwned,
        ).forEach { require(it >= 0) }
    }

    val success: Boolean
        get() = identity != null &&
            preflight != null &&
            preflightFrames == 1 &&
            captureRequestsSubmitted == requestedFrames &&
            imagesReceived == requestedFrames &&
            resultsReceived == requestedFrames &&
            exactPairsCreated == requestedFrames &&
            framesCopied == requestedFrames &&
            framesAccepted == requestedFrames &&
            duplicateImageTimestamps == 0 &&
            duplicateResultTimestamps == 0 &&
            duplicateOrdinals == 0 &&
            unmatchedImages == 0 &&
            unmatchedResults == 0 &&
            invalidImages == 0 &&
            captureFailures == 0 &&
            !sequenceAborted &&
            !timedOut &&
            !cancelled &&
            staleCallbacksAccepted == 0 &&
            imageObjectsStillOwned == 0 &&
            failureDetail == null

    fun withEvidencePersisted(persisted: Boolean): RawBurstCaptureReport =
        copy(evidencePersisted = persisted)

    companion object {
        fun rejected(
            requestedFrames: Int,
            detail: String,
            preflight: RawSourceLayoutCertification? = null,
            cancelled: Boolean = false,
        ) = RawBurstCaptureReport(
            identity = null,
            preflight = preflight,
            requestedFrames = requestedFrames,
            preflightFrames = if (preflight == null) 0 else 1,
            captureRequestsSubmitted = 0,
            imagesReceived = 0,
            resultsReceived = 0,
            exactPairsCreated = 0,
            framesCopied = 0,
            framesAccepted = 0,
            duplicateImageTimestamps = 0,
            duplicateResultTimestamps = 0,
            duplicateOrdinals = 0,
            unmatchedImages = 0,
            unmatchedResults = 0,
            invalidImages = 0,
            captureFailures = 0,
            sequenceAborted = false,
            timedOut = false,
            cancelled = cancelled,
            staleCallbacksAccepted = 0,
            imageObjectsStillOwned = 0,
            evidencePersisted = false,
            failureDetail = detail,
        )
    }
}

/**
 * A bounded diagnostic tap around the existing exact M4 pairer. It observes counters only; it owns
 * no Camera2 objects and has no authority to alter capture, pairing, or lifecycle decisions.
 */
internal object RawBurstDiagnosticsHub {
    internal class Session internal constructor(internal val token: Any)

    private class Counters {
        val imagesReceived = AtomicInteger()
        val resultsReceived = AtomicInteger()
        val exactPairsCreated = AtomicInteger()
        val framesCopied = AtomicInteger()
        val framesAccepted = AtomicInteger()
        val duplicateImageTimestamps = AtomicInteger()
        val duplicateResultTimestamps = AtomicInteger()
        val duplicateOrdinals = AtomicInteger()
        val unmatchedImages = AtomicInteger()
        val unmatchedResults = AtomicInteger()

        fun snapshot() = RawBurstDiagnosticsSnapshot(
            imagesReceived = imagesReceived.get(),
            resultsReceived = resultsReceived.get(),
            exactPairsCreated = exactPairsCreated.get(),
            framesCopied = framesCopied.get(),
            framesAccepted = framesAccepted.get(),
            duplicateImageTimestamps = duplicateImageTimestamps.get(),
            duplicateResultTimestamps = duplicateResultTimestamps.get(),
            duplicateOrdinals = duplicateOrdinals.get(),
            unmatchedImages = unmatchedImages.get(),
            unmatchedResults = unmatchedResults.get(),
        )
    }

    private data class Active(val token: Any, val counters: Counters)
    private val active = AtomicReference<Active?>(null)

    fun begin(): Session {
        val token = Any()
        val holder = Active(token, Counters())
        check(active.compareAndSet(null, holder)) { "A RAW burst diagnostic session is already active" }
        return Session(token)
    }

    fun finish(session: Session): RawBurstDiagnosticsSnapshot {
        val holder = checkNotNull(active.get()) { "RAW burst diagnostic session is not active" }
        check(holder.token === session.token) { "RAW burst diagnostic session identity changed" }
        check(active.compareAndSet(holder, null)) { "RAW burst diagnostic session changed while finishing" }
        return holder.counters.snapshot()
    }

    fun imageReceived() = current()?.imagesReceived?.incrementAndGet().let { Unit }
    fun resultReceived() = current()?.resultsReceived?.incrementAndGet().let { Unit }
    fun exactPairCreated() = current()?.exactPairsCreated?.incrementAndGet().let { Unit }
    fun duplicateImageTimestamp() = current()?.duplicateImageTimestamps?.incrementAndGet().let { Unit }
    fun duplicateResultTimestamp() = current()?.duplicateResultTimestamps?.incrementAndGet().let { Unit }
    fun duplicateOrdinal() = current()?.duplicateOrdinals?.incrementAndGet().let { Unit }

    fun frameCopiedAndAccepted() {
        current()?.let { counters ->
            counters.framesCopied.incrementAndGet()
            counters.framesAccepted.incrementAndGet()
        }
    }

    fun pairingClosed(unmatchedImages: Int, unmatchedResults: Int) {
        current()?.let { counters ->
            counters.unmatchedImages.set(unmatchedImages)
            counters.unmatchedResults.set(unmatchedResults)
        }
    }

    private fun current(): Counters? = active.get()?.counters
}
