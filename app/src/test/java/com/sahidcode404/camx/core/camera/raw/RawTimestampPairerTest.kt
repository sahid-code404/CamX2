package com.sahidcode404.camx.core.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTimestampPairerTest {
    @Test
    fun pairsBothCallbackOrders() {
        RawTimestampPairer<FakeImage, String>(2).use { pairer ->
            val first = FakeImage()
            assertNull(pairer.offerImage(10L, first))
            val pair = pairer.offerResult(10L, "result")
            assertEquals(first, pair?.takeImage())
            assertFalse(first.closed)
            first.close()

            val second = FakeImage()
            assertNull(pairer.offerResult(20L, "second"))
            val secondPair = pairer.offerImage(20L, second)
            assertEquals("second", secondPair?.result)
            secondPair?.close()
            assertTrue(second.closed)
        }
    }

    @Test
    fun overflowInvalidAndCloseReleaseEveryOrphan() {
        val pairer = RawTimestampPairer<FakeImage, String>(1)
        val invalid = FakeImage()
        val old = FakeImage()
        val current = FakeImage()
        pairer.offerImage(0L, invalid)
        pairer.offerImage(10L, old)
        pairer.offerImage(20L, current)
        assertTrue(invalid.closed)
        assertTrue(old.closed)
        assertFalse(current.closed)
        pairer.close()
        assertTrue(current.closed)
    }

    @Test
    fun lateImageAfterCloseIsReleasedAndCloseIsIdempotent() {
        val pairer = RawTimestampPairer<FakeImage, String>(timeoutMillis = 50L)
        pairer.close()
        pairer.close()
        val late = FakeImage()
        assertNull(pairer.offerImage(30L, late))
        assertNull(pairer.offerResult(30L, "late"))
        assertTrue(late.closed)
        assertEquals(50L, pairer.timeoutMillis)
    }

    @Test
    fun rejectsTimeoutBeyondSharedRawContract() {
        assertThrows(IllegalArgumentException::class.java) {
            RawTimestampPairer<FakeImage, String>(timeoutMillis = 60_001L)
        }
    }

    private class FakeImage : AutoCloseable {
        var closed = false
        override fun close() { check(!closed); closed = true }
    }
}
