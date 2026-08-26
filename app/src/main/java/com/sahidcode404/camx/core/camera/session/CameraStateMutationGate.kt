package com.sahidcode404.camx.core.camera.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Serializes short authoritative mutations on the camera-control dispatcher.
 *
 * Waiting for ownership is always cancellable, so an obsolete orchestration intent can disappear
 * before it owns camera state. Once the mutex is acquired, dispatcher handoff, the mutation, and the
 * return hop are enclosed by NonCancellable so a mutation that already owns authoritative state
 * finishes deterministically even if its caller is cancelled.
 *
 * A caller that needs a second mutation after cancellation must make that commitment explicit at its
 * own transaction boundary rather than allowing an already-cancelled waiter to acquire the gate.
 * This prevents the cancel-before-lock race from resurrecting stale intent.
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
        // Mutex.lock is cancellable. Never convert a cancelled waiter into an owner.
        mutex.lock()
        return try {
            withContext(NonCancellable) {
                withContext(dispatcher) { block() }
            }
        } finally {
            mutex.unlock()
        }
    }
}
