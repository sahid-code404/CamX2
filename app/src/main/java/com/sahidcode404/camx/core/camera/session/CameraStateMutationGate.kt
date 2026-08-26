package com.sahidcode404.camx.core.camera.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Serializes short authoritative mutations on the camera-control dispatcher.
 *
 * A live caller waits cancellably, so an obsolete orchestration intent can disappear before it owns
 * camera state. Once the mutex is acquired, dispatcher handoff, the mutation, and the return hop are
 * enclosed by NonCancellable. If a controller transaction invokes a follow-up mutation after its
 * caller was cancelled, that already-committed follow-up is allowed to reacquire non-cancellably.
 *
 * The mutation block itself must stay short and non-suspending. Platform waits, timeouts, joins,
 * deferred completion, discovery, disk IO, and arbitrary long work are forbidden while this mutex is
 * held.
 */
internal class CameraStateMutationGate(
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: () -> T): T {
        if (currentCoroutineContext().isActive) {
            mutex.lock()
        } else {
            withContext(NonCancellable) { mutex.lock() }
        }
        return try {
            withContext(NonCancellable) {
                withContext(dispatcher) { block() }
            }
        } finally {
            mutex.unlock()
        }
    }
}
