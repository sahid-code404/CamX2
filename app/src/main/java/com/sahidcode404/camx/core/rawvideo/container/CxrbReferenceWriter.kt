package com.sahidcode404.camx.core.rawvideo.container

import com.sahidcode404.camx.core.camera.acquisition.canonicalDescriptorBytes
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Sequential, checkpointed M2A reference writer for the provisional CXRB candidate. It performs no
 * camera work and accepts only already-owned PACKED_NONE source packets.
 */
class CxrbReferenceWriter(
    file: File,
    private val config: CxrbWriterConfig,
) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var closed = false
    private var openSegment: OpenSegment? = null
    private var lastDurableOffset = 0L
    private var lastCommittedSegmentOrdinal: ULong? = null
    private var nextGlobalOrdinal: FrameOrdinal? = null
    private var hasCommittedSegment = false

    init {
        require(output.length() == 0L) { "Reference writer creates a new empty CXRB file only" }
        val header = CxrbBinary.encodeFileHeader(config)
        ensureCapacity(header.size.toLong())
        output.write(header)
        output.fd.sync()
        lastDurableOffset = output.filePointer
    }

    @Synchronized
    fun beginSegment(epoch: CxrbSegmentEpoch) {
        ensureOpen()
        check(openSegment == null) { "A CXRB segment is already open" }
        lastCommittedSegmentOrdinal?.let { previous ->
            require(epoch.segmentOrdinal > previous) { "Segment ordinals must increase monotonically" }
        }
        if (hasCommittedSegment) {
            val expected = checkNotNull(nextGlobalOrdinal) { "uint64 frame ordinal space is exhausted" }
            require(epoch.firstOrdinal == expected) {
                "New segment begins at ${epoch.firstOrdinal.value}, expected ${expected.value}; record gaps explicitly"
            }
        }
        val header = CxrbBinary.encodeSegmentHeader(epoch, config.maxSegmentRecords)
        ensureCapacity(header.size.toLong())
        val segmentStart = output.filePointer
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            writeSegmentBytes(header, digest)
            openSegment = OpenSegment(
                epoch = epoch,
                segmentStartOffset = segmentStart,
                digest = digest,
                expectedOrdinal = epoch.firstOrdinal,
            )
        } catch (failure: Throwable) {
            rollbackToLastDurableBoundary()
            throw failure
        }
    }

    @Synchronized
    fun appendFrame(frame: PackedNoneFrame) {
        ensureOpen()
        val state = checkNotNull(openSegment) { "No CXRB segment is open" }
        require(state.recordCount < config.maxSegmentRecords) { "Segment record bound reached" }
        val expected = checkNotNull(state.expectedOrdinal) { "uint64 ordinal space is exhausted" }
        require(frame.frameOrdinal == expected) {
            "Frame ordinal ${frame.frameOrdinal.value} does not match expected ${expected.value}; record a gap explicitly"
        }
        require(frame.payloadBytes.size.toLong() <= config.maxFrameBytes) { "Frame exceeds configured bound" }

        val descriptorDigest = sha256Hex(frame.identity.representation.canonicalDescriptorBytes())
        val identityKey = SegmentIdentityKey(
            canonicalLens = frame.identity.canonicalLensFingerprint.value,
            profile = frame.identity.cameraProfileFingerprint.value,
            route = frame.identity.routeId.value,
            physicalTarget = frame.identity.physicalTarget?.value,
            descriptorSha256 = descriptorDigest,
        )
        val existingKey = state.identityKey
        if (existingKey == null) {
            state.identityKey = identityKey
        } else {
            require(existingKey == identityKey) {
                "Representation or camera identity changed inside a CXRB segment; begin a new representation epoch"
            }
        }

        val encoded = CxrbBinary.encodeFrame(frame, config.maxMetadataBytes)
        ensureCapacity(encoded.byteCount())
        writeSegmentBytes(encoded.header, state.digest)
        writeSegmentBytes(encoded.descriptor, state.digest)
        writeSegmentBytes(encoded.identity, state.digest)
        writeSegmentBytes(encoded.metadata, state.digest)
        writeSegmentBytes(encoded.payload, state.digest)
        state.recordCount += 1
        state.frameCount += 1
        state.lastOrdinal = frame.frameOrdinal
        state.expectedOrdinal = nextOrdinalAfter(frame.frameOrdinal, 1uL)
    }

    @Synchronized
    fun appendGap(gap: RawVideoGap) {
        ensureOpen()
        val state = checkNotNull(openSegment) { "No CXRB segment is open" }
        require(state.recordCount < config.maxSegmentRecords) { "Segment record bound reached" }
        val expected = checkNotNull(state.expectedOrdinal) { "uint64 ordinal space is exhausted" }
        require(gap.firstMissingOrdinal == expected) {
            "Gap begins at ${gap.firstMissingOrdinal.value}, expected ${expected.value}"
        }
        val encoded = CxrbBinary.encodeGap(gap)
        ensureCapacity(encoded.byteCount())
        writeSegmentBytes(encoded.header, state.digest)
        writeSegmentBytes(encoded.reason, state.digest)
        state.recordCount += 1
        state.gapCount += 1
        state.lastOrdinal = lastCoveredOrdinal(gap.firstMissingOrdinal, gap.missingCount)
        state.expectedOrdinal = nextOrdinalAfter(gap.firstMissingOrdinal, gap.missingCount)
    }

    @Synchronized
    fun commitSegment(): CxrbCheckpoint {
        ensureOpen()
        val state = checkNotNull(openSegment) { "No CXRB segment is open" }
        require(state.recordCount > 0) { "Cannot checkpoint an empty CXRB segment" }
        val lastOrdinal = checkNotNull(state.lastOrdinal)
        val segmentEnd = output.filePointer
        val segmentDigest = state.digest.digest()
        val checkpointBytes = CxrbBinary.encodeCheckpoint(
            epoch = state.epoch,
            recordCount = state.recordCount.toLong(),
            frameCount = state.frameCount.toLong(),
            gapCount = state.gapCount.toLong(),
            segmentStartOffset = state.segmentStartOffset,
            segmentEndOffset = segmentEnd,
            firstOrdinal = state.epoch.firstOrdinal,
            lastOrdinal = lastOrdinal,
            segmentDigest = segmentDigest,
        )
        ensureCapacity(checkpointBytes.size.toLong())
        output.write(checkpointBytes)
        output.fd.sync()
        lastDurableOffset = output.filePointer
        lastCommittedSegmentOrdinal = state.epoch.segmentOrdinal
        nextGlobalOrdinal = state.expectedOrdinal
        hasCommittedSegment = true
        openSegment = null
        return CxrbCheckpoint(
            segmentOrdinal = state.epoch.segmentOrdinal,
            recordCount = state.recordCount.toLong(),
            frameCount = state.frameCount.toLong(),
            gapCount = state.gapCount.toLong(),
            segmentStartOffset = state.segmentStartOffset,
            segmentEndOffset = segmentEnd,
            durableFileLength = lastDurableOffset,
            firstOrdinal = state.epoch.firstOrdinal,
            lastOrdinal = lastOrdinal,
            segmentSha256 = segmentDigest.toHexLower(),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (openSegment != null) {
            rollbackToLastDurableBoundary()
            openSegment = null
        }
        output.close()
    }

    private fun ensureOpen() {
        check(!closed) { "CXRB writer is closed" }
    }

    private fun ensureCapacity(additionalBytes: Long) {
        val next = checkedOffsetAdd(output.filePointer, additionalBytes)
        require(next <= config.storage.maxFileBytes) {
            "CXRB append would exceed the declared storage maximum file size"
        }
    }

    private fun writeSegmentBytes(bytes: ByteArray, digest: MessageDigest) {
        output.write(bytes)
        digest.update(bytes)
    }

    private fun rollbackToLastDurableBoundary() {
        output.setLength(lastDurableOffset)
        output.seek(lastDurableOffset)
        output.fd.sync()
    }

    private data class SegmentIdentityKey(
        val canonicalLens: String,
        val profile: String,
        val route: String,
        val physicalTarget: String?,
        val descriptorSha256: String,
    )

    private data class OpenSegment(
        val epoch: CxrbSegmentEpoch,
        val segmentStartOffset: Long,
        val digest: MessageDigest,
        var expectedOrdinal: FrameOrdinal?,
        var identityKey: SegmentIdentityKey? = null,
        var recordCount: Int = 0,
        var frameCount: Int = 0,
        var gapCount: Int = 0,
        var lastOrdinal: FrameOrdinal? = null,
    )
}
