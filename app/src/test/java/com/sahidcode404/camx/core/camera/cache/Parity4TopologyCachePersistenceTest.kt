package com.sahidcode404.camx.core.camera.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Parity4TopologyCachePersistenceTest {
    @Test
    fun legacyTopologyIsRejectedThenCurrentTopologyPersistsAsMigrationWithoutTouchingHotCache() {
        val fs = MemoryFileSystem()
        val persistence = AtomicCameraCachePersistence(File("/cache"), fs)
        val hot = hotSnapshot()
        assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeHot(hot) })

        val legacy = TopologyCacheCodec.encode(representativeTopology()).copyOf().also {
            ByteBuffer.wrap(it, 8, 4).putInt(1)
        }
        fs.put("camx-topology.cache", legacy)

        assertEquals(CacheRead.Miss, awaitSuspend { persistence.readTopology(TEST_ENVIRONMENT) })
        val rejected = TopologyCacheMigrationAudit.snapshot()
        assertEquals("REJECTED_SCHEMA", rejected.status)
        assertEquals(1, rejected.storedTopologySchema)
        assertEquals(true, rejected.environmentCompatible)
        assertEquals(false, rejected.migrated)
        assertEquals(CacheRead.Hit(hot), awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) })

        assertEquals(
            CacheWrite.Success,
            awaitSuspend { persistence.writeTopology(representativeTopology()) },
        )
        val migrated = TopologyCacheMigrationAudit.snapshot()
        assertEquals("MIGRATED", migrated.status)
        assertEquals(2, migrated.storedTopologySchema)
        assertEquals(true, migrated.environmentCompatible)
        assertTrue(migrated.migrated)
        assertEquals(CacheRead.Hit(hot), awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) })

        assertTrue(awaitSuspend { persistence.readTopology(TEST_ENVIRONMENT) } is CacheRead.Hit)
        val warm = TopologyCacheMigrationAudit.snapshot()
        assertEquals("HIT", warm.status)
        assertEquals(2, warm.storedTopologySchema)
        assertEquals(true, warm.environmentCompatible)
    }

    private class MemoryFileSystem : CacheFileSystem {
        private val files = linkedMapOf<String, ByteArray>()

        override fun ensureDirectory(directory: File) = Unit
        override fun exists(file: File): Boolean = file.name in files
        override fun length(file: File): Long = files[file.name]?.size?.toLong() ?: 0L
        override fun openInput(file: File): InputStream =
            ByteArrayInputStream(checkNotNull(files[file.name]).copyOf())

        override fun openOutput(file: File): CacheOutputSink {
            val buffer = ByteArrayOutputStream()
            return object : CacheOutputSink {
                override fun write(bytes: ByteArray) = buffer.write(bytes)
                override fun flush() = Unit
                override fun sync() = Unit
                override fun close() {
                    files[file.name] = buffer.toByteArray()
                }
            }
        }

        override fun rename(source: File, destination: File) {
            files[destination.name] = checkNotNull(files.remove(source.name))
        }

        override fun delete(file: File): Boolean {
            files.remove(file.name)
            return true
        }

        fun put(name: String, bytes: ByteArray) {
            files[name] = bytes
        }
    }

    private fun <T> awaitSuspend(block: suspend () -> T): T {
        val outcome = AtomicReference<Result<T>>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) { outcome.set(result) }
            },
        )
        return checkNotNull(outcome.get()).getOrThrow()
    }
}
