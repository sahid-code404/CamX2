package com.sahidcode404.camx.core.rawvideo.recording

import android.os.SystemClock
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Bounded ownership bridge from detached timestamp pairs to immutable canonical CXRB packets.
 *
 * A Camera2 Image must never sit in the asynchronous ingest queue. The pairer first copies the
 * declared RAW plane extent and closes the ImageReader lease; only heap-backed DetachedRawVideoPair
 * values can wait behind storage backpressure. Canonicalization and hashing run on the ingest worker,
 * so a large RAW frame cannot stall the Camera2 result callback while the queue is full.
 */
internal class AndroidSensorRawVideoIngest(
    context: RawCaptureContext,
    providerEpoch: Long,
    profile: SensorRawVideoProfile,
    private val reservation: SensorRawVideoReservation,
    private val spool: CxrbSensorRawVideoSpool,
    private val onFatal: (Throwable) -> Unit,
) : AutoCloseable {
    private val assembler = SensorRawVideoFrameAssembler(context, providerEpoch, profile)
    private val queue = ArrayBlockingQueue<DetachedRawVideoPair>(reservation.ingestQueueFrames)
    private val accepting = AtomicBoolean(true)
    private val finishing = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val highWater = AtomicInteger(0)
    private val firstFrame = CompletableDeferred<Unit>()
    private val worker = Thread(::runWorker, "camx-raw-video-ingest").apply {
        isDaemon = true
        start()
    }

    fun offer(pair: DetachedRawVideoPair): Boolean {
        if (!accepting.get()) {
            pair.close()
            return false
        }

        val accepted = try {
            queue.offer(
                pair,
                M10RawVideoLimits.INGEST_BACKPRESSURE_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } catch (error: Throwable) {
            pair.close()
            fail(error)
            return false
        }
        if (!accepted) {
            pair.close()
            fail(
                IllegalStateException(
                    "M10 canonical ingest queue remained saturated after " +
                        M10RawVideoLimits.INGEST_BACKPRESSURE_TIMEOUT_MILLIS +
                        " ms of bounded backpressure; recording stopped instead of dropping RAW evidence",
                ),
            )
            return false
        }
        updateHighWater()
        return true
    }

    suspend fun awaitFirstFrame(timeoutMillis: Long = M10RawVideoLimits.SESSION_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) { firstFrame.await() }
    }

    fun finish(): SensorRawVideoSummary {
        accepting.set(false)
        finishing.set(true)
        worker.interrupt()
        worker.join(M10RawVideoLimits.WORKER_JOIN_TIMEOUT_MILLIS)
        check(!worker.isAlive) { "M10 ingest worker did not terminate within the bounded join interval" }
        check(!failed.get()) { "M10 ingest failed before a clean stop" }
        return spool.finish(highWater.get())
    }

    fun abort(deleteOutput: Boolean) {
        accepting.set(false)
        finishing.set(true)
        closeQueuedPairs()
        worker.interrupt()
        spool.abort(deleteOutput)
        runCatching { worker.join(M10RawVideoLimits.WORKER_JOIN_TIMEOUT_MILLIS) }
    }

    override fun close() = abort(deleteOutput = false)

    private fun runWorker() {
        try {
            while (true) {
                val pair = try {
                    queue.poll(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (pair == null) {
                    if (finishing.get() && queue.isEmpty()) break
                    continue
                }
                val batch = try {
                    assembler.assemble(
                        pair,
                        SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L),
                    )
                } catch (error: Throwable) {
                    fail(error)
                    break
                }
                if (!spool.tryAppend(batch.gapBefore, batch.frame)) {
                    fail(IllegalStateException("M10 CXRB spool queue saturated; recording stopped instead of dropping a RAW frame"))
                    break
                }
                firstFrame.complete(Unit)
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            closeQueuedPairs()
        }
    }

    private fun closeQueuedPairs() {
        while (true) {
            val pair = queue.poll() ?: return
            runCatching { pair.close() }
        }
    }

    private fun fail(error: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        accepting.set(false)
        firstFrame.completeExceptionally(error)
        onFatal(error)
    }

    private fun updateHighWater() {
        val size = queue.size
        while (true) {
            val previous = highWater.get()
            if (size <= previous || highWater.compareAndSet(previous, size)) return
        }
    }
}
