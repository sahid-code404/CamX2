package com.sahidcode404.camx.core.camera.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraResourceCountersTest {
    @Test
    fun underflowIsRejectedWithoutCorruptingSnapshot() {
        val counters = CameraResourceCounters()
        assertThrows(IllegalStateException::class.java) {
            counters.decrement(ResourceKind.IMAGE)
        }
        assertEquals(0, counters.snapshot().openImages)
    }

    @Test
    fun byteCountersTrackLargeValuesWithoutNarrowing() {
        val counters = CameraResourceCounters()
        val bytes = Int.MAX_VALUE.toLong() + 10L
        counters.increment(ResourceKind.NATIVE_BUFFER_BYTES, bytes)
        assertEquals(bytes, counters.snapshot().nativeBufferBytes)
        counters.decrement(ResourceKind.NATIVE_BUFFER_BYTES, bytes)
        assertEquals(0L, counters.snapshot().nativeBufferBytes)
    }
}
