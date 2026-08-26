package com.sahidcode404.camx.core.rawvideo.recording

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedRawSensorImageTest {
    @Test
    fun canonicalCopyRespectsNonzeroBufferPositionAndLimit() {
        val backing = byteArrayOf(
            99, 98, 97,
            1, 2, 3, 4, 9, 9,
            5, 6, 7, 8,
            88, 87,
        )
        val source = ByteBuffer.wrap(backing).apply {
            position(3)
            limit(13)
        }

        val canonical = copyCanonicalRawPlane(
            sourceBuffer = source,
            width = 2,
            height = 2,
            rowStrideBytes = 6,
            pixelStrideBytes = 2,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), canonical)
        assertEquals(3, source.position())
        assertEquals(13, source.limit())
    }

    @Test
    fun canonicalCopyFailsWhenDeclaredRowsExceedBufferWindow() {
        val source = ByteBuffer.wrap(ByteArray(16)).apply {
            position(3)
            limit(12)
        }

        val failure = runCatching {
            copyCanonicalRawPlane(
                sourceBuffer = source,
                width = 2,
                height = 2,
                rowStrideBytes = 6,
                pixelStrideBytes = 2,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("buffer window"))
    }
}
