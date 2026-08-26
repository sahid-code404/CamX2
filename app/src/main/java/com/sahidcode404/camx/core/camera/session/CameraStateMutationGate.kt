package com.sahidcode404.camx.core.camera.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes short authoritative mutations on the camera-control dispatcher.
 * The block is deliberately non-suspending: platform callback waits, timeouts,
 * joins, and deferred completion cannot occur while this mutex is held.
 */
internal class CameraStateMutationGate(
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: () -> T): T = withContext(dispatcher) {
        mutex.withLock { block() }
    }
}
