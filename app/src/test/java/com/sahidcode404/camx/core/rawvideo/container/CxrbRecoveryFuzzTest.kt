package com.sahidcode404.camx.core.rawvideo.container

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CxrbRecoveryFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deterministicMutationAndTruncationFuzzNeverEscapesRecoveryBounds() {
        val source = temporaryFolder.newFile("fuzz-source.cxrb")
        CxrbReferenceWriter(source, m2aWriterConfig()).use { writer ->
            repeat(3) { segment ->
                val ordinal = segment.toULong()
                writer.beginSegment(CxrbSegmentEpoch(ordinal, 0uL, 0uL, FrameOrdinal(ordinal)))
                writer.appendFrame(m2aFrame(ordinal))
                writer.commitSegment()
            }
        }
        val valid = source.readBytes()
        val random = Random(0x43585242L)
        repeat(200) { iteration ->
            val mutated = valid.copyOf()
            val file: File = temporaryFolder.newFile("fuzz-$iteration.cxrb")
            if (iteration % 2 == 0) {
                val minimum = CxrbFormat.FILE_HEADER_BYTES
                val newLength = minimum + random.nextInt(valid.size - minimum)
                file.writeBytes(mutated.copyOf(newLength))
            } else {
                val index = CxrbFormat.FILE_HEADER_BYTES + random.nextInt(valid.size - CxrbFormat.FILE_HEADER_BYTES)
                mutated[index] = (mutated[index].toInt() xor (1 shl random.nextInt(8))).toByte()
                file.writeBytes(mutated)
            }

            val report = CxrbRecovery.inspect(file)
            assertTrue(report.durableLength in CxrbFormat.FILE_HEADER_BYTES.toLong()..file.length())
            if (report.issue != null) {
                val durable = report.durableLength
                CxrbRecovery.recoverInPlace(file)
                assertEquals(durable, file.length())
                assertTrue(CxrbRecovery.inspect(file).isFullyValid)
            }
        }
    }
}
