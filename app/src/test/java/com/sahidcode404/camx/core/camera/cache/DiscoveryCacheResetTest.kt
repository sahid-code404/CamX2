package com.sahidcode404.camx.core.camera.cache

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCacheResetTest {
    @Test
    fun `reset removes only topology and deep discovery files`() {
        val directory = File("/virtual/cache")
        val fs = ResetFileSystem(
            setOf(
                File(directory, "camx-hot.cache").path,
                File(directory, "camx-topology.cache").path,
                File(directory, "camx-deep.cache").path,
                File(directory, "camx-deep.cache.tmp").path,
            ),
        )
        val persistence = AtomicCameraCachePersistence(directory, fs)

        assertEquals(DiscoveryCacheResetResult.SUCCESS, awaitResult { persistence.resetDiscoveryCaches() })
        assertTrue(File(directory, "camx-hot.cache").path in fs.files)
        assertFalse(File(directory, "camx-topology.cache").path in fs.files)
        assertFalse(File(directory, "camx-deep.cache").path in fs.files)
        assertFalse(File(directory, "camx-deep.cache.tmp").path in fs.files)
    }

    @Test
    fun `reset reports nothing when no discovery cache exists`() {
        val persistence = AtomicCameraCachePersistence(File("/virtual/empty"), ResetFileSystem(emptySet()))
        assertEquals(
            DiscoveryCacheResetResult.NOTHING_TO_RESET,
            awaitResult { persistence.resetDiscoveryCaches() },
        )
    }

    @Test
    fun `reset fails closed when a discovery cache cannot be deleted`() {
        val directory = File("/virtual/failing")
        val deep = File(directory, "camx-deep.cache").path
        val fs = ResetFileSystem(setOf(deep), failDelete = setOf(deep))
        val persistence = AtomicCameraCachePersistence(directory, fs)
        assertEquals(DiscoveryCacheResetResult.FAILED, awaitResult { persistence.resetDiscoveryCaches() })
        assertTrue(deep in fs.files)
    }

    private class ResetFileSystem(
        existing: Set<String>,
        private val failDelete: Set<String> = emptySet(),
    ) : CacheFileSystem {
        val files = existing.toMutableSet()
        override fun ensureDirectory(directory: File) = Unit
        override fun exists(file: File): Boolean = file.path in files
        override fun length(file: File): Long = 0L
        override fun openInput(file: File): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun openOutput(file: File): CacheOutputSink = error("not used")
        override fun rename(source: File, destination: File) = error("not used")
        override fun delete(file: File): Boolean {
            if (file.path in failDelete) return false
            files.remove(file.path)
            return true
        }
    }

    private fun <T> awaitResult(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result).getOrThrow()
    }
}
