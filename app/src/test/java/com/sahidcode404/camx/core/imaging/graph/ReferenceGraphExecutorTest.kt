package com.sahidcode404.camx.core.imaging.graph

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceGraphExecutorTest {
    @Test
    fun referenceExecutionIsBitExactAndRepeatable() {
        val source = m3SourceEvidence(payload = byteArrayOf(9, 7, 5, 3, 1, 2, 4, 6))
        val type = source.sourceValue.type
        val branchA = GraphValue.intermediate(GraphValueId(1), type)
        val branchB = GraphValue.intermediate(GraphValueId(2), type)
        val finalA = GraphValue.intermediate(GraphValueId(3), type, GraphValueLifetime.FINAL_OUTPUT)
        val finalB = GraphValue.intermediate(GraphValueId(4), type, GraphValueLifetime.FINAL_OUTPUT)
        val plan = GraphCompiler.compile(
            GraphDefinition(
                values = listOf(source.sourceValue, branchA, branchB, finalA, finalB),
                nodes = listOf(
                    m3ForkNode(0, 0, listOf(1, 2)),
                    m3CopyNode(1, 1, 3),
                    m3CopyNode(2, 2, 4, M3ReferenceAlgorithms.CALIBRATION_GATE),
                ),
                finalOutputs = listOf(GraphValueId(3), GraphValueId(4)),
            ),
            m3Budget(),
        )

        val inputs = mapOf(source.record.sourceId to source.payload)
        val first = ReferenceGraphExecutor.execute(plan, inputs)
        val second = ReferenceGraphExecutor.execute(plan, inputs)

        assertEquals(2, first.outputs.size)
        first.outputs.forEach { output ->
            assertArrayEquals(source.payload, output.bytes())
            assertEquals(sha256(source.payload), output.sha256)
            assertEquals(source.payload.size.toLong(), output.byteCount)
        }
        assertEquals(first.outputs.map { it.sha256 }, second.outputs.map { it.sha256 })
        assertEquals(first.outputs.map { it.valueId }, second.outputs.map { it.valueId })
    }

    @Test
    fun sourceDigestMismatchFailsClosed() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val plan = GraphCompiler.compile(
            GraphDefinition(
                values = listOf(source.sourceValue, output),
                nodes = listOf(m3CopyNode(0, 0, 1)),
                finalOutputs = listOf(GraphValueId(1)),
            ),
            m3Budget(),
        )
        val mutated = source.payload.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            ReferenceGraphExecutor.execute(plan, mapOf(source.record.sourceId to mutated))
        }
    }

    @Test
    fun returnedOutputBytesAreDefensiveCopies() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val plan = GraphCompiler.compile(
            GraphDefinition(
                values = listOf(source.sourceValue, output),
                nodes = listOf(m3CopyNode(0, 0, 1)),
                finalOutputs = listOf(GraphValueId(1)),
            ),
            m3Budget(),
        )
        val result = ReferenceGraphExecutor.execute(plan, mapOf(source.record.sourceId to source.payload))
        val firstRead = result.outputs.single().bytes()
        firstRead[0] = 99
        val secondRead = result.outputs.single().bytes()

        assertNotEquals(firstRead[0], secondRead[0])
        assertArrayEquals(source.payload, secondRead)
    }

    @Test
    fun missingSourcePayloadFailsClosed() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val plan = GraphCompiler.compile(
            GraphDefinition(
                values = listOf(source.sourceValue, output),
                nodes = listOf(m3CopyNode(0, 0, 1)),
                finalOutputs = listOf(GraphValueId(1)),
            ),
            m3Budget(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ReferenceGraphExecutor.execute(plan, emptyMap())
        }
    }
}
