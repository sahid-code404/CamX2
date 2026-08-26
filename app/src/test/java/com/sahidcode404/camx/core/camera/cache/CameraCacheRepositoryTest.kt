package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCacheRepositoryTest {
    @Test
    fun diskMissCorruptionAndIoFailureKeepValidSameEnvironmentHotMemory() {
        val persistence = MutablePersistence()
        val repository = CameraCacheRepository(persistence)
        val memory = hotSnapshot(10L)
        assertTrue(awaitSuspend { repository.replaceHot(memory) })

        listOf<CacheRead<HotStartSnapshot>>(
            CacheRead.Miss,
            CacheRead.Corrupt("bad checksum"),
            CacheRead.IoFailure("disk unavailable"),
        ).forEach { read ->
            persistence.hotRead.set(read)
            assertEquals(read, awaitSuspend { repository.loadHot(TEST_ENVIRONMENT) })
            assertEquals(memory, repository.currentHot())
        }
    }

    @Test
    fun diskMissCorruptionAndIoFailureKeepValidSameEnvironmentTopologyMemory() {
        val persistence = MutablePersistence()
        val repository = CameraCacheRepository(persistence)
        val memory = representativeTopology()
        assertTrue(awaitSuspend { repository.replaceTopology(memory) })

        listOf<CacheRead<CameraTopologySnapshot>>(
            CacheRead.Miss,
            CacheRead.Corrupt("bad topology"),
            CacheRead.IoFailure("disk unavailable"),
        ).forEach { read ->
            persistence.topologyRead.set(read)
            assertEquals(read, awaitSuspend { repository.loadTopology(TEST_ENVIRONMENT) })
            assertEquals(memory, repository.currentTopology())
        }
    }

    @Test
    fun actualEnvironmentChangeInvalidatesIncompatibleMemory() {
        val persistence = MutablePersistence()
        val repository = CameraCacheRepository(persistence)
        assertTrue(awaitSuspend { repository.replaceHot(hotSnapshot()) })
        assertTrue(awaitSuspend { repository.replaceTopology(representativeTopology()) })
        val other = CameraEnvironmentFingerprint("environment:other")

        assertEquals(CacheRead.Miss, awaitSuspend { repository.loadHot(other) })
        assertEquals(CacheRead.Miss, awaitSuspend { repository.loadTopology(other) })
        assertNull(repository.currentHot())
        assertNull(repository.currentTopology())
    }

    @Test
    fun staleLoadCannotOverwriteNewerHotState() {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val persistence = object : MutablePersistence() {
            override suspend fun readHot(environment: CameraEnvironmentFingerprint): CacheRead<HotStartSnapshot> {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                return CacheRead.Hit(hotSnapshot(1L))
            }
        }
        val repository = CameraCacheRepository(persistence)
        val loadResult = AtomicReference<CacheRead<HotStartSnapshot>>()
        val replaceResult = AtomicReference<Boolean>()
        val loadThread = Thread { loadResult.set(awaitSuspend { repository.loadHot(TEST_ENVIRONMENT) }) }
        loadThread.start()
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        val replaceThread = Thread { replaceResult.set(awaitSuspend { repository.replaceHot(hotSnapshot(2L)) }) }
        replaceThread.start()
        awaitCondition { repository.currentHot()?.lastVerifiedElapsedRealtimeNs == 2L }
        assertEquals(2L, repository.currentHot()?.lastVerifiedElapsedRealtimeNs)
        releaseRead.countDown()
        loadThread.join(5_000L)
        replaceThread.join(5_000L)

        assertFalse(loadThread.isAlive)
        assertFalse(replaceThread.isAlive)
        assertEquals(CacheRead.Stale, loadResult.get())
        assertTrue(replaceResult.get())
        assertEquals(2L, repository.currentHot()?.lastVerifiedElapsedRealtimeNs)
    }

    @Test
    fun newerReplacementWinsFinalMemoryAndDiskWhenOlderWriteBecomesStale() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val shouldBlock = AtomicBoolean(true)
        val disk = AtomicReference<HotStartSnapshot?>()
        val persistence = object : MutablePersistence() {
            override suspend fun writeHot(snapshot: HotStartSnapshot): CacheWrite {
                if (shouldBlock.compareAndSet(true, false)) {
                    firstWriteStarted.countDown()
                    check(releaseFirstWrite.await(5, TimeUnit.SECONDS))
                }
                disk.set(snapshot)
                return CacheWrite.Success
            }
        }
        val repository = CameraCacheRepository(persistence)
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val first = Thread { firstResult.set(awaitSuspend { repository.replaceHot(hotSnapshot(1L)) }) }
        first.start()
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        val second = Thread { secondResult.set(awaitSuspend { repository.replaceHot(hotSnapshot(2L)) }) }
        second.start()
        awaitCondition { repository.currentHot()?.lastVerifiedElapsedRealtimeNs == 2L }
        assertEquals(2L, repository.currentHot()?.lastVerifiedElapsedRealtimeNs)
        releaseFirstWrite.countDown()
        first.join(5_000L)
        second.join(5_000L)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertFalse(firstResult.get())
        assertTrue(secondResult.get())
        assertEquals(2L, repository.currentHot()?.lastVerifiedElapsedRealtimeNs)
        assertEquals(2L, disk.get()?.lastVerifiedElapsedRealtimeNs)
    }

    @Test
    fun failedPersistenceDoesNotRollBackPublishedMemory() {
        val persistence = MutablePersistence().apply { hotWrite.set(CacheWrite.IoFailure("full")) }
        val repository = CameraCacheRepository(persistence)
        val snapshot = hotSnapshot(44L)

        assertFalse(awaitSuspend { repository.replaceHot(snapshot) })
        assertEquals(snapshot, repository.currentHot())
    }

    @Test
    fun topologyPublicationIsDeeplyUnaliasedAndUnmodifiable() {
        val base = representativeTopology()
        val routes = base.routes.toMutableList()
        val lenses = base.canonicalLenses.toMutableList()
        val evidence = base.evidence.toMutableList()
        val input = base.copy(routes = routes, canonicalLenses = lenses, evidence = evidence)
        val repository = CameraCacheRepository(MutablePersistence())
        assertTrue(awaitSuspend { repository.replaceTopology(input) })
        routes.clear()
        lenses.clear()
        evidence.clear()

        val published = checkNotNull(repository.currentTopology())
        assertEquals(2, published.routes.size)
        assertEquals(2, published.canonicalLenses.size)
        assertEquals(1, published.evidence.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (published.routes as MutableList<Any?>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (published.routes.first().capabilities.previewStreams as MutableList<Any?>).clear()
        }
    }

    private open class MutablePersistence : CameraCachePersistence {
        val hotRead = AtomicReference<CacheRead<HotStartSnapshot>>(CacheRead.Miss)
        val topologyRead = AtomicReference<CacheRead<CameraTopologySnapshot>>(CacheRead.Miss)
        val hotWrite = AtomicReference<CacheWrite>(CacheWrite.Success)
        val topologyWrite = AtomicReference<CacheWrite>(CacheWrite.Success)

        override suspend fun readHot(environment: CameraEnvironmentFingerprint): CacheRead<HotStartSnapshot> =
            hotRead.get()

        override suspend fun readTopology(
            environment: CameraEnvironmentFingerprint,
        ): CacheRead<CameraTopologySnapshot> = topologyRead.get()

        override suspend fun writeHot(snapshot: HotStartSnapshot): CacheWrite = hotWrite.get()

        override suspend fun writeTopology(snapshot: CameraTopologySnapshot): CacheWrite = topologyWrite.get()
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        repeat(100_000) {
            if (predicate()) return
            Thread.yield()
        }
        check(predicate()) { "Condition did not become true" }
    }

    private fun <T> awaitSuspend(block: suspend () -> T): T {
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome.set(result)
                    completed.countDown()
                }
            },
        )
        check(completed.await(5, TimeUnit.SECONDS))
        return checkNotNull(outcome.get()).getOrThrow()
    }
}
