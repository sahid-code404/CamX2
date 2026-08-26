package com.sahidcode404.camx.core.camera.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeCoreSnapshotDecoderTest {
    @Test
    fun exactApi23SnapshotDecodesAndDoesNotAliasJniArray() {
        val values = validSnapshot(androidApi = 23, pointerBits = 32L)

        val decodedOrNull = NativeCoreSnapshotDecoder.decode(values, expectedAndroidApi = 23)
        assertNotNull(decodedOrNull)
        val decoded = checkNotNull(decodedOrNull)
        assertEquals(2L, decoded.schema)
        assertEquals(23L, decoded.androidApi)
        assertEquals(23L, decoded.compiledApi)
        assertEquals(32L, decoded.pointerBits)
        assertArrayEquals(longArrayOf(0L, 1L, 2L, 3L, 4L, 5L), decoded.counters)

        values[4] = 99L
        assertEquals(0L, decoded.counters[0])
    }

    @Test
    fun unknownSchemaAndEveryNonExactLengthAreRejected() {
        val wrongSchema = validSnapshot().also { it[0] = 1L }
        assertNull(NativeCoreSnapshotDecoder.decode(wrongSchema, expectedAndroidApi = 23))
        assertNull(NativeCoreSnapshotDecoder.decode(null, expectedAndroidApi = 23))
        assertNull(
            NativeCoreSnapshotDecoder.decode(
                validSnapshot().copyOf(9),
                expectedAndroidApi = 23,
            ),
        )
        assertNull(
            NativeCoreSnapshotDecoder.decode(
                validSnapshot().copyOf(11),
                expectedAndroidApi = 23,
            ),
        )
    }

    @Test
    fun api23ContractRejectsUnsupportedOrMismatchedRuntimeAndCompileLevels() {
        assertNull(NativeCoreSnapshotDecoder.decode(validSnapshot(androidApi = 22), 22))
        assertNull(NativeCoreSnapshotDecoder.decode(validSnapshot(androidApi = 24), 23))
        assertNull(
            NativeCoreSnapshotDecoder.decode(
                validSnapshot().also { it[2] = 24L },
                expectedAndroidApi = 23,
            ),
        )
        assertNotNull(
            NativeCoreSnapshotDecoder.decode(
                validSnapshot(androidApi = 37),
                expectedAndroidApi = 37,
            ),
        )
    }

    @Test
    fun invalidPointerWidthAndNegativeCountersAreRejected() {
        assertNull(
            NativeCoreSnapshotDecoder.decode(
                validSnapshot(pointerBits = 128L),
                expectedAndroidApi = 23,
            ),
        )
        assertNull(
            NativeCoreSnapshotDecoder.decode(
                validSnapshot().also { it[7] = -1L },
                expectedAndroidApi = 23,
            ),
        )
    }

    private fun validSnapshot(
        androidApi: Int = 23,
        pointerBits: Long = 64L,
    ) = longArrayOf(
        2L,
        androidApi.toLong(),
        23L,
        pointerBits,
        0L,
        1L,
        2L,
        3L,
        4L,
        5L,
    )
}
