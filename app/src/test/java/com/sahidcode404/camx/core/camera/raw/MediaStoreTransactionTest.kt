package com.sahidcode404.camx.core.camera.raw

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaStoreTransactionTest {
    @Test
    fun publishesSuccessfulWrite() {
        val calls = mutableListOf<String>()
        val result = MediaStoreTransaction(
            insertPending = { calls += "insert"; "row" },
            write = { calls += "write:$it" },
            publish = { calls += "publish:$it" },
            delete = { calls += "delete:$it" },
        ).execute()
        assertEquals("row", result.getOrThrow())
        assertEquals(listOf("insert", "write:row", "publish:row"), calls)
    }

    @Test
    fun deletesRowAfterWriteFailure() {
        val calls = mutableListOf<String>()
        val result = MediaStoreTransaction(
            insertPending = { "row" },
            write = { calls += "write"; error("disk full") },
            publish = { calls += "publish" },
            delete = { calls += "delete" },
        ).execute()
        assertTrue(result.isFailure)
        assertEquals(listOf("write", "delete"), calls)
    }

    @Test
    fun cleanupFailureIsRetainedForDiagnosis() {
        val result = MediaStoreTransaction(
            insertPending = { "row" },
            write = { error("write failed") },
            publish = {},
            delete = { error("delete failed") },
        ).execute()
        assertTrue(result.isFailure)
        assertEquals("write failed", result.exceptionOrNull()?.message)
        assertEquals("delete failed", result.exceptionOrNull()?.suppressed?.single()?.message)
    }

    @Test
    fun cancellationDeletesPendingRowThenPropagates() {
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            MediaStoreTransaction(
                insertPending = { "row" },
                write = { throw CancellationException("cancelled") },
                publish = {},
                delete = { calls += "delete" },
            ).execute()
        }
        assertEquals(listOf("delete"), calls)
    }
}
