package com.sahidcode404.camx.core.rawvideo.container

import com.sahidcode404.camx.core.camera.acquisition.AcquisitionIdentity
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.InterpretableSensorDomain
import com.sahidcode404.camx.core.camera.acquisition.M1AcquisitionLimits
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

object RawVideoContainerLimits {
    const val MAX_FRAME_BYTES = M1AcquisitionLimits.MAX_CANONICAL_RASTER_BYTES
    const val MAX_DESCRIPTOR_BYTES = 256 * 1024
    const val MAX_IDENTITY_BYTES = 64 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_METADATA_ENTRIES = 256
    const val MAX_METADATA_KEY_BYTES = 256
    const val MAX_METADATA_VALUE_BYTES = 4 * 1024
    const val MAX_GAP_REASON_BYTES = 1024
    const val MAX_STORAGE_CLASS_BYTES = 128
    const val MAX_SEGMENT_RECORDS = 4096
    const val MAX_RECOVERY_SEGMENTS = 1_000_000
    const val MAX_RECOVERY_SCAN_BYTES = 1L shl 40
    const val IO_BUFFER_BYTES = 64 * 1024
}

@JvmInline
value class FrameOrdinal(val value: ULong)

data class StorageCapabilityDeclaration(
    val storageClass: String,
    val maxFileBytes: Long,
    val declaredSustainedWriteBytesPerSecond: Long?,
    val supportsDurableSync: Boolean,
    val supports64BitOffsets: Boolean,
) {
    init {
        val storageClassBytes = storageClass.toByteArray(StandardCharsets.UTF_8)
        require(storageClass.isNotBlank()) { "Storage class must be named" }
        require(storageClassBytes.size <= RawVideoContainerLimits.MAX_STORAGE_CLASS_BYTES) {
            "Storage class name exceeds the container bound"
        }
        require(maxFileBytes >= CxrbFormat.FILE_HEADER_BYTES.toLong()) {
            "Storage maximum file size is too small for a CXRB header"
        }
        require(declaredSustainedWriteBytesPerSecond == null || declaredSustainedWriteBytesPerSecond > 0L) {
            "Declared sustained write throughput must be positive when known"
        }
    }
}

data class CxrbWriterConfig(
    val storage: StorageCapabilityDeclaration,
    val maxFrameBytes: Long = RawVideoContainerLimits.MAX_FRAME_BYTES,
    val maxMetadataBytes: Int = RawVideoContainerLimits.MAX_METADATA_BYTES,
    val maxSegmentRecords: Int = 256,
) {
    init {
        require(maxFrameBytes in 1L..RawVideoContainerLimits.MAX_FRAME_BYTES) {
            "Frame bound exceeds the frozen PACKED_NONE/M1 bound"
        }
        require(maxMetadataBytes in 1..RawVideoContainerLimits.MAX_METADATA_BYTES) {
            "Metadata bound exceeds the M2A parser bound"
        }
        require(maxSegmentRecords in 1..RawVideoContainerLimits.MAX_SEGMENT_RECORDS) {
            "Segment record bound exceeds the M2A parser bound"
        }
        require(storage.supportsDurableSync) {
            "CXRB durable checkpoints require an fsync-equivalent storage capability"
        }
        require(storage.supports64BitOffsets) {
            "CXRB requires 64-bit file offsets"
        }
    }
}

data class CxrbSegmentEpoch(
    val segmentOrdinal: ULong,
    val representationEpoch: ULong,
    val codecEpoch: ULong,
    val firstOrdinal: FrameOrdinal,
)

data class RawVideoMetadataEntry(
    val key: String,
    val value: String,
) {
    init {
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        require(key.isNotBlank() && keyBytes.size <= RawVideoContainerLimits.MAX_METADATA_KEY_BYTES) {
            "RAW-video metadata key must be nonblank and bounded"
        }
        require(valueBytes.size <= RawVideoContainerLimits.MAX_METADATA_VALUE_BYTES) {
            "RAW-video metadata value exceeds the M2A bound"
        }
    }
}

/**
 * M2A input packet for the frozen PACKED_NONE codec baseline. The payload is already the canonical
 * meaningful-row raster: no undefined row padding, no compression, no sample-changing transform.
 */
class PackedNoneFrame(
    val frameOrdinal: FrameOrdinal,
    val identity: AcquisitionIdentity,
    val canonicalRaster: CanonicalRasterDigest,
    payload: ByteArray,
    val hostTimestampNs: Long,
    val normalizedTimestampNs: Long?,
    val timebaseUncertaintyNs: Long?,
    metadata: List<RawVideoMetadataEntry> = emptyList(),
    val discontinuityBefore: Boolean = false,
) {
    internal val payloadBytes: ByteArray = payload.copyOf()
    val metadata: List<RawVideoMetadataEntry> = Collections.unmodifiableList(
        ArrayList(metadata.sortedWith(compareBy(RawVideoMetadataEntry::key, RawVideoMetadataEntry::value))),
    )

    init {
        require(identity.representation.representation is InterpretableSensorDomain) {
            "M2A PACKED_NONE prototype accepts only interpretable sensor-domain evidence"
        }
        require(payloadBytes.isNotEmpty()) { "PACKED_NONE frame payload cannot be empty" }
        require(payloadBytes.size.toLong() <= RawVideoContainerLimits.MAX_FRAME_BYTES) {
            "PACKED_NONE frame exceeds the M2A frame bound"
        }
        require(canonicalRaster.byteCount == payloadBytes.size.toLong()) {
            "PACKED_NONE payload length must equal the canonical raster byte count"
        }
        require(canonicalRaster.sha256 == sha256Hex(payloadBytes)) {
            "PACKED_NONE payload digest must equal the M1 canonical raster digest"
        }
        require(hostTimestampNs > 0L) { "Host timestamp must be positive" }
        require((normalizedTimestampNs == null) == (timebaseUncertaintyNs == null)) {
            "Normalized timestamp requires matching uncertainty"
        }
        require(normalizedTimestampNs == null || normalizedTimestampNs > 0L) {
            "Normalized timestamp must be positive when present"
        }
        require(timebaseUncertaintyNs == null || timebaseUncertaintyNs >= 0L) {
            "Timebase uncertainty cannot be negative"
        }
        require(metadata.size <= RawVideoContainerLimits.MAX_METADATA_ENTRIES) {
            "RAW-video metadata entry count exceeds the M2A bound"
        }
        require(metadata.map(RawVideoMetadataEntry::key).distinct().size == metadata.size) {
            "RAW-video metadata keys must be unique"
        }
    }
}

data class RawVideoGap(
    val firstMissingOrdinal: FrameOrdinal,
    val missingCount: ULong,
    val reason: String,
    val discontinuity: Boolean,
) {
    init {
        require(missingCount > 0uL) { "Gap must cover at least one ordinal" }
        require(reason.isNotBlank()) { "Gap reason must be explicit" }
        require(reason.toByteArray(StandardCharsets.UTF_8).size <= RawVideoContainerLimits.MAX_GAP_REASON_BYTES) {
            "Gap reason exceeds the M2A bound"
        }
        lastCoveredOrdinal(firstMissingOrdinal, missingCount)
    }
}

data class CxrbFileHeaderSummary(
    val version: Int,
    val maxFrameBytes: Long,
    val maxMetadataBytes: Int,
    val maxSegmentRecords: Int,
    val storage: StorageCapabilityDeclaration,
)

data class CxrbCheckpoint(
    val segmentOrdinal: ULong,
    val recordCount: Long,
    val frameCount: Long,
    val gapCount: Long,
    val segmentStartOffset: Long,
    val segmentEndOffset: Long,
    val durableFileLength: Long,
    val firstOrdinal: FrameOrdinal,
    val lastOrdinal: FrameOrdinal,
    val segmentSha256: String,
)

enum class CxrbRecoveryProblem {
    TRUNCATED_TAIL,
    BAD_FILE_HEADER,
    BAD_SEGMENT_HEADER,
    BAD_RECORD_HEADER,
    BAD_RECORD_BODY,
    BAD_FRAME_PAYLOAD,
    BAD_CHECKPOINT,
    NON_MONOTONIC_ORDINAL,
    LIMIT_EXCEEDED,
    UNSUPPORTED_VERSION,
}

data class CxrbRecoveryIssue(
    val problem: CxrbRecoveryProblem,
    val offset: Long,
    val detail: String,
)

data class CxrbRecoveryReport(
    val fileHeader: CxrbFileHeaderSummary?,
    val durableLength: Long,
    val segmentsRecovered: Long,
    val framesRecovered: Long,
    val gapsRecovered: Long,
    val lastCheckpoint: CxrbCheckpoint?,
    val issue: CxrbRecoveryIssue?,
) {
    val isFullyValid: Boolean
        get() = issue == null
}

data class CxrbRecoveryLimits(
    val maxSegments: Int = RawVideoContainerLimits.MAX_RECOVERY_SEGMENTS,
    val maxRecordsPerSegment: Int = RawVideoContainerLimits.MAX_SEGMENT_RECORDS,
    val maxFrameBytes: Long = RawVideoContainerLimits.MAX_FRAME_BYTES,
    val maxDescriptorBytes: Int = RawVideoContainerLimits.MAX_DESCRIPTOR_BYTES,
    val maxIdentityBytes: Int = RawVideoContainerLimits.MAX_IDENTITY_BYTES,
    val maxMetadataBytes: Int = RawVideoContainerLimits.MAX_METADATA_BYTES,
    val maxScanBytes: Long = RawVideoContainerLimits.MAX_RECOVERY_SCAN_BYTES,
) {
    init {
        require(maxSegments in 1..RawVideoContainerLimits.MAX_RECOVERY_SEGMENTS)
        require(maxRecordsPerSegment in 1..RawVideoContainerLimits.MAX_SEGMENT_RECORDS)
        require(maxFrameBytes in 1L..RawVideoContainerLimits.MAX_FRAME_BYTES)
        require(maxDescriptorBytes in 1..RawVideoContainerLimits.MAX_DESCRIPTOR_BYTES)
        require(maxIdentityBytes in 1..RawVideoContainerLimits.MAX_IDENTITY_BYTES)
        require(maxMetadataBytes in 1..RawVideoContainerLimits.MAX_METADATA_BYTES)
        require(maxScanBytes >= CxrbFormat.FILE_HEADER_BYTES.toLong())
    }
}

internal object CxrbFormat {
    val FILE_MAGIC = "CXRBM2A1".toByteArray(StandardCharsets.US_ASCII)
    val SEGMENT_MAGIC = "CXSGM2A1".toByteArray(StandardCharsets.US_ASCII)
    val CHECKPOINT_MAGIC = "CXCKM2A1".toByteArray(StandardCharsets.US_ASCII)
    const val VERSION = 1
    const val FILE_HEADER_BYTES = 256
    const val SEGMENT_HEADER_BYTES = 64
    const val FRAME_HEADER_BYTES = 204
    const val GAP_HEADER_BYTES = 80
    const val CHECKPOINT_BYTES = 128
    const val FRAME_MAGIC = 0x43584652 // CXFR
    const val GAP_MAGIC = 0x43584750 // CXGP
    const val RECORD_KIND_FRAME = 1
    const val RECORD_KIND_GAP = 2
    const val FLAG_DISCONTINUITY = 1
    const val STORAGE_FLAG_DURABLE_SYNC = 1L
    const val STORAGE_FLAG_64_BIT_OFFSETS = 1L shl 1
}

internal fun checkedOffsetAdd(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L) { "File offsets cannot be negative" }
    if (left > Long.MAX_VALUE - right) {
        throw IllegalArgumentException("64-bit file offset overflow")
    }
    return left + right
}

internal fun lastCoveredOrdinal(first: FrameOrdinal, count: ULong): FrameOrdinal {
    require(count > 0uL) { "Ordinal span must be non-empty" }
    val delta = count - 1uL
    require(delta <= ULong.MAX_VALUE - first.value) { "uint64 frame ordinal overflow" }
    return FrameOrdinal(first.value + delta)
}

internal fun nextOrdinalAfter(first: FrameOrdinal, count: ULong): FrameOrdinal? {
    val last = lastCoveredOrdinal(first, count)
    return if (last.value == ULong.MAX_VALUE) null else FrameOrdinal(last.value + 1uL)
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()

internal fun ByteArray.toHexLower(): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        result[index * 2] = alphabet[unsigned ushr 4]
        result[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return String(result)
}
