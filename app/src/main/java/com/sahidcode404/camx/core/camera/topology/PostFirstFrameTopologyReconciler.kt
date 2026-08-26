package com.sahidcode404.camx.core.camera.topology

import android.os.SystemClock
import com.sahidcode404.camx.core.camera.discovery.CameraEvidenceSnapshot
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun interface AdvertisedTopologyEvidenceProvider {
    suspend fun collect(): List<CameraEvidenceSnapshot>
}

/**
 * One-shot, post-first-frame advertised reconciliation.
 *
 * This owns only low-frequency metadata work on Dispatchers.Default. It never runs on
 * CameraSessionController's camera dispatcher and publishing a topology never restarts preview.
 */
internal class PostFirstFrameTopologyReconciler(
    private val environment: CameraEnvironmentFingerprint,
    private val repository: CameraTopologyRepository,
    private val providers: List<AdvertisedTopologyEvidenceProvider>,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        require(providers.isNotEmpty()) { "At least one advertised topology provider is required" }
        require(providers.size <= CameraTopologyResolver.MAX_PROVENANCE_SOURCES) {
            "Advertised topology provider count exceeds the provenance bound"
        }
    }

    fun startAfterFirstFrame() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        scope.launch {
            val previous = repository.topology.value
            val permit = repository.beginReconciliation(environment)
            val snapshots = ArrayList<CameraEvidenceSnapshot>()
            for (provider in providers) {
                if (closed.get()) return@launch
                val provided = try {
                    provider.collect()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A failed backend is additional evidence loss, not a reason to erase a healthy backend.
                    emptyList()
                }
                snapshots += provided
                if (snapshots.sumOf { it.evidence.size } > CameraTopologyResolver.MAX_TOTAL_EVIDENCE) {
                    // Fail closed before invoking expensive reconciliation on pathological input.
                    return@launch
                }
            }
            if (closed.get()) return@launch
            val resolved = try {
                CameraTopologyResolver.resolve(
                    environment = environment,
                    snapshots = snapshots,
                    generatedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
                    previousTrustedTopology = previous,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                return@launch
            }
            if (!closed.get()) repository.publish(resolved, permit)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
    }
}
