package com.sahidcode404.camx.core.camera.session

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateMutationGateTest {
    @Test
    fun committedMutationFinishesAfterCallerCancellation() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val gate = CameraStateMutationGate(dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)

            val job = launch(Dispatchers.Default) {
                gate.mutate {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    completed.countDown()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()

            assertEquals(0L, completed.count)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledWaiterNeverAcquiresOwnershipAfterEarlierMutation() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val gate = CameraStateMutationGate(dispatcher)
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            var staleRan = false

            val first = launch(Dispatchers.Default) {
                gate.mutate {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val stale = launch(Dispatchers.Default) {
                gate.mutate { staleRan = true }
            }
            stale.cancelAndJoin()
            releaseFirst.countDown()
            first.join()

            assertFalse(staleRan)
            var nextRan = false
            gate.mutate { nextRan = true }
            assertTrue(nextRan)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun overlappingMutationsRemainSerializedAndGateDoesNotDeadlock() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val gate = CameraStateMutationGate(dispatcher)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)

            val first = launch(Dispatchers.Default) {
                gate.mutate {
                    order += "first-start"
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    order += "first-end"
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = launch(Dispatchers.Default) {
                gate.mutate { order += "second" }
            }
            releaseFirst.countDown()
            first.join()
            second.join()
            gate.mutate { order += "third" }

            assertEquals(listOf("first-start", "first-end", "second", "third"), order)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
