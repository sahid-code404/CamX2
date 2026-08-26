package com.sahidcode404.camx.core.camera.preview

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceProviderTest {
    @Test
    fun publishedBindingCanBeAcquiredAsTheSingleCurrentLease() {
        val slot = slot()
        val binding = TestBinding(PreviewSurfaceIdentity(1L), version = 1)
        slot.publish(binding)

        val lease = awaitResult { slot.awaitLease() }.getOrThrow()

        assertEquals(binding, lease.value)
        assertFalse(lease.isInvalidated)
    }

    @Test
    fun sameIdentityRefreshUpdatesCurrentLeaseWithoutInvalidatingIt() {
        val slot = slot()
        slot.publish(TestBinding(PreviewSurfaceIdentity(2L), version = 1))
        val lease = awaitResult { slot.awaitLease() }.getOrThrow()

        slot.publish(TestBinding(PreviewSurfaceIdentity(2L), version = 2))

        assertEquals(2, lease.value.version)
        assertFalse(lease.isInvalidated)
    }

    @Test
    fun replacementIdentityInvalidatesOldLeaseAndPublishesNewBinding() {
        val slot = slot()
        slot.publish(TestBinding(PreviewSurfaceIdentity(3L), version = 1))
        val oldLease = awaitResult { slot.awaitLease() }.getOrThrow()

        val replacement = TestBinding(PreviewSurfaceIdentity(4L), version = 1)
        slot.publish(replacement)
        val newLease = awaitResult { slot.awaitLease() }.getOrThrow()

        assertTrue(oldLease.isInvalidated)
        assertFalse(newLease.isInvalidated)
        assertEquals(replacement, newLease.value)
    }

    @Test
    fun staleDestroyCannotInvalidateNewerLease() {
        val slot = slot()
        val oldIdentity = PreviewSurfaceIdentity(5L)
        val newIdentity = PreviewSurfaceIdentity(6L)
        slot.publish(TestBinding(oldIdentity, version = 1))
        val oldLease = awaitResult { slot.awaitLease() }.getOrThrow()
        slot.publish(TestBinding(newIdentity, version = 1))
        val newLease = awaitResult { slot.awaitLease() }.getOrThrow()

        slot.invalidate(oldIdentity)

        assertTrue(oldLease.isInvalidated)
        assertFalse(newLease.isInvalidated)
        assertEquals(newIdentity, newLease.identity)
    }

    @Test
    fun currentDestroyInvalidatesCurrentLeaseAndLeavesNoReacquirableBinding() {
        val slot = slot()
        val identity = PreviewSurfaceIdentity(7L)
        slot.publish(TestBinding(identity, version = 1))
        val lease = awaitResult { slot.awaitLease() }.getOrThrow()

        slot.invalidate(identity)

        assertTrue(lease.isInvalidated)
    }

    @Test
    fun consumerCloseRelinquishesLeaseWithoutDestroyingCurrentBinding() {
        val slot = slot()
        val binding = TestBinding(PreviewSurfaceIdentity(8L), version = 1)
        slot.publish(binding)
        val first = awaitResult { slot.awaitLease() }.getOrThrow()

        first.close()
        val second = awaitResult { slot.awaitLease() }.getOrThrow()

        assertTrue(first.isInvalidated)
        assertFalse(second.isInvalidated)
        assertEquals(binding, second.value)
    }

    @Test
    fun providerCloseInvalidatesActiveLeaseExactlyOnce() {
        val slot = slot()
        slot.publish(TestBinding(PreviewSurfaceIdentity(9L), version = 1))
        val lease = awaitResult { slot.awaitLease() }.getOrThrow()

        slot.close()
        slot.close()

        assertTrue(lease.isInvalidated)
    }

    @Test
    fun onlyOnePendingAwaiterIsPermitted() {
        val slot = slot()
        val firstResult = AtomicReference<Result<GenerationSafeLeaseSlot.Lease<TestBinding>>?>()
        val firstStarted = CountDownLatch(1)
        val firstCompleted = CountDownLatch(1)
        val first = suspend {
            firstStarted.countDown()
            slot.awaitLease()
        }
        first.startCoroutine(
            object : Continuation<GenerationSafeLeaseSlot.Lease<TestBinding>> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<GenerationSafeLeaseSlot.Lease<TestBinding>>) {
                    firstResult.set(result)
                    firstCompleted.countDown()
                }
            },
        )
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

        val second = awaitResult { slot.awaitLease() }
        assertTrue(second.isFailure)

        slot.publish(TestBinding(PreviewSurfaceIdentity(10L), version = 1))
        assertTrue(firstCompleted.await(5, TimeUnit.SECONDS))
        assertTrue(checkNotNull(firstResult.get()).isSuccess)
    }

    private fun slot() = GenerationSafeLeaseSlot<TestBinding>(TestBinding::identity)

    private fun <T> awaitResult(block: suspend () -> T): Result<T> {
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome.set(result)
                    completed.countDown()
                }
            },
        )
        check(completed.await(5, TimeUnit.SECONDS))
        return checkNotNull(outcome.get())
    }

    private data class TestBinding(
        val identity: PreviewSurfaceIdentity,
        val version: Int,
    )
}
