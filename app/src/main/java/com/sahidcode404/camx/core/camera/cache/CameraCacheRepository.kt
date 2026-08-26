package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import com.sahidcode404.camx.core.camera.model.frozenCopy
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CacheRead<out T> {
    data class Hit<T>(val value: T) : CacheRead<T>
    data object Miss : CacheRead<Nothing>
    data object Stale : CacheRead<Nothing>
    data class Corrupt(val reason: String) : CacheRead<Nothing>
    data class IoFailure(val reason: String) : CacheRead<Nothing>
}

sealed interface CacheWrite {
    data object Success : CacheWrite
    data class Rejected(val reason: String) : CacheWrite
    data class IoFailure(val reason: String) : CacheWrite
}

interface CameraCachePersistence {
    suspend fun readHot(environment: CameraEnvironmentFingerprint): CacheRead<HotStartSnapshot>
    suspend fun readTopology(environment: CameraEnvironmentFingerprint): CacheRead<CameraTopologySnapshot>
    suspend fun writeHot(snapshot: HotStartSnapshot): CacheWrite
    suspend fun writeTopology(snapshot: CameraTopologySnapshot): CacheWrite
}

/** Persistence and immutable memory publication are ordered outside every camera hot path. */
class CameraCacheRepository(private val persistence: CameraCachePersistence) {
    private val hotMemory = AtomicReference<HotStartSnapshot?>(null)
    private val topologyMemory = AtomicReference<CameraTopologySnapshot?>(null)
    private val hotMutationMutex = Mutex()
    private val topologyMutationMutex = Mutex()
    private val hotRequestSequence = AtomicLong(0L)
    private val topologyRequestSequence = AtomicLong(0L)

    fun currentHot(): HotStartSnapshot? = hotMemory.get()

    fun currentTopology(): CameraTopologySnapshot? = topologyMemory.get()

    suspend fun loadHot(environment: CameraEnvironmentFingerprint): CacheRead<HotStartSnapshot> {
        val request = beginRequest(hotRequestSequence) {
            hotMemory.updateAndGetApi23 { current -> current?.takeIf { it.environment == environment } }
        }
        return hotMutationMutex.withLock {
            if (!isCurrent(hotRequestSequence, request)) return@withLock CacheRead.Stale
            val result = try {
                persistence.readHot(environment)
            } catch (error: Exception) {
                CacheRead.IoFailure(error.message ?: error.javaClass.simpleName)
            }
            synchronized(hotRequestSequence) {
                if (request != hotRequestSequence.get()) {
                    CacheRead.Stale
                } else {
                    when (result) {
                        is CacheRead.Hit -> if (
                            result.value.environment == environment &&
                            result.value.schema == CameraSchemaVersions.HOT_START
                        ) {
                            hotMemory.set(result.value)
                            result
                        } else {
                            CacheRead.Corrupt("Hot cache schema or environment mismatch")
                        }
                        CacheRead.Miss -> CacheRead.Miss
                        CacheRead.Stale -> CacheRead.Stale
                        is CacheRead.Corrupt -> result
                        is CacheRead.IoFailure -> result
                    }
                }
            }
        }
    }

    suspend fun loadTopology(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<CameraTopologySnapshot> {
        val request = beginRequest(topologyRequestSequence) {
            topologyMemory.updateAndGetApi23 { current -> current?.takeIf { it.environment == environment } }
        }
        return topologyMutationMutex.withLock {
            if (!isCurrent(topologyRequestSequence, request)) return@withLock CacheRead.Stale
            val result = try {
                persistence.readTopology(environment)
            } catch (error: Exception) {
                CacheRead.IoFailure(error.message ?: error.javaClass.simpleName)
            }
            synchronized(topologyRequestSequence) {
                if (request != topologyRequestSequence.get()) {
                    CacheRead.Stale
                } else {
                    when (result) {
                        is CacheRead.Hit -> if (
                            result.value.environment == environment &&
                            result.value.schema == CameraSchemaVersions.TOPOLOGY
                        ) {
                            val frozen = result.value.frozenCopy()
                            topologyMemory.set(frozen)
                            CacheRead.Hit(frozen)
                        } else {
                            CacheRead.Corrupt("Topology cache schema or environment mismatch")
                        }
                        CacheRead.Miss -> CacheRead.Miss
                        CacheRead.Stale -> CacheRead.Stale
                        is CacheRead.Corrupt -> result
                        is CacheRead.IoFailure -> result
                    }
                }
            }
        }
    }

    suspend fun replaceHot(snapshot: HotStartSnapshot): Boolean {
        require(snapshot.schema == CameraSchemaVersions.HOT_START) {
            "Cannot persist an unsupported hot-cache schema"
        }
        val request = beginRequest(hotRequestSequence) { hotMemory.set(snapshot) }
        return hotMutationMutex.withLock {
            if (!isCurrent(hotRequestSequence, request)) return@withLock false
            val write = try {
                persistence.writeHot(snapshot)
            } catch (_: Exception) {
                return@withLock false
            }
            write == CacheWrite.Success && isCurrent(hotRequestSequence, request)
        }
    }

    suspend fun replaceTopology(snapshot: CameraTopologySnapshot): Boolean {
        require(snapshot.schema == CameraSchemaVersions.TOPOLOGY) {
            "Cannot persist an unsupported topology-cache schema"
        }
        val frozen = snapshot.frozenCopy()
        val request = beginRequest(topologyRequestSequence) { topologyMemory.set(frozen) }
        return topologyMutationMutex.withLock {
            if (!isCurrent(topologyRequestSequence, request)) return@withLock false
            val write = try {
                persistence.writeTopology(frozen)
            } catch (_: Exception) {
                return@withLock false
            }
            write == CacheWrite.Success && isCurrent(topologyRequestSequence, request)
        }
    }

    private fun beginRequest(sequence: AtomicLong, onBegin: () -> Unit): Long =
        synchronized(sequence) {
            val current = sequence.get()
            check(current < Long.MAX_VALUE) { "Camera cache request sequence exhausted" }
            val next = current + 1L
            sequence.set(next)
            onBegin()
            next
        }

    private fun isCurrent(sequence: AtomicLong, request: Long): Boolean =
        synchronized(sequence) { sequence.get() == request }
}

private inline fun <T> AtomicReference<T>.updateAndGetApi23(transform: (T) -> T): T {
    while (true) {
        val current = get()
        val updated = transform(current)
        if (compareAndSet(current, updated)) return updated
    }
}
