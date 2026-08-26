package com.sahidcode404.camx.core.rawvideo.container

import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CxrbReferenceWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkpointedSegmentsRecoverExactlyWithExplicitGap() {
        val file = temporaryFolder.newFile("roundtrip.cxrb")
        CxrbReferenceWriter(file, m2aWriterConfig()).use { writer ->
            writer.beginSegment(CxrbSegmentEpoch(0uL, 0uL, 0uL, FrameOrdinal(0uL)))
            writer.appendFrame(m2aFrame(0uL))
            writer.appendGap(
                RawVideoGap(
                    firstMissingOrdinal = FrameOrdinal(1uL),
                    missingCount = 2uL,
                    reason = "source transaction reported explicit sequence gap",
                    discontinuity = true,
                ),
            )
            writer.appendFrame(m2aFrame(3uL, discontinuityBefore = true))
            writer.commitSegment()
            writer.beginSegment(CxrbSegmentEpoch(1uL, 1uL, 0uL, FrameOrdinal(4uL)))
            writer.appendFrame(m2aFrame(4uL))
            writer.commitSegment()
        }

        val report = CxrbRecovery.inspect(file)
        assertTrue(report.isFullyValid)
        assertNull(report.issue)
        assertEquals(2L, report.segmentsRecovered)
        assertEquals(3L, report.framesRecovered)
        assertEquals(1L, report.gapsRecovered)
        assertEquals(FrameOrdinal(4uL), report.lastCheckpoint?.lastOrdinal)
        assertEquals(8L * 1024L * 1024L * 1024L, report.fileHeader?.storage?.maxFileBytes)
        assertTrue(report.fileHeader?.storage?.supports64BitOffsets == true)
    }

    @Test
    fun normalCloseDropsUncheckpointedTail() {
        val file = temporaryFolder.newFile("cancel.cxrb")
        lateinit var firstCheckpoint: CxrbCheckpoint
        CxrbReferenceWriter(file, m2aWriterConfig()).use { writer ->
            writer.beginSegment(CxrbSegmentEpoch(0uL, 0uL, 0uL, FrameOrdinal(0uL)))
            writer.appendFrame(m2aFrame(0uL))
            firstCheckpoint = writer.commitSegment()
            writer.beginSegment(CxrbSegmentEpoch(1uL, 0uL, 0uL, FrameOrdinal(1uL)))
            writer.appendFrame(m2aFrame(1uL))
        }
        assertEquals(firstCheckpoint.durableFileLength, file.length())
        val report = CxrbRecovery.inspect(file)
        assertTrue(report.isFullyValid)
        assertEquals(1L, report.segmentsRecovered)
        assertEquals(1L, report.framesRecovered)
    }

    @Test
    fun truncatedTailRecoversToPreviousDurableCheckpoint() {
        val file = temporaryFolder.newFile("truncated.cxrb")
        val (first, second) = writeTwoCheckpointFile(file)
        RandomAccessFile(file, "rw").use { it.setLength(second.durableFileLength - 17L) }

        val damaged = CxrbRecovery.inspect(file)
        assertFalse(damaged.isFullyValid)
        assertEquals(first.durableFileLength, damaged.durableLength)
        assertEquals(1L, damaged.segmentsRecovered)

        val recovery = CxrbRecovery.recoverInPlace(file)
        assertEquals(first.durableFileLength, file.length())
        assertEquals(first.durableFileLength, recovery.durableLength)
        assertTrue(CxrbRecovery.inspect(file).isFullyValid)
    }

    @Test
    fun corruptionInsideLastSegmentHasOneSegmentCorruptionRadius() {
        val file = temporaryFolder.newFile("corrupt-tail.cxrb")
        val (first, second) = writeTwoCheckpointFile(file)
        RandomAccessFile(file, "rw").use { raf ->
            val corruptionOffset = second.segmentStartOffset + CxrbFormat.SEGMENT_HEADER_BYTES + 32L
            raf.seek(corruptionOffset)
            val original = raf.readByte().toInt()
            raf.seek(corruptionOffset)
            raf.writeByte(original xor 0x5a)
        }
        val report = CxrbRecovery.inspect(file)
        assertFalse(report.isFullyValid)
        assertEquals(first.durableFileLength, report.durableLength)
        assertEquals(1L, report.segmentsRecovered)
        assertTrue(report.issue?.offset ?: 0L >= second.segmentStartOffset)
    }

    @Test
    fun wrongPackedNoneDigestIsRejectedBeforeWriterOwnership() {
        assertThrows(IllegalArgumentException::class.java) {
            PackedNoneFrame(
                frameOrdinal = FrameOrdinal(0uL),
                identity = m2aIdentity(1L),
                canonicalRaster = CanonicalRasterDigest("0".repeat(64), TEST_PACKED_NONE_PAYLOAD.size.toLong()),
                payload = TEST_PACKED_NONE_PAYLOAD,
                hostTimestampNs = 2_000_000L,
                normalizedTimestampNs = 1_000_050L,
                timebaseUncertaintyNs = 10L,
            )
        }
    }

    @Test
    fun identityChangeInsideSegmentIsRejected() {
        val file = temporaryFolder.newFile("identity-change.cxrb")
        CxrbReferenceWriter(file, m2aWriterConfig()).use { writer ->
            writer.beginSegment(CxrbSegmentEpoch(0uL, 0uL, 0uL, FrameOrdinal(0uL)))
            writer.appendFrame(m2aFrame(0uL, profile = "profile-a"))
            assertThrows(IllegalArgumentException::class.java) {
                writer.appendFrame(m2aFrame(1uL, profile = "profile-b"))
            }
        }
        val report = CxrbRecovery.inspect(file)
        assertTrue(report.isFullyValid)
        assertEquals(0L, report.segmentsRecovered)
        assertEquals(CxrbFormat.FILE_HEADER_BYTES.toLong(), file.length())
    }

    @Test
    fun declaredFileLimitFailsBeforePartialFrameWrite() {
        val file = temporaryFolder.newFile("bounded.cxrb")
        val maxBytes = CxrbFormat.FILE_HEADER_BYTES.toLong() + CxrbFormat.SEGMENT_HEADER_BYTES + 32L
        CxrbReferenceWriter(file, m2aWriterConfig(maxFileBytes = maxBytes)).use { writer ->
            writer.beginSegment(CxrbSegmentEpoch(0uL, 0uL, 0uL, FrameOrdinal(0uL)))
            val lengthBefore = file.length()
            assertThrows(IllegalArgumentException::class.java) { writer.appendFrame(m2aFrame(0uL)) }
            assertEquals(lengthBefore, file.length())
        }
        assertEquals(CxrbFormat.FILE_HEADER_BYTES.toLong(), file.length())
    }

    @Test
    fun uint64MaximumOrdinalCanBeTheFinalRecord() {
        val file = temporaryFolder.newFile("uint64-max.cxrb")
        CxrbReferenceWriter(file, m2aWriterConfig()).use { writer ->
            writer.beginSegment(
                CxrbSegmentEpoch(0uL, 0uL, 0uL, FrameOrdinal(ULong.MAX_VALUE)),
            )
            writer.appendFrame(m2aFrame(ULong.MAX_VALUE))
            writer.commitSegment()
        }
        val report = CxrbRecovery.inspect(file)
        assertTrue(report.isFullyValid)
        assertEquals(FrameOrdinal(ULong.MAX_VALUE), report.lastCheckpoint?.lastOrdinal)
    }

    @Test
    fun fileHeaderRoundTripsStorageLimitAboveTwoGiBWithoutAllocation() {
        val file = temporaryFolder.newFile("large-offset-header.cxrb")
        val fiveGiB = 5L * 1024L * 1024L * 1024L
        CxrbReferenceWriter(file, m2aWriterConfig(maxFileBytes = fiveGiB)).close()
        val report = CxrbRecovery.inspect(file)
        assertTrue(report.isFullyValid)
        assertEquals(fiveGiB, report.fileHeader?.storage?.maxFileBytes)
        assertEquals(CxrbFormat.FILE_HEADER_BYTES.toLong(), report.durableLength)
    }
}
