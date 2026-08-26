package com.sahidcode404.camx.core.camera.preview

import android.view.Surface
import com.sahidcode404.camx.core.camera.model.IntSize
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

@JvmInline
value class PreviewSurfaceIdentity(val value: Long) {
    init { require(value > 0L) { "Preview surface identity must be positive" } }
}

/** Process-unique while callbacks can coexist; process death also destroys every old callback. */
object PreviewSurfaceIdentityAllocator {
    private val sequence = AtomicLong(0L)

    fun next(): PreviewSurfaceIdentity {
        while (true) {
            val current = sequence.get()
            check(current < Long.MAX_VALUE) { "Preview surface identity exhausted" }
            val next = current + 1L
            if (sequence.compareAndSet(current, next)) return PreviewSurfaceIdentity(next)
        }
    }
}

data class PreviewSurfaceBinding(
    val surface: Surface,
    val viewSize: IntSize,
    val identity: PreviewSurfaceIdentity,
    val bufferSize: IntSize = viewSize,
)

interface PreviewSurfaceLease : AutoCloseable {
    val binding: PreviewSurfaceBinding

    /** Completes only when this exact lease is invalidated, replaced, closed, or its provider closes. */
    suspend fun awaitInvalidation()
}

interface PreviewSurfaceProvider {
    suspend fun awaitSurface(): PreviewSurfaceLease

    /** A stale identity must be ignored and must never revoke a newer lease. */
    fun invalidate(identity: PreviewSurfaceIdentity)
}

/**
 * Generation-safe SurfaceView bridge. The provider owns no Surface resource: it owns only binding
 * identity and exactly one revocable, non-owning lease. Late UI callbacks are therefore harmless.
 */
class GenerationSafePreviewSurfaceProvider : PreviewSurfaceProvider, AutoCloseable {
    private val slot = GenerationSafeLeaseSlot<PreviewSurfaceBinding>(PreviewSurfaceBinding::identity)

    fun publish(binding: PreviewSurfaceBinding) {
        slot.publish(binding)
    }

    override suspend fun awaitSurface(): PreviewSurfaceLease {
        val lease = slot.awaitLease()
        return object : PreviewSurfaceLease {
            override val binding: PreviewSurfaceBinding
                get() = lease.value

            override suspend fun awaitInvalidation() {
                lease.awaitInvalidation()
            }

            override fun close() {
                lease.close()
            }
        }
    }

    override fun invalidate(identity: PreviewSurfaceIdentity) {
        slot.invalidate(identity)
    }

    override fun close() {
        slot.close()
    }
}

/** Pure single-consumer lease state used by the Android adapter and deterministic JVM tests. */
internal class GenerationSafeLeaseSlot<T>(
    private val identityOf: (T) -> PreviewSurfaceIdentity,
) : AutoCloseable {
    private val lock = Any()
    private var current: T? = null
    private var activeLease: Lease<T>? = null
    private var waiter: CompletableDeferred<Lease<T>>? = null
    private var closed = false

    fun publish(value: T) {
        var invalidated: Lease<T>? = null
        var delivery: LeaseDelivery<T>? = null
        synchronized(lock) {
            if (closed) return
            val previous = current
            if (previous != null && identityOf(previous) != identityOf(value)) {
                invalidated = activeLease
                activeLease = null
            }
            current = value
            delivery = createDeliveryLocked()
        }
        invalidated?.invalidate()
        delivery?.complete()
    }

    suspend fun awaitLease(): Lease<T> {
        var immediate: Lease<T>? = null
        var pending: CompletableDeferred<Lease<T>>? = null
        synchronized(lock) {
            check(!closed) { "Preview surface provider is closed" }
            check(waiter == null) { "Only one preview surface waiter is permitted" }
            val value = current
            if (value != null && activeLease == null) {
                immediate = newLeaseLocked(value)
            } else {
                pending = CompletableDeferred()
                waiter = pending
            }
        }
        immediate?.let { return it }
        val deferred = checkNotNull(pending)
        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                if (waiter === deferred) waiter = null
            }
        }
    }

    fun invalidate(identity: PreviewSurfaceIdentity) {
        var invalidated: Lease<T>? = null
        synchronized(lock) {
            val value = current ?: return
            if (identityOf(value) != identity) return
            current = null
            invalidated = activeLease
            activeLease = null
        }
        invalidated?.invalidate()
    }

    private fun release(lease: Lease<T>) {
        var delivery: LeaseDelivery<T>? = null
        synchronized(lock) {
            if (activeLease !== lease) {
                lease.invalidate()
                return
            }
            activeLease = null
            delivery = createDeliveryLocked()
        }
        lease.invalidate()
        delivery?.complete()
    }

    private fun currentValue(lease: Lease<T>, fallback: T): T = synchronized(lock) {
        val value = current
        if (activeLease === lease && value != null && identityOf(value) == lease.identity) value else fallback
    }

    private fun newLeaseLocked(value: T): Lease<T> = Lease(
        owner = this,
        identity = identityOf(value),
        initial = value,
    ).also { activeLease = it }

    private fun createDeliveryLocked(): LeaseDelivery<T>? {
        val deferred = waiter ?: return null
        if (activeLease != null) return null
        val value = current ?: return null
        waiter = null
        return LeaseDelivery(deferred, newLeaseLocked(value))
    }

    override fun close() {
        var invalidated: Lease<T>? = null
        var pending: CompletableDeferred<Lease<T>>? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null
            invalidated = activeLease
            activeLease = null
            pending = waiter
            waiter = null
        }
        invalidated?.invalidate()
        pending?.completeExceptionally(CancellationException("Preview surface provider closed"))
    }

    internal class Lease<T> private constructor(
        private val owner: GenerationSafeLeaseSlot<T>,
        internal val identity: PreviewSurfaceIdentity,
        private val initial: T,
    ) : AutoCloseable {
        private val invalidation = CompletableDeferred<Unit>()

        val value: T
            get() = owner.currentValue(this, initial)

        internal val isInvalidated: Boolean
            get() = invalidation.isCompleted

        suspend fun awaitInvalidation() {
            invalidation.await()
        }

        internal fun invalidate() {
            invalidation.complete(Unit)
        }

        override fun close() {
            owner.release(this)
        }

        companion object {
            operator fun <T> invoke(
                owner: GenerationSafeLeaseSlot<T>,
                identity: PreviewSurfaceIdentity,
                initial: T,
            ): Lease<T> = Lease(owner, identity, initial)
        }
    }

    private data class LeaseDelivery<T>(
        val deferred: CompletableDeferred<Lease<T>>,
        val lease: Lease<T>,
    ) {
        fun complete() {
            if (!deferred.complete(lease)) lease.close()
        }
    }
}
