package com.sahidcode404.camx.core.rawvideo.container

import com.sahidcode404.camx.core.camera.acquisition.AcquisitionIdentity
import com.sahidcode404.camx.core.camera.acquisition.InterpretationField
import com.sahidcode404.camx.core.camera.acquisition.canonicalDescriptorBytes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32

internal object CxrbBinary {
    fun encodeFileHeader(config: CxrbWriterConfig): ByteArray {
        val storageName = config.storage.storageClass.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArray(CxrbFormat.FILE_HEADER_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CxrbFormat.FILE_MAGIC)
        buffer.putInt(CxrbFormat.VERSION)
        buffer.putInt(CxrbFormat.FILE_HEADER_BYTES)
        buffer.putLong(config.maxFrameBytes)
        buffer.putInt(config.maxMetadataBytes)
        buffer.putInt(config.maxSegmentRecords)
        buffer.putLong(config.storage.maxFileBytes)
        buffer.putLong(config.storage.declaredSustainedWriteBytesPerSecond ?: -1L)
        var flags = 0L
        if (config.storage.supportsDurableSync) flags = flags or CxrbFormat.STORAGE_FLAG_DURABLE_SYNC
        if (config.storage.supports64BitOffsets) flags = flags or CxrbFormat.STORAGE_FLAG_64_BIT_OFFSETS
        buffer.putLong(flags)
        buffer.putInt(storageName.size)
        buffer.putInt(crc32(storageName))
        buffer.put(storageName)
        buffer.position(CxrbFormat.FILE_HEADER_BYTES - Int.SIZE_BYTES)
        buffer.putInt(crc32(bytes, 0, CxrbFormat.FILE_HEADER_BYTES - Int.SIZE_BYTES))
        return bytes
    }

    fun encodeSegmentHeader(epoch: CxrbSegmentEpoch, maxRecords: Int): ByteArray {
        val bytes = ByteArray(CxrbFormat.SEGMENT_HEADER_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CxrbFormat.SEGMENT_MAGIC)
        buffer.putInt(CxrbFormat.VERSION)
        buffer.putInt(CxrbFormat.SEGMENT_HEADER_BYTES)
        buffer.putLong(epoch.segmentOrdinal.toLong())
        buffer.putLong(epoch.representationEpoch.toLong())
        buffer.putLong(epoch.codecEpoch.toLong())
        buffer.putLong(epoch.firstOrdinal.value.toLong())
        buffer.putInt(maxRecords)
        buffer.putLong(0L)
        buffer.putInt(crc32(bytes, 0, CxrbFormat.SEGMENT_HEADER_BYTES - Int.SIZE_BYTES))
        return bytes
    }

    fun encodeFrame(
        frame: PackedNoneFrame,
        maxMetadataBytes: Int,
    ): EncodedFrameRecord {
        val descriptor = frame.identity.representation.canonicalDescriptorBytes()
        require(descriptor.size <= RawVideoContainerLimits.MAX_DESCRIPTOR_BYTES) {
            "Representation descriptor exceeds the M2A bound"
        }
        val identity = encodeIdentity(frame.identity)
        require(identity.size <= RawVideoContainerLimits.MAX_IDENTITY_BYTES) {
            "Acquisition identity exceeds the M2A bound"
        }
        val metadata = encodeMetadata(frame.metadata)
        require(metadata.size <= maxMetadataBytes) { "Frame metadata exceeds the configured bound" }
        val payload = frame.payloadBytes
        val payloadDigest = MessageDigest.getInstance("SHA-256").digest(payload)
        val descriptorDigest = MessageDigest.getInstance("SHA-256").digest(descriptor)
        val bytes = ByteArray(CxrbFormat.FRAME_HEADER_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(CxrbFormat.FRAME_MAGIC)
        buffer.putInt(CxrbFormat.RECORD_KIND_FRAME)
        buffer.putInt(CxrbFormat.FRAME_HEADER_BYTES)
        buffer.putInt(if (frame.discontinuityBefore) CxrbFormat.FLAG_DISCONTINUITY else 0)
        buffer.putLong(frame.frameOrdinal.value.toLong())
        buffer.putLong(frame.identity.timebase.imageTimestampNs)
        buffer.putLong(frame.hostTimestampNs)
        buffer.putLong(frame.normalizedTimestampNs ?: -1L)
        buffer.putLong(frame.timebaseUncertaintyNs ?: -1L)
        buffer.putInt(descriptor.size)
        buffer.putInt(identity.size)
        buffer.putInt(metadata.size)
        buffer.putInt(0)
        buffer.putLong(payload.size.toLong())
        buffer.putInt(crc32(payload))
        buffer.put(payloadDigest)
        buffer.put(descriptorDigest)
        buffer.put(identityDigest(identity))
        buffer.putInt(crc32(metadata))
        buffer.putInt(crc32(descriptor))
        buffer.putInt(crc32(identity))
        buffer.putLong(0L)
        buffer.putInt(crc32(bytes, 0, CxrbFormat.FRAME_HEADER_BYTES - Int.SIZE_BYTES))
        return EncodedFrameRecord(bytes, descriptor, identity, metadata, payload)
    }

    fun encodeGap(gap: RawVideoGap): EncodedGapRecord {
        val reason = gap.reason.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArray(CxrbFormat.GAP_HEADER_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(CxrbFormat.GAP_MAGIC)
        buffer.putInt(CxrbFormat.RECORD_KIND_GAP)
        buffer.putInt(CxrbFormat.GAP_HEADER_BYTES)
        buffer.putInt(if (gap.discontinuity) CxrbFormat.FLAG_DISCONTINUITY else 0)
        buffer.putLong(gap.firstMissingOrdinal.value.toLong())
        buffer.putLong(gap.missingCount.toLong())
        buffer.putInt(reason.size)
        buffer.putInt(crc32(reason))
        repeat(4) { buffer.putLong(0L) }
        buffer.putInt(0)
        buffer.putInt(crc32(bytes, 0, CxrbFormat.GAP_HEADER_BYTES - Int.SIZE_BYTES))
        return EncodedGapRecord(bytes, reason)
    }

    fun encodeCheckpoint(
        epoch: CxrbSegmentEpoch,
        recordCount: Long,
        frameCount: Long,
        gapCount: Long,
        segmentStartOffset: Long,
        segmentEndOffset: Long,
        firstOrdinal: FrameOrdinal,
        lastOrdinal: FrameOrdinal,
        segmentDigest: ByteArray,
    ): ByteArray {
        require(segmentDigest.size == 32)
        val bytes = ByteArray(CxrbFormat.CHECKPOINT_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CxrbFormat.CHECKPOINT_MAGIC)
        buffer.putInt(CxrbFormat.VERSION)
        buffer.putInt(CxrbFormat.CHECKPOINT_BYTES)
        buffer.putLong(epoch.segmentOrdinal.toLong())
        buffer.putLong(recordCount)
        buffer.putLong(frameCount)
        buffer.putLong(gapCount)
        buffer.putLong(segmentStartOffset)
        buffer.putLong(segmentEndOffset)
        buffer.putLong(firstOrdinal.value.toLong())
        buffer.putLong(lastOrdinal.value.toLong())
        buffer.put(segmentDigest)
        repeat(3) { buffer.putInt(0) }
        buffer.putInt(crc32(bytes, 0, CxrbFormat.CHECKPOINT_BYTES - Int.SIZE_BYTES))
        return bytes
    }

    fun crc32(bytes: ByteArray): Int = crc32(bytes, 0, bytes.size)

    fun crc32(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    private fun identityDigest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun encodeIdentity(identity: AcquisitionIdentity): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { output ->
            output.writeInt(1)
            output.writeToken(identity.canonicalLensFingerprint.value)
            output.writeToken(identity.cameraProfileFingerprint.value)
            output.writeToken(identity.routeId.value)
            output.writeNullableToken(identity.physicalTarget?.value)
            output.writeLong(identity.providerEpoch)
            output.writeLong(identity.selectionGeneration.value)
            output.writeLong(identity.sessionGeneration.value)
            output.writeLong(identity.captureToken.value)
            output.writeNullableLong(identity.captureGeneration)
            output.writeNullableLong(identity.surfaceGeneration)
            output.writeLong(identity.timebase.imageTimestampNs)
            output.writeNullableLong(identity.timebase.captureResultTimestampNs)
            output.writeNullableLong(identity.timebase.requestIssuedTimestampNs)
            output.writeToken(identity.timebase.declaredTimebase.name)
            output.writeNullableLong(identity.timebase.normalizedOffsetNs)
            output.writeNullableLong(identity.timebase.mappingUncertaintyNs)
            output.writeNullableToken(identity.representation.calibration.identity)
            output.writeNullableToken(identity.representation.calibration.version)
            output.writeLong(java.lang.Double.doubleToLongBits(identity.representation.calibration.confidence))
            output.writeToken(identity.representation.sourceApi.name)
            output.writeInt(identity.representation.interpretationFields.size)
            identity.representation.interpretationFields.forEach { field -> output.writeField(field) }
        }
        return sink.toByteArray()
    }

    private fun encodeMetadata(metadata: List<RawVideoMetadataEntry>): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { output ->
            output.writeInt(metadata.size)
            metadata.forEach { entry ->
                output.writeToken(entry.key)
                output.writeToken(entry.value)
            }
        }
        return sink.toByteArray()
    }

    private fun DataOutputStream.writeField(field: InterpretationField) {
        writeToken(field.key)
        writeToken(field.value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        value?.let { writeLong(it) }
    }

    private fun DataOutputStream.writeNullableToken(value: String?) {
        writeBoolean(value != null)
        value?.let { writeToken(it) }
    }

    private fun DataOutputStream.writeToken(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 64 * 1024) { "CXRB token exceeds the bounded identity format" }
        writeInt(bytes.size)
        write(bytes)
    }
}

internal data class EncodedFrameRecord(
    val header: ByteArray,
    val descriptor: ByteArray,
    val identity: ByteArray,
    val metadata: ByteArray,
    val payload: ByteArray,
) {
    fun byteCount(): Long =
        header.size.toLong() + descriptor.size + identity.size + metadata.size + payload.size
}

internal data class EncodedGapRecord(
    val header: ByteArray,
    val reason: ByteArray,
) {
    fun byteCount(): Long = header.size.toLong() + reason.size
}
