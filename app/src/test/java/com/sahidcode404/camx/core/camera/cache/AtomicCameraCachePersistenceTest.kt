package com.sahidcode404.camx.core.camera.cache

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicCameraCachePersistenceTest {
    @Test
    fun firstWriteFlushesSyncsClosesThenRenamesAndReadsBack() {
        val fs = FakeCacheFileSystem()
        val persistence = AtomicCameraCachePersistence(File("/cache"), fs)
        val snapshot = hotSnapshot()

        assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeHot(snapshot) })
        assertEquals(CacheRead.Hit(snapshot), awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) })
        assertEquals(listOf("open", "write", "flush", "sync", "close", "rename"), fs.writeEvents)
        assertFalse(fs.hasTemporaryFiles())
    }

    @Test
    fun successfulReplacementPublishesOnlyCompleteNewFile() {
        val fs = FakeCacheFileSystem()
        val persistence = AtomicCameraCachePersistence(File("/cache"), fs)
        assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeHot(hotSnapshot(1L)) })
        assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeHot(hotSnapshot(2L)) })
        assertEquals(
            CacheRead.Hit(hotSnapshot(2L)),
            awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) },
        )
        assertFalse(fs.hasTemporaryFiles())
    }

    @Test
    fun writeFlushSyncCloseAndRenameFailuresKeepOldAuthorityAndCleanTemp() {
        Fault.values().forEach { fault ->
            val fs = FakeCacheFileSystem()
            val persistence = AtomicCameraCachePersistence(File("/cache"), fs)
            assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeHot(hotSnapshot(1L)) })
            fs.fault.set(fault)

            assertTrue(awaitSuspend { persistence.writeHot(hotSnapshot(2L)) } is CacheWrite.IoFailure)
            fs.fault.set(null)
            assertEquals(
                CacheRead.Hit(hotSnapshot(1L)),
                awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) },
            )
            assertFalse("temp leaked for $fault", fs.hasTemporaryFiles())
        }
    }

    @Test
    fun corruptTopologyDoesNotAffectIndependentHotRead() {
        val fs = FakeCacheFileSystem()
        val persistence = AtomicCameraCachePersistence(File("/cache"), fs)
        val hot = hotSnapshot()
        assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeHot(hot) })
        assertEquals(CacheWrite.Success, awaitSuspend { persistence.writeTopology(representativeTopology()) })
        fs.corrupt("camx-topology.cache")

        assertEquals(CacheRead.Hit(hot), awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) })
        assertTrue(awaitSuspend { persistence.readTopology(TEST_ENVIRONMENT) } is CacheRead.Corrupt)
    }

    @Test
    fun oversizedDiskFileIsRejectedBeforeReadAllocation() {
        val fs = FakeCacheFileSystem()
        val persistence = AtomicCameraCachePersistence(File("/cache"), fs)
        fs.put("camx-hot.cache", ByteArray(CacheBounds.HOT_FILE_BYTES + 1))
        assertTrue(awaitSuspend { persistence.readHot(TEST_ENVIRONMENT) } is CacheRead.Corrupt)
        assertEquals(0, fs.openInputCount)
    }

    private enum class Fault { WRITE, FLUSH, SYNC, CLOSE, RENAME }

    private class FakeCacheFileSystem : CacheFileSystem {
        private val files = linkedMapOf<String, ByteArray>()
        val writeEvents = mutableListOf<String>()
        val fault = AtomicReference<Fault?>(null)
        var openInputCount = 0

        override fun ensureDirectory(directory: File) = Unit
        override fun exists(file: File): Boolean = key(file) in files
        override fun length(file: File): Long = files[key(file)]?.size?.toLong() ?: 0L

        override fun openInput(file: File): InputStream {
            openInputCount++
            return ByteArrayInputStream(checkNotNull(files[key(file)]).copyOf())
        }

        override fun openOutput(file: File): CacheOutputSink {
            writeEvents += "open"
            files[key(file)] = ByteArray(0)
            return object : CacheOutputSink {
                private val bytes = java.io.ByteArrayOutputStream()

                override fun write(bytesToWrite: ByteArray) {
                    writeEvents += "write"
                    fail(Fault.WRITE)
                    bytes.write(bytesToWrite)
                }

                override fun flush() {
                    writeEvents += "flush"
                    fail(Fault.FLUSH)
                }

                override fun sync() {
                    writeEvents += "sync"
                    fail(Fault.SYNC)
                }

                override fun close() {
                    writeEvents += "close"
                    files[key(file)] = bytes.toByteArray()
                    fail(Fault.CLOSE)
                }
            }
        }

        override fun rename(source: File, destination: File) {
            writeEvents += "rename"
            fail(Fault.RENAME)
            files[key(destination)] = checkNotNull(files.remove(key(source)))
        }

        override fun delete(file: File): Boolean {
            files.remove(key(file))
            return true
        }

        fun hasTemporaryFiles(): Boolean = files.keys.any { it.endsWith(".tmp") }

        fun corrupt(name: String) {
            val bytes = checkNotNull(files[name]).copyOf()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            files[name] = bytes
        }

        fun put(name: String, bytes: ByteArray) {
            files[name] = bytes
        }

        private fun fail(expected: Fault) {
            if (fault.get() == expected) throw java.io.IOException("Injected $expected failure")
        }

        private fun key(file: File): String = file.name
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
