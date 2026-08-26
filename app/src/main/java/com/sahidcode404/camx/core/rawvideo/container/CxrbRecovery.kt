package com.sahidcode404.camx.core.rawvideo.container

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32

/** Bounded sequential validator/recovery tool for the provisional CXRB candidate. */
object CxrbRecovery {
    fun inspect(
        file: File,
        limits: CxrbRecoveryLimits = CxrbRecoveryLimits(),
    ): CxrbRecoveryReport {
        if (!file.isFile) {
            return failureReport(
                problem = CxrbRecoveryProblem.BAD_FILE_HEADER,
                offset = 0L,
                detail = "CXRB path is not a regular file",
            )
        }
        RandomAccessFile(file, "r").use { input ->
            var header: CxrbFileHeaderSummary? = null
            var durableLength = 0L
            var recoveredSegments = 0L
            var recoveredFrames = 0L
            var recoveredGaps = 0L
            var lastCheckpoint: CxrbCheckpoint? = null
            try {
                if (input.length() < CxrbFormat.FILE_HEADER_BYTES.toLong()) {
                    throw ParseFailure(
                        CxrbRecoveryProblem.TRUNCATED_TAIL,
                        0L,
                        "CXRB file header is truncated",
                    )
                }
                if (input.length() > limits.maxScanBytes) {
                    throw ParseFailure(
                        CxrbRecoveryProblem.LIMIT_EXCEEDED,
                        limits.maxScanBytes,
                        "CXRB file exceeds the configured recovery scan bound",
                    )
                }
                header = parseFileHeader(readExact(input, CxrbFormat.FILE_HEADER_BYTES, 0L), limits)
                durableLength = CxrbFormat.FILE_HEADER_BYTES.toLong()
                var previousSegmentOrdinal: ULong? = null
                var nextGlobalOrdinal: FrameOrdinal? = null
                var hasRecoveredSegment = false

                while (input.filePointer < input.length()) {
                    if (recoveredSegments >= limits.maxSegments.toLong()) {
                        throw ParseFailure(
                            CxrbRecoveryProblem.LIMIT_EXCEEDED,
                            input.filePointer,
                            "CXRB segment count exceeds recovery bounds",
                        )
                    }
                    val segmentStart = input.filePointer
                    val segmentHeader = parseSegmentHeader(
                        readExact(input, CxrbFormat.SEGMENT_HEADER_BYTES, segmentStart),
                        segmentStart,
                        limits,
                        header,
                    )
                    previousSegmentOrdinal?.let { previous ->
                        if (segmentHeader.epoch.segmentOrdinal <= previous) {
                            throw ParseFailure(
                                CxrbRecoveryProblem.BAD_SEGMENT_HEADER,
                                segmentStart,
                                "Segment ordinals must increase monotonically",
                            )
                        }
                    }
                    if (hasRecoveredSegment) {
                        val expected = nextGlobalOrdinal ?: throw ParseFailure(
                            CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL,
                            segmentStart,
                            "No segment may follow a record that exhausted uint64 frame ordinals",
                        )
                        if (segmentHeader.epoch.firstOrdinal != expected) {
                            throw ParseFailure(
                                CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL,
                                segmentStart,
                                "Segment begins at ${segmentHeader.epoch.firstOrdinal.value}, expected ${expected.value}; gaps must be explicit records",
                            )
                        }
                    }
                    previousSegmentOrdinal = segmentHeader.epoch.segmentOrdinal

                    var expectedOrdinal: FrameOrdinal? = segmentHeader.epoch.firstOrdinal
                    var lastOrdinal: FrameOrdinal? = null
                    var recordCount = 0L
                    var frameCount = 0L
                    var gapCount = 0L
                    var checkpointFound = false

                    while (!checkpointFound) {
                        val recordOffset = input.filePointer
                        if (recordOffset >= input.length()) {
                            throw ParseFailure(
                                CxrbRecoveryProblem.TRUNCATED_TAIL,
                                recordOffset,
                                "Segment ended without a durable checkpoint",
                            )
                        }

                        val prefix = peek(input, 8, recordOffset)
                        if (prefix.contentEquals(CxrbFormat.CHECKPOINT_MAGIC)) {
                            if (recordCount == 0L || lastOrdinal == null) {
                                throw ParseFailure(
                                    CxrbRecoveryProblem.BAD_CHECKPOINT,
                                    recordOffset,
                                    "Empty segments cannot be checkpointed",
                                )
                            }
                            val checkpointBytes = readExact(input, CxrbFormat.CHECKPOINT_BYTES, recordOffset)
                            val parsed = parseCheckpoint(
                                bytes = checkpointBytes,
                                offset = recordOffset,
                                epoch = segmentHeader.epoch,
                                recordCount = recordCount,
                                frameCount = frameCount,
                                gapCount = gapCount,
                                segmentStart = segmentStart,
                                expectedLastOrdinal = lastOrdinal,
                            )
                            val actualSegmentDigest = digestRange(input, segmentStart, recordOffset)
                            if (!MessageDigest.isEqual(actualSegmentDigest, parsed.segmentDigest)) {
                                throw ParseFailure(
                                    CxrbRecoveryProblem.BAD_CHECKPOINT,
                                    recordOffset,
                                    "Segment SHA-256 does not match its checkpoint",
                                )
                            }
                            val durableAfterCheckpoint = input.filePointer
                            val checkpoint = CxrbCheckpoint(
                                segmentOrdinal = parsed.segmentOrdinal,
                                recordCount = parsed.recordCount,
                                frameCount = parsed.frameCount,
                                gapCount = parsed.gapCount,
                                segmentStartOffset = parsed.segmentStartOffset,
                                segmentEndOffset = parsed.segmentEndOffset,
                                durableFileLength = durableAfterCheckpoint,
                                firstOrdinal = parsed.firstOrdinal,
                                lastOrdinal = parsed.lastOrdinal,
                                segmentSha256 = parsed.segmentDigest.toHexLower(),
                            )
                            durableLength = durableAfterCheckpoint
                            lastCheckpoint = checkpoint
                            recoveredSegments += 1
                            recoveredFrames += frameCount
                            recoveredGaps += gapCount
                            nextGlobalOrdinal = expectedOrdinal
                            hasRecoveredSegment = true
                            checkpointFound = true
                            continue
                        }

                        if (recordCount >= limits.maxRecordsPerSegment.toLong() ||
                            recordCount >= segmentHeader.maxRecords.toLong()
                        ) {
                            throw ParseFailure(
                                CxrbRecoveryProblem.LIMIT_EXCEEDED,
                                recordOffset,
                                "Segment record count exceeds declared bounds",
                            )
                        }

                        val magic = ByteBuffer.wrap(prefix, 0, Int.SIZE_BYTES)
                            .order(ByteOrder.BIG_ENDIAN)
                            .int
                        when (magic) {
                            CxrbFormat.FRAME_MAGIC -> {
                                val expected = expectedOrdinal ?: throw ParseFailure(
                                    CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL,
                                    recordOffset,
                                    "uint64 frame ordinal space was already exhausted",
                                )
                                val parsedOrdinal = parseAndValidateFrame(input, recordOffset, expected, limits)
                                lastOrdinal = parsedOrdinal
                                expectedOrdinal = nextOrdinalAfter(parsedOrdinal, 1uL)
                                frameCount += 1
                            }
                            CxrbFormat.GAP_MAGIC -> {
                                val expected = expectedOrdinal ?: throw ParseFailure(
                                    CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL,
                                    recordOffset,
                                    "uint64 frame ordinal space was already exhausted",
                                )
                                val gap = parseAndValidateGap(input, recordOffset, expected)
                                lastOrdinal = lastCoveredOrdinal(gap.firstMissingOrdinal, gap.missingCount)
                                expectedOrdinal = nextOrdinalAfter(gap.firstMissingOrdinal, gap.missingCount)
                                gapCount += 1
                            }
                            else -> throw ParseFailure(
                                CxrbRecoveryProblem.BAD_RECORD_HEADER,
                                recordOffset,
                                "Unknown CXRB record magic",
                            )
                        }
                        recordCount += 1
                    }
                }
                return CxrbRecoveryReport(
                    fileHeader = header,
                    durableLength = durableLength,
                    segmentsRecovered = recoveredSegments,
                    framesRecovered = recoveredFrames,
                    gapsRecovered = recoveredGaps,
                    lastCheckpoint = lastCheckpoint,
                    issue = null,
                )
            } catch (failure: ParseFailure) {
                return CxrbRecoveryReport(
                    fileHeader = header,
                    durableLength = durableLength,
                    segmentsRecovered = recoveredSegments,
                    framesRecovered = recoveredFrames,
                    gapsRecovered = recoveredGaps,
                    lastCheckpoint = lastCheckpoint,
                    issue = CxrbRecoveryIssue(failure.problem, failure.offset, failure.message ?: failure.problem.name),
                )
            } catch (failure: IllegalArgumentException) {
                return CxrbRecoveryReport(
                    fileHeader = header,
                    durableLength = durableLength,
                    segmentsRecovered = recoveredSegments,
                    framesRecovered = recoveredFrames,
                    gapsRecovered = recoveredGaps,
                    lastCheckpoint = lastCheckpoint,
                    issue = CxrbRecoveryIssue(
                        CxrbRecoveryProblem.BAD_RECORD_BODY,
                        input.filePointer,
                        failure.message ?: "Malformed CXRB value",
                    ),
                )
            }
        }
    }

    fun recoverInPlace(
        file: File,
        limits: CxrbRecoveryLimits = CxrbRecoveryLimits(),
    ): CxrbRecoveryReport {
        val report = inspect(file, limits)
        if (report.fileHeader != null && report.issue != null && file.length() > report.durableLength) {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(report.durableLength)
                output.fd.sync()
            }
        }
        return report
    }

    private fun parseFileHeader(bytes: ByteArray, limits: CxrbRecoveryLimits): CxrbFileHeaderSummary {
        validateBlockCrc(bytes, CxrbRecoveryProblem.BAD_FILE_HEADER, 0L)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(CxrbFormat.FILE_MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(CxrbFormat.FILE_MAGIC)) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_FILE_HEADER, 0L, "CXRB file magic mismatch")
        }
        val version = buffer.int
        if (version != CxrbFormat.VERSION) {
            throw ParseFailure(CxrbRecoveryProblem.UNSUPPORTED_VERSION, 8L, "Unsupported CXRB version $version")
        }
        if (buffer.int != CxrbFormat.FILE_HEADER_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_FILE_HEADER, 12L, "CXRB file header size mismatch")
        }
        val maxFrameBytes = buffer.long
        val maxMetadataBytes = buffer.int
        val maxSegmentRecords = buffer.int
        val maxFileBytes = buffer.long
        val sustained = buffer.long
        val flags = buffer.long
        val storageNameLength = buffer.int
        val storageNameCrc = buffer.int
        if (maxFrameBytes !in 1L..limits.maxFrameBytes ||
            maxMetadataBytes !in 1..limits.maxMetadataBytes ||
            maxSegmentRecords !in 1..limits.maxRecordsPerSegment ||
            maxFileBytes < CxrbFormat.FILE_HEADER_BYTES.toLong()
        ) {
            throw ParseFailure(CxrbRecoveryProblem.LIMIT_EXCEEDED, 16L, "CXRB file header declares unsupported bounds")
        }
        if (storageNameLength !in 1..RawVideoContainerLimits.MAX_STORAGE_CLASS_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_FILE_HEADER, 56L, "Storage class length is invalid")
        }
        val storageBytes = bytes.copyOfRange(64, 64 + storageNameLength)
        if (CxrbBinary.crc32(storageBytes) != storageNameCrc) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_FILE_HEADER, 60L, "Storage class checksum mismatch")
        }
        val storageName = String(storageBytes, StandardCharsets.UTF_8)
        val supportsSync = flags and CxrbFormat.STORAGE_FLAG_DURABLE_SYNC != 0L
        val supports64Bit = flags and CxrbFormat.STORAGE_FLAG_64_BIT_OFFSETS != 0L
        if (!supportsSync || !supports64Bit) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_FILE_HEADER, 48L, "CXRB storage declaration lacks required capabilities")
        }
        return CxrbFileHeaderSummary(
            version = version,
            maxFrameBytes = maxFrameBytes,
            maxMetadataBytes = maxMetadataBytes,
            maxSegmentRecords = maxSegmentRecords,
            storage = StorageCapabilityDeclaration(
                storageClass = storageName,
                maxFileBytes = maxFileBytes,
                declaredSustainedWriteBytesPerSecond = sustained.takeIf { it >= 0L },
                supportsDurableSync = supportsSync,
                supports64BitOffsets = supports64Bit,
            ),
        )
    }

    private fun parseSegmentHeader(
        bytes: ByteArray,
        offset: Long,
        limits: CxrbRecoveryLimits,
        fileHeader: CxrbFileHeaderSummary,
    ): ParsedSegmentHeader {
        validateBlockCrc(bytes, CxrbRecoveryProblem.BAD_SEGMENT_HEADER, offset)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(CxrbFormat.SEGMENT_MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(CxrbFormat.SEGMENT_MAGIC)) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_SEGMENT_HEADER, offset, "Segment magic mismatch")
        }
        val version = buffer.int
        if (version != CxrbFormat.VERSION) {
            throw ParseFailure(CxrbRecoveryProblem.UNSUPPORTED_VERSION, offset + 8, "Unsupported segment version $version")
        }
        if (buffer.int != CxrbFormat.SEGMENT_HEADER_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_SEGMENT_HEADER, offset + 12, "Segment header size mismatch")
        }
        val epoch = CxrbSegmentEpoch(
            segmentOrdinal = buffer.long.toULong(),
            representationEpoch = buffer.long.toULong(),
            codecEpoch = buffer.long.toULong(),
            firstOrdinal = FrameOrdinal(buffer.long.toULong()),
        )
        val maxRecords = buffer.int
        if (maxRecords !in 1..limits.maxRecordsPerSegment || maxRecords > fileHeader.maxSegmentRecords) {
            throw ParseFailure(CxrbRecoveryProblem.LIMIT_EXCEEDED, offset + 48, "Segment record bound is invalid")
        }
        return ParsedSegmentHeader(epoch, maxRecords)
    }

    private fun parseAndValidateFrame(
        input: RandomAccessFile,
        offset: Long,
        expectedOrdinal: FrameOrdinal,
        limits: CxrbRecoveryLimits,
    ): FrameOrdinal {
        val header = readExact(input, CxrbFormat.FRAME_HEADER_BYTES, offset)
        validateBlockCrc(header, CxrbRecoveryProblem.BAD_RECORD_HEADER, offset)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != CxrbFormat.FRAME_MAGIC || buffer.int != CxrbFormat.RECORD_KIND_FRAME) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset, "Frame record header mismatch")
        }
        if (buffer.int != CxrbFormat.FRAME_HEADER_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset + 8, "Frame header size mismatch")
        }
        val flags = buffer.int
        if (flags and CxrbFormat.FLAG_DISCONTINUITY.inv() != 0) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset + 12, "Unknown frame flags")
        }
        val ordinal = FrameOrdinal(buffer.long.toULong())
        if (ordinal != expectedOrdinal) {
            throw ParseFailure(
                CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL,
                offset + 16,
                "Frame ordinal ${ordinal.value} does not match expected ${expectedOrdinal.value}",
            )
        }
        val sensorTimestamp = buffer.long
        val hostTimestamp = buffer.long
        val normalizedTimestamp = buffer.long
        val uncertainty = buffer.long
        if (sensorTimestamp <= 0L || hostTimestamp <= 0L ||
            ((normalizedTimestamp == -1L) != (uncertainty == -1L)) ||
            (normalizedTimestamp != -1L && normalizedTimestamp <= 0L) ||
            (uncertainty < -1L)
        ) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset + 24, "Frame timestamp contract is invalid")
        }
        val descriptorLength = buffer.int
        val identityLength = buffer.int
        val metadataLength = buffer.int
        buffer.int
        val payloadLength = buffer.long
        val expectedPayloadCrc = buffer.int
        val expectedPayloadDigest = ByteArray(32).also(buffer::get)
        val expectedDescriptorDigest = ByteArray(32).also(buffer::get)
        val expectedIdentityDigest = ByteArray(32).also(buffer::get)
        val expectedMetadataCrc = buffer.int
        val expectedDescriptorCrc = buffer.int
        val expectedIdentityCrc = buffer.int
        buffer.long

        if (descriptorLength !in 1..limits.maxDescriptorBytes ||
            identityLength !in 1..limits.maxIdentityBytes ||
            metadataLength !in 0..limits.maxMetadataBytes ||
            payloadLength !in 1L..limits.maxFrameBytes
        ) {
            throw ParseFailure(CxrbRecoveryProblem.LIMIT_EXCEEDED, offset, "Frame body lengths exceed recovery bounds")
        }
        val bodyBytes = checkedOffsetAdd(
            checkedOffsetAdd(descriptorLength.toLong(), identityLength.toLong()),
            checkedOffsetAdd(metadataLength.toLong(), payloadLength),
        )
        val bodyStart = input.filePointer
        val bodyEnd = checkedOffsetAdd(bodyStart, bodyBytes)
        if (bodyEnd > input.length()) {
            throw ParseFailure(CxrbRecoveryProblem.TRUNCATED_TAIL, bodyStart, "Frame body is truncated")
        }
        if (bodyEnd > limits.maxScanBytes) {
            throw ParseFailure(CxrbRecoveryProblem.LIMIT_EXCEEDED, bodyStart, "Frame body exceeds recovery scan bound")
        }

        val descriptor = readExact(input, descriptorLength, input.filePointer)
        if (CxrbBinary.crc32(descriptor) != expectedDescriptorCrc ||
            !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(descriptor), expectedDescriptorDigest)
        ) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_BODY, bodyStart, "Representation descriptor integrity failure")
        }
        val identity = readExact(input, identityLength, input.filePointer)
        if (CxrbBinary.crc32(identity) != expectedIdentityCrc ||
            !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(identity), expectedIdentityDigest)
        ) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_BODY, input.filePointer - identityLength, "Identity integrity failure")
        }
        val metadata = readExact(input, metadataLength, input.filePointer)
        if (CxrbBinary.crc32(metadata) != expectedMetadataCrc) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_BODY, input.filePointer - metadataLength, "Metadata integrity failure")
        }
        validatePayload(input, payloadLength, expectedPayloadCrc, expectedPayloadDigest, input.filePointer)
        return ordinal
    }

    private fun parseAndValidateGap(
        input: RandomAccessFile,
        offset: Long,
        expectedOrdinal: FrameOrdinal,
    ): RawVideoGap {
        val header = readExact(input, CxrbFormat.GAP_HEADER_BYTES, offset)
        validateBlockCrc(header, CxrbRecoveryProblem.BAD_RECORD_HEADER, offset)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != CxrbFormat.GAP_MAGIC || buffer.int != CxrbFormat.RECORD_KIND_GAP) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset, "Gap record header mismatch")
        }
        if (buffer.int != CxrbFormat.GAP_HEADER_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset + 8, "Gap header size mismatch")
        }
        val flags = buffer.int
        if (flags and CxrbFormat.FLAG_DISCONTINUITY.inv() != 0) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset + 12, "Unknown gap flags")
        }
        val firstMissing = FrameOrdinal(buffer.long.toULong())
        val missingCount = buffer.long.toULong()
        val reasonLength = buffer.int
        val expectedReasonCrc = buffer.int
        if (firstMissing != expectedOrdinal) {
            throw ParseFailure(
                CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL,
                offset + 16,
                "Gap begins at ${firstMissing.value}, expected ${expectedOrdinal.value}",
            )
        }
        if (missingCount == 0uL || reasonLength !in 1..RawVideoContainerLimits.MAX_GAP_REASON_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_HEADER, offset + 24, "Gap span or reason length is invalid")
        }
        val reasonOffset = input.filePointer
        val reason = readExact(input, reasonLength, reasonOffset)
        if (CxrbBinary.crc32(reason) != expectedReasonCrc) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_RECORD_BODY, reasonOffset, "Gap reason checksum mismatch")
        }
        val reasonText = String(reason, StandardCharsets.UTF_8)
        return try {
            RawVideoGap(
                firstMissingOrdinal = firstMissing,
                missingCount = missingCount,
                reason = reasonText,
                discontinuity = flags and CxrbFormat.FLAG_DISCONTINUITY != 0,
            )
        } catch (failure: IllegalArgumentException) {
            throw ParseFailure(CxrbRecoveryProblem.NON_MONOTONIC_ORDINAL, offset, failure.message ?: "Gap ordinal overflow")
        }
    }

    private fun parseCheckpoint(
        bytes: ByteArray,
        offset: Long,
        epoch: CxrbSegmentEpoch,
        recordCount: Long,
        frameCount: Long,
        gapCount: Long,
        segmentStart: Long,
        expectedLastOrdinal: FrameOrdinal,
    ): ParsedCheckpoint {
        validateBlockCrc(bytes, CxrbRecoveryProblem.BAD_CHECKPOINT, offset)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(CxrbFormat.CHECKPOINT_MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(CxrbFormat.CHECKPOINT_MAGIC)) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_CHECKPOINT, offset, "Checkpoint magic mismatch")
        }
        val version = buffer.int
        if (version != CxrbFormat.VERSION) {
            throw ParseFailure(CxrbRecoveryProblem.UNSUPPORTED_VERSION, offset + 8, "Unsupported checkpoint version $version")
        }
        if (buffer.int != CxrbFormat.CHECKPOINT_BYTES) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_CHECKPOINT, offset + 12, "Checkpoint size mismatch")
        }
        val segmentOrdinal = buffer.long.toULong()
        val declaredRecordCount = buffer.long
        val declaredFrameCount = buffer.long
        val declaredGapCount = buffer.long
        val declaredSegmentStart = buffer.long
        val declaredSegmentEnd = buffer.long
        val firstOrdinal = FrameOrdinal(buffer.long.toULong())
        val lastOrdinal = FrameOrdinal(buffer.long.toULong())
        val digest = ByteArray(32).also(buffer::get)
        if (segmentOrdinal != epoch.segmentOrdinal ||
            declaredRecordCount != recordCount ||
            declaredFrameCount != frameCount ||
            declaredGapCount != gapCount ||
            declaredFrameCount + declaredGapCount != declaredRecordCount ||
            declaredSegmentStart != segmentStart ||
            declaredSegmentEnd != offset ||
            firstOrdinal != epoch.firstOrdinal ||
            lastOrdinal != expectedLastOrdinal
        ) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_CHECKPOINT, offset, "Checkpoint does not bind the parsed segment")
        }
        return ParsedCheckpoint(
            segmentOrdinal = segmentOrdinal,
            recordCount = declaredRecordCount,
            frameCount = declaredFrameCount,
            gapCount = declaredGapCount,
            segmentStartOffset = declaredSegmentStart,
            segmentEndOffset = declaredSegmentEnd,
            firstOrdinal = firstOrdinal,
            lastOrdinal = lastOrdinal,
            segmentDigest = digest,
        )
    }

    private fun validatePayload(
        input: RandomAccessFile,
        payloadLength: Long,
        expectedCrc: Int,
        expectedDigest: ByteArray,
        offset: Long,
    ) {
        val crc = CRC32()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(RawVideoContainerLimits.IO_BUFFER_BYTES)
        var remaining = payloadLength
        while (remaining > 0L) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read <= 0) {
                throw ParseFailure(CxrbRecoveryProblem.TRUNCATED_TAIL, input.filePointer, "Frame payload is truncated")
            }
            crc.update(buffer, 0, read)
            digest.update(buffer, 0, read)
            remaining -= read.toLong()
        }
        if (crc.value.toInt() != expectedCrc || !MessageDigest.isEqual(digest.digest(), expectedDigest)) {
            throw ParseFailure(CxrbRecoveryProblem.BAD_FRAME_PAYLOAD, offset, "PACKED_NONE payload integrity failure")
        }
    }

    private fun digestRange(input: RandomAccessFile, start: Long, endExclusive: Long): ByteArray {
        require(start >= 0L && endExclusive >= start)
        val restore = input.filePointer
        input.seek(start)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(RawVideoContainerLimits.IO_BUFFER_BYTES)
        var remaining = endExclusive - start
        while (remaining > 0L) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read <= 0) {
                input.seek(restore)
                throw ParseFailure(CxrbRecoveryProblem.TRUNCATED_TAIL, input.filePointer, "Segment digest range is truncated")
            }
            digest.update(buffer, 0, read)
            remaining -= read.toLong()
        }
        input.seek(restore)
        return digest.digest()
    }

    private fun validateBlockCrc(bytes: ByteArray, problem: CxrbRecoveryProblem, offset: Long) {
        require(bytes.size >= Int.SIZE_BYTES)
        val expected = ByteBuffer.wrap(bytes, bytes.size - Int.SIZE_BYTES, Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        val actual = CxrbBinary.crc32(bytes, 0, bytes.size - Int.SIZE_BYTES)
        if (expected != actual) {
            throw ParseFailure(problem, offset, "Header/checkpoint CRC32 mismatch")
        }
    }

    private fun readExact(input: RandomAccessFile, byteCount: Int, offset: Long): ByteArray {
        if (byteCount < 0 || checkedOffsetAdd(offset, byteCount.toLong()) > input.length()) {
            throw ParseFailure(CxrbRecoveryProblem.TRUNCATED_TAIL, offset, "CXRB structure is truncated")
        }
        val bytes = ByteArray(byteCount)
        input.readFully(bytes)
        return bytes
    }

    private fun peek(input: RandomAccessFile, byteCount: Int, offset: Long): ByteArray {
        val restore = input.filePointer
        val bytes = readExact(input, byteCount, offset)
        input.seek(restore)
        return bytes
    }

    private data class ParsedSegmentHeader(
        val epoch: CxrbSegmentEpoch,
        val maxRecords: Int,
    )

    private data class ParsedCheckpoint(
        val segmentOrdinal: ULong,
        val recordCount: Long,
        val frameCount: Long,
        val gapCount: Long,
        val segmentStartOffset: Long,
        val segmentEndOffset: Long,
        val firstOrdinal: FrameOrdinal,
        val lastOrdinal: FrameOrdinal,
        val segmentDigest: ByteArray,
    )

    private class ParseFailure(
        val problem: CxrbRecoveryProblem,
        val offset: Long,
        message: String,
    ) : Exception(message)

    private fun failureReport(
        problem: CxrbRecoveryProblem,
        offset: Long,
        detail: String,
    ): CxrbRecoveryReport = CxrbRecoveryReport(
        fileHeader = null,
        durableLength = 0L,
        segmentsRecovered = 0L,
        framesRecovered = 0L,
        gapsRecovered = 0L,
        lastCheckpoint = null,
        issue = CxrbRecoveryIssue(problem, offset, detail),
    )
}
